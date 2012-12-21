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
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
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
public class MultipleMonitoredFilesIT {

    private static final Logger testLogger = LoggerFactory.getLogger(MultipleMonitoredFilesIT.class);

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

    private Semaphore semaphore = null;

    @Before
    public void setUp() throws Exception {
        Configuration configuration = new Configuration();
        CacheConfiguration cacheConfiguration = new CacheConfiguration(MultipleMonitoredFilesIT.class.getName(), 1000).eternal(false).persistence(new PersistenceConfiguration().strategy(PersistenceConfiguration.Strategy.NONE));
        configuration.addCache(cacheConfiguration);
        cacheManager = CacheManager.create(configuration);
        cache = cacheManager.getEhcache(MultipleMonitoredFilesIT.class.getName());
        observers = new LinkedList<FileAlterationObserver>();
        selfPopulatingCache = new SelfPopulatingCache(cache, new FileLoadingCacheEntryFactory() {

            @Override
            protected Object loadObjectFromFile(File file) throws Exception {
                return file;
            }
        });
        cacheManager.replaceCacheWithDecoratedCache(cache, selfPopulatingCache);
        semaphore = new Semaphore(0);
        fileAlterationMonitor = spy(new FileAlterationMonitor(250));
        fileAlterationMonitor.setThreadFactory(new ThreadFactory(){

            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "File Monitor");
            }
        });
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
        instance.addMonitoredFileListener(new MonitoredFileListener() {

            @Override
            public void startedMonitoringFile(File file) {
                testLogger.trace("startedMonitoringFile({})", file);
                semaphore.release();
            }

            @Override
            public void stoppedMonitoringFile(File file) {
                testLogger.trace("stoppedMonitoringFile({})",file);
                semaphore.release();
            }
        });
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

    /**
     * Tests the behaviour when multiple files are monitored and one of them is
     * modified or deleted.
     * @throws Exception
     */
    @Test
    public void testWithOtherMonitoredFileChangedOrDeleted() throws Exception {
        testLogger.info("Testing behaviour when we are monitoring multiple files and one is changed or deleted.");
        assertEquals(0, selfPopulatingCache.getKeys().size());
        assertEquals(0, observers.size());
        File monitored = tempFolder.newFile();
        testLogger.debug("File: {}", monitored);
        selfPopulatingCache.get(monitored);
        semaphore.acquire();
        assertEquals(1, selfPopulatingCache.getKeys().size());
        assertEquals(1, observers.size());
        File otherMonitored = new File(monitored.getParentFile(),  "other.tmp");
        testLogger.debug("Other File: {}", otherMonitored);
        byte[] bytes = new byte[4096];
        FileOutputStream fout = null;
        try {
            fout = new FileOutputStream(otherMonitored, false);
            fout.write(bytes);
        }finally {
            if (fout != null) {
                try {
                    fout.close();
                }catch(Exception ex) {
                    //trap
                }
            }
        }
        selfPopulatingCache.get(otherMonitored);
        semaphore.acquire();
        assertEquals(2, selfPopulatingCache.getKeys().size());
        assertEquals(1, observers.size()); // same folder is monitored.
        if (otherMonitored.canWrite()) {
            testLogger.info("Testing other monitored file modification.");
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
                fileOut = new FileOutputStream(otherMonitored, true);
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
            assertEquals(1, observers.size());
            assertEquals(1, selfPopulatingCache.getKeys().size());
            selfPopulatingCache.get(otherMonitored);
            semaphore.acquire();
            assertEquals(1, observers.size());
            assertEquals(2, selfPopulatingCache.getKeys().size());
        } else {
            testLogger.info("Cannot write the file.  Will not test modification of other monitored files.");
        }
        testLogger.trace("Deleting other monitored file.");
        otherMonitored.delete();
        testLogger.info("Checking for proper cache removal after other monitored file deletion.");
        semaphore.acquire();
        assertEquals(1, selfPopulatingCache.getKeys().size());
        testLogger.info("Checking for proper observer removal after other monitored file deletion.");
        assertEquals(1, observers.size());
    }
}
