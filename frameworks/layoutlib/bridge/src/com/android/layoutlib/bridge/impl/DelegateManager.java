/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.layoutlib.bridge.impl;

import com.android.layoutlib.bridge.util.Debug;
import com.android.layoutlib.bridge.util.SparseWeakArray;

import android.annotation.Nullable;
import android.util.SparseArray;

import java.io.PrintStream;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Manages native delegates.
 *
 * This is used in conjunction with layoublib_create: certain Android java classes are mere
 * wrappers around a heavily native based implementation, and we need a way to run these classes
 * in our Android Studio rendering framework without bringing all the native code from the Android
 * platform.
 *
 * Thus we instruct layoutlib_create to modify the bytecode of these classes to replace their
 * native methods by "delegate calls".
 *
 * For example, a native method android.graphics.Matrix.init(...) will actually become
 * a call to android.graphics.Matrix_Delegate.init(...).
 *
 * The Android java classes that use native code uses an int (Java side) to reference native
 * objects. This int is generally directly the pointer to the C structure counterpart.
 * Typically a creation method will return such an int, and then this int will be passed later
 * to a Java method to identify the C object to manipulate.
 *
 * Since we cannot use the Java object reference as the int directly, DelegateManager manages the
 * int -> Delegate class link.
 *
 * Native methods usually always have the int as parameters. The first thing the delegate method
 * will do is call {@link #getDelegate(long)} to get the Java object matching the int.
 *
 * Typical native init methods are returning a new int back to the Java class, so
 * {@link #addNewDelegate(Object)} does the same.
 *
 * The JNI references are counted, so we do the same through a {@link WeakReference}. Because
 * the Java object needs to count as a reference (even though it only holds an int), we use the
 * following mechanism:
 *
 * - {@link #addNewDelegate(Object)} and {@link #removeJavaReferenceFor(long)} adds and removes
 *   the delegate to/from a set. This set holds the reference and prevents the GC from reclaiming
 *   the delegate.
 *
 * - {@link #addNewDelegate(Object)} also adds the delegate to a {@link SparseArray} that holds a
 *   {@link WeakReference} to the delegate. This allows the delegate to be deleted automatically
 *   when nothing references it. This means that any class that holds a delegate (except for the
 *   Java main class) must not use the int but the Delegate class instead. The integers must
 *   only be used in the API between the main Java class and the Delegate.
 *
 * @param <T> the delegate class to manage
 */
public final class DelegateManager<T> {
    private static final SparseWeakArray<Object> sDelegates = new SparseWeakArray<>();
    /** Set used to store delegates when their main object holds a reference to them.
     * This is to ensure that the WeakReference in the SparseWeakArray doesn't get GC'ed
     * @see #addNewDelegate(Object)
     * @see #removeJavaReferenceFor(long)
     */
    private static final Set<Object> sJavaReferences = new HashSet<>();
    private static final AtomicLong sDelegateCounter = new AtomicLong(1);

    private final Class<T> mClass;

    public DelegateManager(Class<T> theClass) {
        mClass = theClass;
    }

    public synchronized static void dump(PrintStream out) {
        for (Object reference : sJavaReferences) {
            int idx = sDelegates.indexOfValue(reference);
            out.printf("[%d] %s\n", sDelegates.keyAt(idx), reference.getClass().getSimpleName());
        }
        out.printf("\nTotal number of objects: %d\n", sJavaReferences.size());
    }

    /**
     * Returns the delegate from the given native int.
     * <p>
     * If the int is zero, then this will always return null.
     * <p>
     * If the int is non zero and the delegate is not found, this will throw an assert.
     *
     * @param native_object the native int.
     * @return the delegate or null if not found.
     */
    @Nullable
    public T getDelegate(long native_object) {
        if (native_object > 0) {
            Object delegate;
            synchronized (DelegateManager.class) {
                delegate = sDelegates.get(native_object);
            }

            if (Debug.DEBUG) {
                if (delegate == null) {
                    System.err.println("Unknown " + mClass.getSimpleName() + " with int " +
                            native_object);
                }
            }

            //noinspection unchecked
            return (T)delegate;
        }
        return null;
    }

    /**
     * Adds a delegate to the manager and returns the native int used to identify it.
     * @param newDelegate the delegate to add
     * @return a unique native int to identify the delegate
     */
    public long addNewDelegate(T newDelegate) {
        long native_object = sDelegateCounter.getAndIncrement();
        synchronized (DelegateManager.class) {
            sDelegates.put(native_object, newDelegate);
            // Only for development: assert !sJavaReferences.contains(newDelegate);
            sJavaReferences.add(newDelegate);
        }

        if (Debug.DEBUG) {
            System.out.println(
                    "New " + mClass.getSimpleName() + " " +
                            "with int " +
                            native_object);
        }

        return native_object;
    }

    /**
     * Removes the main reference on the given delegate.
     * @param native_object the native integer representing the delegate.
     */
    public void removeJavaReferenceFor(long native_object) {
        synchronized (DelegateManager.class) {
            T delegate = getDelegate(native_object);

            if (Debug.DEBUG) {
                System.out.println("Removing main Java ref on " + mClass.getSimpleName() +
                        " with int " + native_object);
            }

            sJavaReferences.remove(delegate);
        }
    }
}
