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
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
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
 * @author Steven Swor
 */
public class CacheTest {

    /**
     * The test logger.
     */
    private static final Logger testLogger = LoggerFactory.getLogger(CacheTest.class);

    /**
     * The file monitor service factory.
     */
    private static FileMonitorServiceFactory factory = null;

    /**
     * A temporary folder for placing files during test execution.
     */
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    /**
     * The cache manager.
     */
    private CacheManager cacheManager;

    /**
     * The cache we are testing.
     */
    private Ehcache cache = null;

    /**
     * The decorated cache.
     */
    private SelfPopulatingCache selfPopulatingCache = null;

    /**
     * The file monitor service instance.
     */
    private FileMonitorService fileMonitorService = null;

    /**
     * The listener which will eagerly evict items from the cache when the files
     * from which they were loaded have changed.
     */
    private FileMonitoringCacheEventListener instance = null;

    /**
     * Listener used to take action when we start and stop monitoring files.
     */
    private MonitoredFileListener monitoredFileListener = null;

    /**
     * A place to put files when we detect changes to them.
     */
    private BlockingQueue<File> callbackFiles = null;

    /**
     * Loads the SFMF4J implementation using SPI.
     */
    @BeforeClass
    public static void setUpClass() {
        ServiceLoader<FileMonitorServiceFactory> serviceLoader = ServiceLoader.load(FileMonitorServiceFactory.class);
        Iterator<FileMonitorServiceFactory> factoryIterator = serviceLoader.iterator();
        if (factoryIterator.hasNext()) {
            factory = factoryIterator.next();
        } else {
            throw new IllegalStateException("No SPI-compliant SFMF4J implementation found.  File system monitoring will not work.");
        }
    }

    /**
     * Unloads the SFMF4J implementation.
     */
    @AfterClass
    public static void tearDownClass() {
        factory = null;
    }

    /**
     * Sets up the test environment to lazy-load resources from files and:
     * <ul>
     *   <li>
     *       Decorates the cache used for testing with the {@link
     *       SelfPopulatingCache}
     *   </li>
     *   <li>Starts up the {@link FileMonitorService}</li>
     *   <li>
     *      Creates the {@link FileMonitoringCacheEventListener} instance and
     *      registers it with the decorated cache.
     *   </li>
     * </ul>
     * @throws Exception if any of these steps fails
     */
    @Before
    public void setUp() throws Exception {
        callbackFiles = new LinkedBlockingQueue<File>();

        /*
         * Set up a self-populating cache for testing.
         */
        Configuration configuration = new Configuration();
        configuration.setUpdateCheck(false);
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

        /*
         * Set up the file monitor service.
         */
        fileMonitorService = factory.createFileMonitorService();
        fileMonitorService.initialize();

        /*
         * Add a listener to eagerly evict items from the cache when the files
         * have changed, and add a listener to that listener, so we can verify
         * when we've started and stopped monitoring a file.
         */
        instance = new FileMonitoringCacheEventListener(selfPopulatingCache, fileMonitorService);
        monitoredFileListener = new MonitoredFileListener() {
            @Override
            public void startedMonitoringFile(File file) {
                callbackFiles.add(file);
            }

            @Override
            public void stoppedMonitoringFile(File file) {
                callbackFiles.add(file);
            }
        };
        instance.addMonitoredFileListener(monitoredFileListener);
        selfPopulatingCache.getCacheEventNotificationService().registerListener(instance);
    }

    /**
     * Cleans up the test.
     * <ul>
     *   <li>Unregisters the cache listener and disposes of it</li>
     *   <li>Shuts down the file monitor service</li>
     *   <li>Shuts down Ehcache</li>
     * </ul>
     */
    @After
    public void tearDown() {
        selfPopulatingCache.getCacheEventNotificationService().unregisterListener(instance);
        instance.removeMonitoredFileListener(monitoredFileListener);
        monitoredFileListener = null;
        instance.dispose();
        instance = null;
        fileMonitorService.shutdown();
        fileMonitorService = null;
        selfPopulatingCache = null;
        cache = null;
        if (cacheManager != null) {
            cacheManager.shutdown();
            cacheManager = null;
        }
    }

    /**
     * Tests the cache's lazy-loading behavior with a single file.
     * @throws Exception if the test fails
     */
    @Test(timeout = 10000)
    public void testLazyLoading() throws Exception {
        testLogger.info("Testing lazy-loading file monitor through ehcache.");
        assertEquals(0, selfPopulatingCache.getKeys().size());
        final File folder = tempFolder.getRoot();
        assertFalse(fileMonitorService.isMonitoringDirectory(folder));
        File created = verifyFileCreateBehaviour(1);
        verifyFileModifyBehaviour(created, 0);
        verifyFileDeleteBehaviour(created, 0);
    }

