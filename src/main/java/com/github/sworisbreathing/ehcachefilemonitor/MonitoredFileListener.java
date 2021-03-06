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

/**
 * Listener interface for file system monitoring events.  This is intended for
 * thread synchronization during automated tests.
 * @author Steven Swor
 */
interface MonitoredFileListener {
    /**
     * Callback invoked when we stop monitoring a file for changes.
     * @param file the file
     */
    void startedMonitoringFile(final File file);

    /**
     * Callback invoked when we stop monitoring a file for changes.
     * @param file the file
     */
    void stoppedMonitoringFile(final File file);
}
