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
import net.sf.ehcache.constructs.blocking.CacheEntryFactory;

/**
 * A cache entry factory which loads object instances from a file.
 * @author Steven Swor
 */
public abstract class FileLoadingCacheEntryFactory implements CacheEntryFactory {

    /**
     * Obtains a new cache entry.  When an instance of {@link File}
     * is passed to this method, it will return the result of {@link
     * #loadObjectFromFile(File)}.  Otherwise, it will return {@code null}.
     * @param key the key
     * @return the result of {@link #loadObjectFromFile(File)} if the argument
     * is a {@link File}, otherwise {@code null}
     * @throws Exception if the argument was a {@link File} and an exception was
     * thrown by {@link #loadObjectFromFile(File)}
     */
    @Override
    public Object createEntry(Object key) throws Exception {
        if (key != null && key instanceof File) {
            return loadObjectFromFile((File)key);
        }else{
            return null;
        }
    }

    /**
     * Loads an object from a file.
     * @param file the file
     * @return an object loaded from a file
     * @throws Exception if an object cannot be loaded from the file
     */
    protected abstract Object loadObjectFromFile(final File file) throws Exception;
}
