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
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
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
public class NotAFileIT {

    private static final Logger testLogger = LoggerFactory.getLogger(NotAFileIT.class);

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private CacheManager cacheManager;

    private Ehcache cache = null;

    private SelfPopulatingCache selfPopulatingCache = null;

    private FileAlterationMonitor fileAlterationMonitor = null;

    private FileMonitoringCacheEventListener instance = null;

    @Before
    public void setUp() throws Exception {
        Configuration configuration = new Configuration();
        CacheConfiguration cacheConfiguration = new CacheConfiguration(NotAFileIT.class.getName(), 1000).eternal(false).persistence(new PersistenceConfiguration().strategy(PersistenceConfiguration.Strategy.NONE));
        configuration.addCache(cacheConfiguration);
        cacheManager = CacheManager.create(configuration);
        cache = cacheManager.getEhcache(NotAFileIT.class.getName());
        selfPopulatingCache = new SelfPopulatingCache(cache, new FileLoadingCacheEntryFactory() {

            @Override
            protected Object loadObjectFromFile(File file) throws Exception {
                return file;
            }
        });
        cacheManager.replaceCacheWithDecoratedCache(cache, selfPopulatingCache);
        fileAlterationMonitor = new FileAlterationMonitor(250);
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
    }

    @Test
    public void testNotAFile() {
        Element notAFile = new Element("test", "test");
        selfPopulatingCache.put(notAFile);
        assertEquals(1, selfPopulatingCache.getKeys().size());
        selfPopulatingCache.remove("test");
        assertEquals(0, selfPopulatingCache.getKeys().size());
    }
}
