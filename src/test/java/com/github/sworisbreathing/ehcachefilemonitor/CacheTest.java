/*
 * Copyright 2013 Steven Swor.
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
package com.github.sworisbreathing.ehcachefilemonitor;

import com.github.sworisbreathing.sfmf4j.api.FileMonitorService;
import com.github.sworisbreathing.sfmf4j.api.FileMonitorServiceFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.Configuration;
import net.sf.ehcache.config.PersistenceConfiguration;
import net.sf.ehcache.constructs.blocking.SelfPopulatingCache;
import org.junit.After;
import org.junit.AfterClass;
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration tests.
 *
 * @author sswor
 */
public class CacheTest {

    private static final Logger testLogger = LoggerFactory.getLogger(CacheTest.class);
    private static FileMonitorServiceFactory factory = null;
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();
    private CacheManager cacheManager;
    private Ehcache cache = null;
    private SelfPopulatingCache selfPopulatingCache = null;
    private FileMonitorService fileAlterationMonitor = null;
    private FileMonitoringCacheEventListener instance = null;
    private BlockingQueue<File> callbackFiles = null;

    @BeforeClass
    public static void setUpClass() {
        ServiceLoader<FileMonitorServiceFactory> serviceLoader = ServiceLoader.load(FileMonitorServiceFactory.class);
        Iterator<FileMonitorServiceFactory> factoryIterator = serviceLoader.iterator();
        if (factoryIterator.hasNext()) {
            factory = factoryIterator.next();
        }
    }

    @AfterClass
    public static void tearDownClass() {
        factory = null;
    }

    @Before
    public void setUp() throws Exception {
        callbackFiles = new LinkedBlockingQueue<File>();
        Configuration configuration = new Configuration();
        CacheConfiguration cacheConfiguration = new CacheConfiguration(CacheTest.class.getName(), 1000).eternal(false).persistence(new PersistenceConfiguration().strategy(PersistenceConfiguration.Strategy.NONE));
        configuration.addCache(cacheConfiguration);
        cacheManager = CacheManager.create(configuration);
        cache = cacheManager.getEhcache(CacheTest.class.getName());
        selfPopulatingCache = new SelfPopulatingCache(cache, new FileLoadingCacheEntryFactory() {
            @Override
            protected Object loadObjectFromFile(File file) throws Exception {
                return file;
            }
        });
        cacheManager.replaceCacheWithDecoratedCache(cache, selfPopulatingCache);
        fileAlterationMonitor = factory.createFileMonitorService();
        fileAlterationMonitor.initialize();
        instance = new FileMonitoringCacheEventListener(selfPopulatingCache, fileAlterationMonitor);
        instance.addMonitoredFileListener(new MonitoredFileListener() {
            @Override
            public void startedMonitoringFile(File file) {
                callbackFiles.add(file);
            }

            @Override
            public void stoppedMonitoringFile(File file) {
                callbackFiles.add(file);
            }
        });
        selfPopulatingCache.getCacheEventNotificationService().registerListener(instance);
    }

    @After
    public void tearDown() {
        selfPopulatingCache.getCacheEventNotificationService().unregisterListener(instance);
        instance.dispose();
        instance = null;
        fileAlterationMonitor.shutdown();
        fileAlterationMonitor = null;
        selfPopulatingCache = null;
        cache = null;
        if (cacheManager != null) {
            cacheManager.shutdown();
            cacheManager = null;
        }
    }

    @Test(timeout = 10000)
    public void testLazyLoading() throws Exception {
        testLogger.info("Testing lazy-loading file monitor through ehcache.");
        assertEquals(0, selfPopulatingCache.getKeys().size());
        final File folder = tempFolder.getRoot();
        assertFalse(fileAlterationMonitor.isMonitoringDirectory(folder));
        File created = verifyFileCreateBehaviour(folder, 1);
        verifyFileModifyBehaviour(created, folder, 0);
        verifyFileDeleteBehaviour(created, folder, 0);
    }

    @Test(timeout = 10000)
    public void testLazyLoadingWithMultipleFiles() throws Exception {
        testLogger.info("Testing lazy-loading file monitor through ehcache with multiple files.");
        assertEquals(0, selfPopulatingCache.getKeys().size());
        final File folder = tempFolder.getRoot();
        assertFalse(fileAlterationMonitor.isMonitoringDirectory(folder));
        List<File> files = new LinkedList<File>();
        File last = null;
        for (int i=0;i<10;i++) {
            last = verifyFileCreateBehaviour(folder, 1+i);
            files.add(last);
        }
        for (File file : files) {
            verifyFileModifyBehaviour(file, folder, files.size()-1);
        }
        int count = files.size();
        for (File file : files) {
            verifyFileDeleteBehaviour(file, folder, --count);
        }
    }

    private File verifyFileCreateBehaviour(final File folder, final int count) throws Exception {
        final File created = tempFolder.newFile();
        testLogger.debug("File: {}", created);
        /*
         * Lazy-load the file.
         */
        selfPopulatingCache.get(created);
        File added = callbackFiles.take();
        assertEquals(created.getAbsolutePath(), added.getAbsolutePath());
        assertEquals(count, selfPopulatingCache.getKeys().size());
        assertTrue(fileAlterationMonitor.isMonitoringDirectory(folder));
        return created;
    }

    private void verifyFileModifyBehaviour(final File file, final File folder, final int filesStillBeingMonitored) throws Exception {
        if (file.canWrite()) {
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
                fileOut = new FileOutputStream(file, true);
                fileOut.write(bytes);
                testLogger.trace("data written");
            } finally {
                if (fileOut != null) {
                    testLogger.trace("Closing file stream.");
                    try {
                        fileOut.close();
                    } catch (Exception ex) {
                        //trap
                    }
                }
            }
            File removed = callbackFiles.take();
            assertEquals(file.getAbsolutePath(), removed.getAbsolutePath());
            assertEquals(filesStillBeingMonitored, selfPopulatingCache.getKeys().size());
            assertEquals(filesStillBeingMonitored > 0, fileAlterationMonitor.isMonitoringDirectory(folder));
            testLogger.info("Automatically removed on modification.  Ensuring it is cached again prior to deleting.");
            selfPopulatingCache.get(file);
            File added = callbackFiles.take();
            assertEquals(file.getAbsolutePath(), added.getAbsolutePath());
            assertEquals(1+filesStillBeingMonitored, selfPopulatingCache.getKeys().size());
            assertTrue(fileAlterationMonitor.isMonitoringDirectory(folder));
        } else {
            testLogger.info("Cannot write the file.  Will not test automatic removal.");
        }
    }

    private void verifyFileDeleteBehaviour(final File file, final File folder, final int filesStillBeingMonitored) throws Exception {
        testLogger.trace("Deleting");
        file.delete();
        testLogger.info("Checking for cache removal after deletion.");
        File removed = callbackFiles.take();
        assertEquals(file.getAbsolutePath(), removed.getAbsolutePath());
        assertEquals(filesStillBeingMonitored, selfPopulatingCache.getKeys().size());
        assertEquals(filesStillBeingMonitored>0, fileAlterationMonitor.isMonitoringDirectory(folder));
    }
}
