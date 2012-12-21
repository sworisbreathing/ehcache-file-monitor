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
import java.util.LinkedList;
import java.util.List;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.Configuration;
import net.sf.ehcache.config.PersistenceConfiguration;
import net.sf.ehcache.constructs.blocking.SelfPopulatingCache;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.junit.After;
import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.mockito.Matchers.any;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import static org.powermock.api.mockito.PowerMockito.*;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.rule.PowerMockRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author sswor
 */
@PrepareForTest(FileAlterationMonitor.class)
public class UnmonitoredFilesIT {

    private static final Logger testLogger = LoggerFactory.getLogger(UnmonitoredFilesIT.class);

    @Rule
    public PowerMockRule powerMockRule = new PowerMockRule();

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private CacheManager cacheManager;

    private Ehcache cache = null;

    private SelfPopulatingCache selfPopulatingCache = null;

    private FileAlterationMonitor fileAlterationMonitor = null;

    private FileMonitoringCacheEventListener instance = null;

    private List<FileAlterationObserver> observers = null;

    @Before
    public void setUp() throws Exception {
        Configuration configuration = new Configuration();
        CacheConfiguration cacheConfiguration = new CacheConfiguration(UnmonitoredFilesIT.class.getName(), 1000).eternal(false).persistence(new PersistenceConfiguration().strategy(PersistenceConfiguration.Strategy.NONE));
        configuration.addCache(cacheConfiguration);
        cacheManager = CacheManager.create(configuration);
        cache = cacheManager.getEhcache(UnmonitoredFilesIT.class.getName());
        observers = new LinkedList<FileAlterationObserver>();
        selfPopulatingCache = new SelfPopulatingCache(cache, new FileLoadingCacheEntryFactory() {

            @Override
            protected Object loadObjectFromFile(File file) throws Exception {
                return file;
            }
        });
        cacheManager.replaceCacheWithDecoratedCache(cache, selfPopulatingCache);
        fileAlterationMonitor = spy(new FileAlterationMonitor(250));
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                FileAlterationObserver added = (FileAlterationObserver) invocation.getArguments()[0];
                observers.add(added);
                invocation.callRealMethod();
                return added;
            }
        }).when(fileAlterationMonitor).addObserver(any(FileAlterationObserver.class));
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                FileAlterationObserver removed = (FileAlterationObserver) invocation.getArguments()[0];
                observers.remove(removed);
                invocation.callRealMethod();
                return removed;
            }
        }).when(fileAlterationMonitor).removeObserver(any(FileAlterationObserver.class));
        fileAlterationMonitor.start();
        instance = new FileMonitoringCacheEventListener(cache, fileAlterationMonitor);
        cache.getCacheEventNotificationService().registerListener(instance);

    }

    @After
    public void tearDown() {
        cache.getCacheEventNotificationService().unregisterListener(instance);
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
        observers = null;
    }

    @Test
    public void testWithUnmonitoredFileChangedOrDeleted() throws Exception {
        testLogger.info("Testing behaviour when we are monitoring a file and an unmonitored file is changed or deleted.");
        assertEquals(0, selfPopulatingCache.getKeys().size());
        assertEquals(0, observers.size());
        File monitored = tempFolder.newFile();
        testLogger.debug("File: {}", monitored);
        selfPopulatingCache.get(monitored);
        assertEquals(1, selfPopulatingCache.getKeys().size());
        assertEquals(1, observers.size());
        Thread.sleep(500);
        File unmonitored = tempFolder.newFile();
        if (unmonitored.canWrite()) {
            testLogger.info("Testing unmonitored file modification.");
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
                fileOut = new FileOutputStream(unmonitored, true);
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
            Thread.sleep(500);
            assertEquals(1, observers.size());
            assertEquals(1, selfPopulatingCache.getKeys().size());
        } else {
            testLogger.info("Cannot write the file.  Will not test modification of unmonitored files.");
        }
        testLogger.trace("Deleting unmonitored file.");
        unmonitored.delete();
        Thread.sleep(500);
        testLogger.info("Checking for undesired cache removal after unmonitored file deletion.");
        assertEquals(1, selfPopulatingCache.getKeys().size());
        testLogger.info("Checking for undesired observer removal after unmonitored file deletion.");
        assertEquals(1, observers.size());
    }
}
