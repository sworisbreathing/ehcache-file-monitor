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

import java.io.File;
import java.io.IOException;
import static org.junit.Assert.assertEquals;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for {@link FileMonitoringCacheEventListener}.
 *
 * @author Steven Swor
 */
public class FileMonitoringCacheEventListenerTest {

    /**
     * Tests {@link FileMonitoringCacheEventListener#clone()}.
     * @throws CloneNotSupportedException if the test passes
     */
    @Test(expected = CloneNotSupportedException.class)
    public void testClone() throws CloneNotSupportedException {
        new FileMonitoringCacheEventListener(null, null).clone();
    }

    /**
     * A temporary folder for testing.
     */
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    /**
     * Tests {@link FileMonitoringCacheEventListener#getDirectory(File)} with a
     * file.
     * @throws IOException if the file cannot be created
     */
    @Test
    public void testGetDirectoryFile() throws IOException {
        File newFile = tempFolder.newFile();
        assertEquals(tempFolder.getRoot(), FileMonitoringCacheEventListener.getDirectory(newFile));
    }

    /**
     * Tests {@link FileMonitoringCacheEventListener#getDirectory(File)} with a
     * directory.
     */
    @Test
    public void testGetDirectoryFolder() {
        assertEquals(tempFolder.getRoot(), FileMonitoringCacheEventListener.getDirectory(tempFolder.getRoot()));
    }
}