    /**
     * Tests the cache's lazy-loading behavior with multiple files.
     * @throws Exception if the test fails
     */
    @Test(timeout = 10000)
    public void testLazyLoadingWithMultipleFiles() throws Exception {
        testLogger.info("Testing lazy-loading file monitor through ehcache with multiple files.");
        assertEquals(0, selfPopulatingCache.getKeys().size());
        final File folder = tempFolder.getRoot();
        assertFalse(fileMonitorService.isMonitoringDirectory(folder));
        List<File> files = new LinkedList<File>();
        File last = null;
        for (int i=0;i<10;i++) {
            last = verifyFileCreateBehaviour(1+i);
            files.add(last);
        }
        for (File file : files) {
            verifyFileModifyBehaviour(file, files.size()-1);
        }
        int count = files.size();
        for (File file : files) {
            verifyFileDeleteBehaviour(file, --count);
        }
    }

    /**
     * Ensures that we stop monitoring files when {@link Ehcache#removeAll()} is
     * called.
     */
    @Test(timeout = 10000)
    public void testRemoveAll() throws IOException, InterruptedException {
        /*
         * Let's make some files, load them into the cache, and start monitoring
         * them.
         */
        final int NUM_FILES = 10;
        Collection<File> files = new HashSet<File>(NUM_FILES);
        for (int i=0;i<NUM_FILES;i++) {
            File file = tempFolder.newFile();
            selfPopulatingCache.get(file);
            files.add(callbackFiles.take());
        }
        assertTrue(fileMonitorService.isMonitoringDirectory(tempFolder.getRoot()));

        /*
         * Empty the cache and verify that we stopped monitoring all the files.
         */
        selfPopulatingCache.removeAll();
        Collection<File> notMonitoringFiles = new HashSet<File>(NUM_FILES);
        for (int i=0; i<NUM_FILES; i++) {
            notMonitoringFiles.add(callbackFiles.take());
        }
        assertEquals(files, notMonitoringFiles);
        assertFalse(fileMonitorService.isMonitoringDirectory(tempFolder.getRoot()));
    }

    /**
     * Creates a new file and verifies that it will be loaded from the cache and
     * monitored.
     * @param count the expected number of entries in the cache after loading
     * the file
     * @return the new file
     * @throws Exception if the test fails
     */
    private File verifyFileCreateBehaviour(final int count) throws Exception {
        final File created = tempFolder.newFile();
        testLogger.debug("File: {}", created);
        /*
         * Lazy-load the file.
         */
        selfPopulatingCache.get(created);
        File added = callbackFiles.take();
        assertEquals(created.getAbsolutePath(), added.getAbsolutePath());
        assertEquals(count, selfPopulatingCache.getKeys().size());
        assertTrue(fileMonitorService.isMonitoringDirectory(tempFolder.getRoot()));
        return created;
    }

    /**
     * Verifies that an entry will be eagerly removed from the cache when its
     * backing file is changed.
     * @param file the file to modify
     * @param filesStillBeingMonitored the expected number of items in the cache
     * after removing the file's entry
     * @throws Exception if the test fails
     */
    private void verifyFileModifyBehaviour(final File file, final int filesStillBeingMonitored) throws Exception {
        if (file.canWrite()) {

            /*
             * Write to the file.
             */
            testLogger.info("Testing automatic cache removal on file modification.");
            byte[] bytes = new byte[4096];
            FileOutputStream fileOut = null;
            try {
                testLogger.trace("Opening file stream for writing.");
                /*
                 * NOTE: We can get multiple modification events for the file
                 * here.
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

            /*
             * Verify that we stopped monitoring the file.
             */
            File removed = callbackFiles.take();
            assertEquals(file.getAbsolutePath(), removed.getAbsolutePath());
            assertEquals(filesStillBeingMonitored, selfPopulatingCache.getKeys().size());
            assertEquals(filesStillBeingMonitored > 0, fileMonitorService.isMonitoringDirectory(tempFolder.getRoot()));
            testLogger.info("Automatically removed on modification.  Ensuring it is cached again prior to deleting.");

            /*
             * Verify that the next call to Ehcache.get() puts the object back
             * in the cache and starts file monitoring again.
             */
            selfPopulatingCache.get(file);
            File added = callbackFiles.take();
            assertEquals(file.getAbsolutePath(), added.getAbsolutePath());
            assertEquals(1+filesStillBeingMonitored, selfPopulatingCache.getKeys().size());
            assertTrue(fileMonitorService.isMonitoringDirectory(tempFolder.getRoot()));
        } else {
            testLogger.info("Cannot write the file.  Will not test automatic removal.");
        }
    }

    /**
     * Verifies that an entry will be removed from the cache when its backing
     * file is deleted.
     * @param file the file to delete
     * @param filesStillBeingMonitored the expected number of entries in the
     * cache after deleting the file
     * @throws Exception if the test fails
     */
    private void verifyFileDeleteBehaviour(final File file, final int filesStillBeingMonitored) throws Exception {
        testLogger.trace("Deleting");
        file.delete();
        testLogger.info("Checking for cache removal after deletion.");
        File removed = callbackFiles.take();
        assertEquals(file.getAbsolutePath(), removed.getAbsolutePath());
        assertEquals(filesStillBeingMonitored, selfPopulatingCache.getKeys().size());
        assertEquals(filesStillBeingMonitored>0, fileMonitorService.isMonitoringDirectory(tempFolder.getRoot()));
    }
}
