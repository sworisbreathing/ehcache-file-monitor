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
import static org.junit.Assert.fail;
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
public class SlowLoadingCacheIT {

    private static final Logger testLogger = LoggerFactory.getLogger(SlowLoadingCacheIT.class);

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
        CacheConfiguration cacheConfiguration = new CacheConfiguration(SlowLoadingCacheIT.class.getName(), 1000).eternal(false).persistence(new PersistenceConfiguration().strategy(PersistenceConfiguration.Strategy.NONE));
        configuration.addCache(cacheConfiguration);
        cacheManager = CacheManager.create(configuration);
        cache = cacheManager.getEhcache(SlowLoadingCacheIT.class.getName());
        observers = new LinkedList<FileAlterationObserver>();
        selfPopulatingCache = new SelfPopulatingCache(cache, new FileLoadingCacheEntryFactory() {

            private volatile boolean shouldFail = true;

            @Override
            protected Object loadObjectFromFile(File file) throws Exception {
                if (shouldFail) {
                    shouldFail = false;
                    throw new Exception("Loading failed due to partial file.");
                }
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
    public void testLazyLoadingSlowLoadingCache() throws Exception {
        testLogger.info("Testing lazy-loading file monitor through ehcache, when it takes a while for the cache to load.");
        assertEquals(0, selfPopulatingCache.getKeys().size());
        assertEquals(0, observers.size());
        File created = tempFolder.newFile();
        testLogger.debug("File: {}", created);
        /*
         * Lazy-load the file.
         */
        try {
            selfPopulatingCache.get(created);
            fail("Should have thrown an exception");
        }catch(Exception ex) {
            //expected behaviour.
            testLogger.debug("exception caught (expected behaviour)");
        }
        assertEquals(0, observers.size());
        testLogger.debug("Getting file from cache again (should succeed)");
        selfPopulatingCache.get(created);
        assertEquals(1, selfPopulatingCache.getKeys().size());
        assertEquals(1, observers.size());
        Thread.sleep(500);
        testLogger.trace("Deleting");
        created.delete();
        Thread.sleep(500);
        testLogger.info("Checking for cache removal after deletion.");
        assertEquals(0, selfPopulatingCache.getKeys().size());
        testLogger.info("Checking for observer removal after deletion.");
        assertEquals(0, observers.size());
    }
}
