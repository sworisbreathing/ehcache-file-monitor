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
import net.sf.ehcache.constructs.blocking.CacheEntryFactory;

/**
 * A cache entry factory which loads object instances from a file.
 * @author sswor
 */
public abstract class FileLoadingCacheEntryFactory implements CacheEntryFactory {

    /**
     * Creates a new cache entry from a file.  When an instance of {@link File}
     * is passed to this method, it will return the result of {@link
     * #loadObjectFromFile(File)}.  Otherwise, it will return {@code null}.
     * @param key the key
     * @return the result of {@link #loadObjectFromFile(File)} if the argument
     * is a file, otherwise {@code null}
     * @throws Exception if the object cannot be loaded from a file
     */
    @Override
    public Object createEntry(Object key) throws Exception {
        if (key != null && key instanceof File) {
            return loadObjectFromFile((File)key);
        }else{
            return null;
        }
    }

    protected abstract Object loadObjectFromFile(final File file) throws Exception;

}