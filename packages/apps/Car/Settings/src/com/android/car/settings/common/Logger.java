/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.car.settings.common;

import android.util.Log;

/**
 * Helper class that wraps {@link Log} to log messages to logcat. The intended use for a Logger is
 * to include one per file, using the class.getSimpleName as the prefix, like this:
 * <pre> private static final Logger LOG = new Logger(MyClass.class); </pre>
 *
 * <p>
 * The logger will log statements in this format:
 * TAG: [PREFIX] MESSAGE
 *
 * <p>
 * When logging verbose and debug logs, the logs should either be guarded by {@code if (LOG.isV())},
 * or a constant if (DEBUG). That DEBUG constant should be false on any submitted code.
 */
public final class Logger {

    private static final String TAG = "CarSettings";
    private final String mPrefix;

    public Logger(Class<?> cls) {
        this(cls.getSimpleName());
    }

    public Logger(String prefix) {
        mPrefix = "[" + prefix + "] ";
    }

    /**
     * Returns true when it is desired to force log all messages.
     */
    protected boolean forceAllLogging() {
        return false;
    }

    /**
     * Logs a {@link Log#VERBOSE} log message. Will only be logged if {@link Log#VERBOSE} is
     * loggable. This is a wrapper around {@link Log#v(String, String)}.
     *
     * @param message The message you would like logged.
     */
    public void v(String message) {
        if (isV()) {
            Log.v(TAG, mPrefix.concat(message));
        }
    }

    /**
     * Logs a {@link Log#VERBOSE} log message. Will only be logged if {@link Log#VERBOSE} is
     * loggable. This is a wrapper around {@link Log#v(String, String, Throwable)}.
     *
     * @param message   The message you would like logged.
     * @param throwable An exception to log
     */
    public void v(String message, Throwable throwable) {
        if (isV()) {
            Log.v(TAG, mPrefix.concat(message), throwable);
        }
    }

    /**
     * Logs a {@link Log#DEBUG} log message. Will only be logged if {@link Log#DEBUG} is
     * loggable. This is a wrapper around {@link Log#d(String, String)}.
     *
     * @param message The message you would like logged.
     */
    public void d(String message) {
        if (isD()) {
            Log.d(TAG, mPrefix.concat(message));
        }
    }

    /**
     * Logs a {@link Log#DEBUG} log message. Will only be logged if {@link Log#DEBUG} is
     * loggable. This is a wrapper around {@link Log#d(String, String, Throwable)}.
     *
     * @param message   The message you would like logged.
     * @param throwable An exception to log
     */
    public void d(String message, Throwable throwable) {
        if (isD()) {
            Log.d(TAG, mPrefix.concat(message), throwable);
        }
    }

    /**
     * Logs a {@link Log#INFO} log message. Will only be logged if {@link Log#INFO} is loggable.
     * This is a wrapper around {@link Log#i(String, String)}.
     *
     * @param message The message you would like logged.
     */
    public void i(String message) {
        if (isI()) {
            Log.i(TAG, mPrefix.concat(message));
        }
    }

    /**
     * Logs a {@link Log#INFO} log message. Will only be logged if {@link Log#INFO} is loggable.
     * This is a wrapper around {@link Log#i(String, String, Throwable)}.
     *
     * @param message   The message you would like logged.
     * @param throwable An exception to log
     */
    public void i(String message, Throwable throwable) {
        if (isI()) {
            Log.i(TAG, mPrefix.concat(message), throwable);
        }
    }

    /**
     * Logs a {@link Log#WARN} log message. This is a wrapper around {@link Log#w(String, String)}.
     *
     * @param message The message you would like logged.
     */
    public void w(String message) {
        Log.w(TAG, mPrefix.concat(message));
    }

    /**
     * Logs a {@link Log#WARN} log message. This is a wrapper around
     * {@link Log#w(String, String, Throwable)}.
     *
     * @param message   The message you would like logged.
     * @param throwable An exception to log
     */
    public void w(String message, Throwable throwable) {
        Log.w(TAG, mPrefix.concat(message), throwable);
    }

    /**
     * Logs a {@link Log#ERROR} log message. This is a wrapper around {@link Log#e(String, String)}.
     *
     * @param message The message you would like logged.
     */
    public void e(String message) {
        Log.e(TAG, mPrefix.concat(message));
    }

    /**
     * Logs a {@link Log#ERROR} log message. This is a wrapper around
     * {@link Log#e(String, String, Throwable)}.
     *
     * @param message   The message you would like logged.
     * @param throwable An exception to log
     */
    public void e(String message, Throwable throwable) {
        Log.e(TAG, mPrefix.concat(message), throwable);
    }

    /**
     * Logs a "What a Terrible Failure" as an {@link Log#ASSERT} log message. This is a wrapper
     * around {@link Log#w(String, String)}.
     *
     * @param message The message you would like logged.
     */
    public void wtf(String message) {
        Log.wtf(TAG, mPrefix.concat(message));
    }

    /**
     * Logs a "What a Terrible Failure" as an {@link Log#ASSERT} log message. This is a wrapper
     * around {@link Log#wtf(String, String, Throwable)}.
     *
     * @param message   The message you would like logged.
     * @param throwable An exception to log
     */
    public void wtf(String message, Throwable throwable) {
        Log.wtf(TAG, mPrefix.concat(message), throwable);
    }

    private boolean isV() {
        return Log.isLoggable(TAG, Log.VERBOSE) || forceAllLogging();
    }

    private boolean isD() {
        return Log.isLoggable(TAG, Log.DEBUG) || forceAllLogging();
    }

    private boolean isI() {
        return Log.isLoggable(TAG, Log.INFO) || forceAllLogging();
    }

    /**
     * Returns the tag used when wrapping {@link Log} methods.
     */
    public String getTag() {
        return TAG;
    }

    @Override
    public String toString() {
        return "Logger[TAG=" + TAG + ", prefix=\"" + mPrefix + "\"]";
    }
}
