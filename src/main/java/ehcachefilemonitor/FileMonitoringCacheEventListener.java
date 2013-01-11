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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.sf.ehcache.CacheException;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import net.sf.ehcache.event.CacheEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sfmf4j.api.DirectoryListener;
import sfmf4j.api.DirectoryListenerAdapter;
import sfmf4j.api.FileMonitorService;

/**
 * A cache listener which monitors files for changes as long as they are in the
 * cache. Once a file is altered or deleted, it will be removed from the cache.
 * The file is only monitored as long as it is in the cache. This implementation
 * is bound to a single cache instance.
 *
 * @author sswor
 */
public class FileMonitoringCacheEventListener implements CacheEventListener {

    /**
     * The logger.
     */
    private static final Logger logger = LoggerFactory.getLogger(FileMonitoringCacheEventListener.class);
    /**
     * The cache instance.
     */
    private final Ehcache cache;
    /**
     * The file monitor service.
     */
    private final FileMonitorService fileAlterationMonitor;

    /**
     * Disposal flag, used to prevent new file observers from being added when
     * the cache is shutting down. This is initially set to {@code false} and is
     * set to {@code true} during {@link #dispose()}, after which time
     * {@link #startMonitoringFile(Object)} will no longer register new file
     * observers.
     */
    private volatile boolean disposed;

    private final List<MonitoredFileListener> monitoredFileListeners;

    private final ConcurrentMap<File, Collection<File>> monitoredFilesByFolder;
    private final ConcurrentMap<File, DirectoryListener> directoryListenersByFolder;

    /**
     * Creates a new FileMonitoringCacheEventListener.
     *
     * @param cache the cache to manage
     * @param fileAlterationMonitor service for monitoring file changes
     */
    public FileMonitoringCacheEventListener(final Ehcache cache, final FileMonitorService fileAlterationMonitor) {
        this.cache = cache;
        this.fileAlterationMonitor = fileAlterationMonitor;
        this.disposed = false;
        monitoredFileListeners = new CopyOnWriteArrayList<MonitoredFileListener>();
        monitoredFilesByFolder = new ConcurrentHashMap<File, Collection<File>>();
        directoryListenersByFolder = new ConcurrentHashMap<File, DirectoryListener>();
    }

    public void addMonitoredFileListener(final MonitoredFileListener listener) {
        monitoredFileListeners.add(listener);
    }

    public void removeMonitoredFileListener(final MonitoredFileListener listener) {
        monitoredFileListeners.remove(listener);
    }

    protected void notifyStartMonitoring(final File file) {
        for (MonitoredFileListener listener : monitoredFileListeners) {
            listener.startedMonitoringFile(file);
        }
    }

    protected void notifyStopMonitoring(final File file) {
        for (MonitoredFileListener listener : monitoredFileListeners) {
            listener.stoppedMonitoringFile(file);
        }
    }

    /**
     * Stops monitoring changes for files removed from the cache.
     *
     * @param ehcache {@inheritDoc}
     * @param element {@inheritDoc}
     * @throws CacheException {@inheritDoc}
     */
    @Override
    public void notifyElementRemoved(final Ehcache ehcache, final Element element) throws CacheException {
        logger.trace("notifyElementRemoved({}, {})", ehcache, element);
        stopMonitoringFile(element.getObjectKey());
    }

    /**
     * Starts monitoring changes for files added to the cache.
     *
     * @param ehcache {@inheritDoc}
     * @param element {@inheritDoc}
     * @throws CacheException {@inheritDoc}
     */
    @Override
    public void notifyElementPut(Ehcache ehcache, Element element) throws CacheException {
        logger.trace("notifyElementPut({}, {})", ehcache, element);
        startMonitoringFile(element.getObjectKey());
    }

    /**
     * No-op.
     *
     * @param cache {@inheritDoc}
     * @param element {@inheritDoc}
     * @throws CacheException {@inheritDoc}
     */
    @Override
    public void notifyElementUpdated(Ehcache cache, Element element) throws CacheException {
    }

    /**
     * Stops monitoring changes for files which have expired from the cache, as
     * they will no longer be retrievable.
     *
     * @param ehcache {@inheritDoc}
     * @param element {@inheritDoc}
     */
    @Override
    public void notifyElementExpired(Ehcache ehcache, Element element) {
        logger.trace("notifyElementExpired({}, {})", ehcache, element);
        stopMonitoringFile(element.getObjectKey());
    }

