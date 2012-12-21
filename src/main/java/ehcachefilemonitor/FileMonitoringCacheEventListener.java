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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.sf.ehcache.CacheException;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import net.sf.ehcache.event.CacheEventListener;
import org.apache.commons.io.monitor.FileAlterationListener;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final FileAlterationMonitor fileAlterationMonitor;
    /**
     * File alteration observers.
     */
    private final ConcurrentMap<File, FileAlterationObserver> folderObservers;
    /**
     * Disposal flag, used to prevent new file observers from being added when
     * the cache is shutting down. This is initially set to {@code false} and is
     * set to {@code true} during {@link #dispose()}, after which time
     * {@link #startMonitoringFile(Object)} will no longer register new file
     * observers.
     */
    private volatile boolean disposed;

    private final List<MonitoredFileListener> monitoredFileListeners;

    /**
     * Creates a new FileMonitoringCacheEventListener.
     *
     * @param cache the cache to manage
     * @param fileAlterationMonitor service for monitoring file changes
     */
    public FileMonitoringCacheEventListener(final Ehcache cache, final FileAlterationMonitor fileAlterationMonitor) {
        this.cache = cache;
        this.fileAlterationMonitor = fileAlterationMonitor;
        this.disposed = false;
        folderObservers = new ConcurrentHashMap<File, FileAlterationObserver>();
        monitoredFileListeners = new CopyOnWriteArrayList<MonitoredFileListener>();
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
    public void dispose() {
        logger.trace("dispose()");
        disposed = true;
        stopMonitoringAll();
    }

    /**
     * Stops all file monitoring.
     */
    private void stopMonitoringAll() {
        for (File file : folderObservers.keySet()) {
            stopMonitoringFile(file);
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
        if (key instanceof File) {
            final File file = (File) key;
            final File folder = getDirectory(file);
            final FileAlterationObserver observer;
            boolean shouldAdd = false;
            FileAlterationObserver newObserver = new FileAlterationObserver(folder, new FileFilterChain());
            if (!disposed) {
                FileAlterationObserver oldObserver = folderObservers.putIfAbsent(folder, newObserver);
                if (oldObserver == null) {
                    try {
                        newObserver.initialize();
                        shouldAdd = true;
                        /*
                         * Add a listener for the file.
                         */
                        final FileAlterationListener fileListener = new FileAlterationListenerAdaptor() {

                            @Override
                            public void onFileChange(final File f) {
                                logger.trace("onFileChange({})", f);
                                cache.remove(f);
                            }

                            @Override
                            public void onFileDelete(final File f) {
                                logger.trace("onFileDelete({})", f);
                                cache.remove(f);
                            }
                        };
                        newObserver.addListener(fileListener);
                    } catch (Exception ex) {
                        logger.warn("Failed to initialize observer for folder: {}", folder.getAbsolutePath());
                    }
                    observer = newObserver;
                } else {
                    /*
                     * We already have an observer.  Use it instead.
                     */
                    observer = oldObserver;
                    try {
                        /*
                         * Ensure the one we didn't add can be garbage
                         * collected.
                         */
                        newObserver.destroy();
                    } catch (Exception ex) {
                        //trap.
                    }
                }
                observer.addListener(new FileAlterationListenerAdaptor(){

                    @Override
                    public void onStart(FileAlterationObserver observer) {
                        logger.debug("Started monitoring file: {}", file);
                        notifyStartMonitoring(file);
                        observer.removeListener(this);
                    }
                });
                /*
                 * Ensure the file is monitored.
                 */
                synchronized(observer) {
                    ((FileFilterChain)observer.getFileFilter()).getFiles().add(file);
                    if (shouldAdd) {
                        fileAlterationMonitor.addObserver(observer);
                    }
                }
            }
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
            final FileAlterationObserver observer = folderObservers.get(parentFolder);
            if (observer != null) {
                synchronized(observer) {
                    FileFilterChain filterChain = (FileFilterChain) observer.getFileFilter();
                    filterChain.getFiles().remove(file);
                    if (filterChain.getFiles().isEmpty()) {
                        fileAlterationMonitor.removeObserver(observer);
                        folderObservers.remove(parentFolder);
                        try {
                            observer.destroy();
                        }catch(Exception ex) {
                            // trap.
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
