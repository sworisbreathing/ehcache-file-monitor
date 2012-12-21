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
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.junit.After;
import org.junit.AfterClass;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.mockito.Matchers.any;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import static org.powermock.api.mockito.PowerMockito.*;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.rule.PowerMockRule;

/**
 *
 * @author sswor
 */
@PrepareForTest(FileAlterationMonitor.class)
public class FileMonitoringCacheEventListenerTest {

    @Rule
    public PowerMockRule powerMockRule = new PowerMockRule();

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static CacheManager cacheManager;

    private static Ehcache cache = null;

    private FileAlterationMonitor fileAlterationMonitor = null;

    private FileMonitoringCacheEventListener instance = null;

    private List<FileAlterationObserver> observers;

    @BeforeClass
    public static void setUpClass() {
        Configuration configuration = new Configuration();
        CacheConfiguration cacheConfiguration = new CacheConfiguration(FileMonitoringCacheEventListenerTest.class.getName(), 1000).persistence(new PersistenceConfiguration().strategy(PersistenceConfiguration.Strategy.NONE));
        configuration.addCache(cacheConfiguration);
        cacheManager = CacheManager.create(configuration);
        cache = cacheManager.getEhcache(FileMonitoringCacheEventListenerTest.class.getName());
    }

    @AfterClass
    public static void tearDownClass() {
        cache = null;
        if (cacheManager != null) {
            cacheManager.shutdown();
            cacheManager = null;
        }
    }

    @Before
    public void setUp() throws Exception {
        observers = new LinkedList<FileAlterationObserver>();
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
        observers = null;

    }

    /**
     * Tests {@link FileMonitoringCacheEventListener#getDirectory(File)},
     * ensuring it returns the file that was passed in, when that file is a
     * directory.
     */
    @Test
    public void testGetDirectoryForDirectory() {
        assertSame(tempFolder.getRoot(), instance.getDirectory(tempFolder.getRoot()));
    }

    /**
     * Tests {@link FileMonitoringCacheEventListener#getDirectory(File)},
     * ensuring it returns the parent folder when it is passed a file instead of
     * a directory.
     * @throws Exception if a new temporary file cannot be created
     */
    @Test
    public void testGetDirectoryForFile() throws Exception {
        assertEquals(tempFolder.getRoot(), instance.getDirectory(tempFolder.newFile()));
    }

    /**
     * Tests {@link FileMonitoringCacheEventListener#clone()}.
     * @throws Exception
     */
    @Test(expected=CloneNotSupportedException.class)
    public void testClone() throws Exception {
        instance.clone();
    }
}
