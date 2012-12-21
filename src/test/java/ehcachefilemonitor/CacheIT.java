/*
 * Copyright 2012 Steven Swor.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ehcachefilemonitor;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.Configuration;
import net.sf.ehcache.config.PersistenceConfiguration;
import net.sf.ehcache.constructs.blocking.SelfPopulatingCache;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.junit.After;
import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration tests.
 * @author sswor
 */
public class CacheIT {

    private static final Logger testLogger = LoggerFactory.getLogger(CacheIT.class);

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private CacheManager cacheManager;

    private Ehcache cache = null;

    private SelfPopulatingCache selfPopulatingCache = null;

    private FileAlterationMonitor fileAlterationMonitor = null;

    private FileMonitoringCacheEventListener instance = null;

    private volatile Semaphore semaphore = null;

    @Before
    public void setUp() throws Exception {
        Configuration configuration = new Configuration();
        CacheConfiguration cacheConfiguration = new CacheConfiguration(CacheIT.class.getName(), 1000).eternal(false).persistence(new PersistenceConfiguration().strategy(PersistenceConfiguration.Strategy.NONE));
        configuration.addCache(cacheConfiguration);
        cacheManager = CacheManager.create(configuration);
        cache = cacheManager.getEhcache(CacheIT.class.getName());
        selfPopulatingCache = new SelfPopulatingCache(cache, new FileLoadingCacheEntryFactory() {

            @Override
            protected Object loadObjectFromFile(File file) throws Exception {
                return file;
            }
        });
        cacheManager.replaceCacheWithDecoratedCache(cache, selfPopulatingCache);
        semaphore = new Semaphore(0);
        fileAlterationMonitor = new FileAlterationMonitor(250);
        fileAlterationMonitor.setThreadFactory(new ThreadFactory(){

            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "File Monitor");
            }
        });
        fileAlterationMonitor.start();
        instance = new FileMonitoringCacheEventListener(selfPopulatingCache, fileAlterationMonitor);
        instance.addMonitoredFileListener(new MonitoredFileListener() {

            @Override
            public void startedMonitoringFile(File file) {
                semaphore.release();
            }

            @Override
            public void stoppedMonitoringFile(File file) {
                semaphore.release();
            }
        });
        selfPopulatingCache.getCacheEventNotificationService().registerListener(instance);
    }

    @After
    public void tearDown() {
        selfPopulatingCache.getCacheEventNotificationService().unregisterListener(instance);
        instance.dispose();
        instance = null;
        try {
            fileAlterationMonitor.stop();
        } catch (Exception ex) {
            //trap
        }
        fileAlterationMonitor = null;
        selfPopulatingCache = null;
        cache = null;
        if (cacheManager != null) {
            cacheManager.shutdown();
            cacheManager = null;
        }
    }

    @Test(timeout=10000)
    public void testLazyLoading() throws Exception {
        testLogger.info("Testing lazy-loading file monitor through ehcache.");
        assertEquals(0, selfPopulatingCache.getKeys().size());
        final File created = tempFolder.newFile();
        testLogger.debug("File: {}", created);
        /*
         * Lazy-load the file.
         */
        selfPopulatingCache.get(created);
        semaphore.acquire();
        assertEquals(1, selfPopulatingCache.getKeys().size());
        if (created.canWrite()) {
            testLogger.info("Testing automatic cache removal on file modification.");
            byte[] bytes = new byte[4096];
            FileOutputStream fileOut = null;
            try {
                testLogger.trace("Opening file stream for writing.");
                /*
                 * NOTE: In the environment used to develop and test this code
                 * (Windows XP, Java 6 update 35), the following behaviours were
                 * observed:
                 *
                 * When the FileOutputStream was created with append = false,
                 *  the file change event was fired AFTER the constructor and
                 *  BEFORE the call to write().
                 *
                 * When the FileOutputStream was created with append = true,
                 *  the file change event was fired AFTER the call to write()
                 *  and BEFORE the call to close().
                 *
                 * We can draw two very important conclusions from this:
                 *
                 *  1. In some environments, we will see that the file has
                 *     changed BEFORE the other process has finished making all
                 *     of its changes.
                 *
                 *  2. In some environments, we will see the event for a
                 *     newly-created file BEFORE any of its data has been
                 *     written.
                 *
                 * If we want a fault-tolerant system, we need to keep these two
                 * things in mind when designing a solution around file change
                 * events.
                 */
                fileOut = new FileOutputStream(created, true);
                fileOut.write(bytes);
                testLogger.trace("data written");
            }finally {
                if (fileOut != null) {
                    testLogger.trace("Closing file stream.");
                    try {
                        fileOut.close();
                    }catch(Exception ex) {
                        //trap
                    }
                }
            }
            semaphore.acquire();
            assertEquals(0, selfPopulatingCache.getKeys().size());
            testLogger.info("Automatically removed on modification.  Ensuring it is cached again prior to deleting.");
            selfPopulatingCache.get(created);
            semaphore.acquire();
            assertEquals(1, selfPopulatingCache.getKeys().size());
        } else {
            testLogger.info("Cannot write the file.  Will not test automatic removal.");
        }
        testLogger.trace("Deleting");
        created.delete();
        semaphore.acquire();
        testLogger.info("Checking for cache removal after deletion.");
        assertEquals(0, selfPopulatingCache.getKeys().size());
    }
}
