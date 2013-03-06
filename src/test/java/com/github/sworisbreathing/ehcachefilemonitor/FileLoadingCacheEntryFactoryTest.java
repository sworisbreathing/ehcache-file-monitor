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
import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for {@link FileLoadingCacheEntryFactory}.
 *
 * @author Steven Swor
 */
public class FileLoadingCacheEntryFactoryTest {

    /**
     * Temporary folder for testing.
     */
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    /**
     * Tests {@link FileLoadingCacheEntryFactory#createEntry(Object)} when the
     * argument is {@code null}.
     * @throws Exception never
     */
    @Test
    public void testCreateEntryWithNull() throws Exception {
        assertNull(new FileLoadingCacheEntryFactoryImpl().createEntry(null));
    }

    /**
     * Tests {@link FileLoadingCacheEntryFactory#createEntry(Object)} when the
     * argument is not {@code null} but is not a {@link File}.
     * @throws Exception never
     */
    @Test
    public void testCreateEntryWithNotAFile() throws Exception {
        assertNull(new FileLoadingCacheEntryFactoryImpl().createEntry(this));
    }

    /**
     * Tests {@link FileLoadingCacheEntryFactory#createEntry(Object)} when the
     * argument is a {@link File} and is not {@code null}.
     * @throws Exception never
     */
    @Test
    public void testCreateEntryWithFile() throws Exception {
        File file = tempFolder.newFile();
        assertSame(file, new FileLoadingCacheEntryFactoryImpl().createEntry(file));
    }

    /**
     * Testing implementation of {@link FileLoadingCacheEntryFactory}, whose
     * {@link FileLoadingCacheEntryFactory#loadObjectFromFile(File)} simply
     * returns the argument which was passed in.
     */
    public class FileLoadingCacheEntryFactoryImpl extends FileLoadingCacheEntryFactory {

        @Override
        public Object loadObjectFromFile(File file) throws Exception {
            return file;
        }
    }
}