    /**
     * Stops monitoring changes for files which have been evicted from the
     * cache.
     *
     * @param ehcache {@inheritDoc}
     * @param element {@inheritDoc}
     */
    @Override
    public void notifyElementEvicted(Ehcache ehcache, Element element) {
        logger.trace("notifyElementEvicted({}, {})", ehcache, element);
        stopMonitoringFile(element.getObjectKey());
    }

    /**
     * Stops monitoring changes for all files when the cache is emptied.
     *
     * @param ehcache {@inheritDoc}
     */
    @Override
    public void notifyRemoveAll(Ehcache ehcache) {
        logger.trace("notifyRemoveAll({})", ehcache);
        stopMonitoringAll();
    }

    /**
     * Prevents additional file monitoring and stops all current file
     * monitoring.
     */
    @Override
    public synchronized void dispose() {
        logger.trace("dispose()");
        disposed = true;
        stopMonitoringAll();
    }

    /**
     * Stops all file monitoring.
     */
    private synchronized void stopMonitoringAll() {
        Collection<File> folders = new LinkedList<File>(monitoredFilesByFolder.keySet());
        for (File folder : folders) {
            Collection<File> files = new LinkedList<File>(monitoredFilesByFolder.remove(folder));
            if (files != null) {
                for (File file : files) {
                    stopMonitoringFile(file);
                }
            }
        }
    }

    /**
     * Starts monitoring for a file. This method is a no-op for keys which are
     * not instances of {@link File}, and it will not monitor the file if {@link
     * #dispose()} has been called
     *
     * @param key the file
     */
    void startMonitoringFile(final Object key) {
        if (!disposed && key instanceof File) {
            final File file = (File)key;
            final File folder = getDirectory(file);
            final String parentFolderPath = folder.getAbsolutePath();
            synchronized(this){
                Collection<File> monitoredFiles = monitoredFilesByFolder.get(folder);
                if (monitoredFiles==null) {
                    monitoredFiles = Collections.newSetFromMap(new ConcurrentHashMap<File, Boolean>());
                    monitoredFilesByFolder.put(folder, monitoredFiles);
                }
                monitoredFiles.add(file);
                /*
                 * Register a directory listener with the file monitor service.
                 */
                DirectoryListener listener = directoryListenersByFolder.get(folder);
                if (listener == null) {
                    listener = new DirectoryListenerAdapter(){

                        @Override
                        public void fileChanged(File changed) {
                            logger.trace("fileChanged({})", changed);
                            cache.remove(changed);
                        }

                        @Override
                        public void fileDeleted(File deleted) {
                            logger.trace("fileDeleted({})", deleted);
                            cache.remove(deleted);
                        }
                    };
                    directoryListenersByFolder.put(folder, listener);
                    fileAlterationMonitor.registerDirectoryListener(folder, listener);
                }
            }
            logger.debug("Started monitoring file: {}", file);
            notifyStartMonitoring(file);
        }
    }

    /**
     * Stops monitoring a file. This method is a no-op if the key is not an
     * instance of {@link File}.
     *
     * @param key the file to monitor
     */
    private void stopMonitoringFile(final Object key) {
        if (key instanceof File) {
            final File file = (File) key;
            final File parentFolder = getDirectory(file);
            synchronized(this) {
                Collection<File> monitoredFiles = monitoredFilesByFolder.get(parentFolder);
                if (monitoredFiles!=null) {
                    monitoredFiles.remove(file);
                    if (monitoredFiles.isEmpty()) {
                        logger.debug("No more files to monitor in folder {}", parentFolder);
                        monitoredFilesByFolder.remove(parentFolder);
                        DirectoryListener listener = directoryListenersByFolder.remove(parentFolder);
                        if (listener != null) {
                            fileAlterationMonitor.unregisterDirectoryListener(parentFolder, listener);
                        }
                    }
                }
            }
            logger.debug("Stopped monitoring file: {}", file);
            notifyStopMonitoring(file);
        }
    }

    /**
     * Throws {@link CloneNotSupportedException}, since this instance does not
     * support cloning.
     *
     * @return nothing, since an exception will be thrown.
     * @throws CloneNotSupportedException always
     */
    @Override
    public Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    /**
     * Gets the file as a directory, or its parent directory if the file is not
     * a directory.
     *
     * @param file the file
     * @return the file (if it is a directory) or its parent directory
     */
    protected File getDirectory(final File file) {
        if (file.isDirectory()) {
            return file;
        } else {
            return file.getParentFile();
        }
    }
}