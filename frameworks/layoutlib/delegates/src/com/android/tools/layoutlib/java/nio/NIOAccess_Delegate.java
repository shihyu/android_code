/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.tools.layoutlib.java.nio;

import com.android.layoutlib.common.util.ReflectionUtils;
import com.android.layoutlib.common.util.ReflectionUtils.ReflectionException;

import java.nio.Buffer;

/**
 * A fork of libcore's java.nio.NIOAccess which does not exist in the JVM
 *
 * This class is used via JNI by code in frameworks/base/.
 * @hide
 */
// @VisibleForTesting : was default
public final class NIOAccess_Delegate {

    /**
     * Returns the underlying native pointer to the data of the given
     * Buffer starting at the Buffer's current position, or 0 if the
     * Buffer is not backed by native heap storage.
     * @hide
     */
    // @VisibleForTesting : was default
    public static long getBasePointer(Buffer b) {
        try {
            long address = (long)ReflectionUtils.getFieldValue(Buffer.class, b, "address");
            if (address == 0L || !b.isDirect()) {
                return 0L;
            }
            return address + ((long)b.position() << Buffer_Delegate.elementSizeShift(b));
        } catch (ReflectionException e) {
            return 0L;
        }
    }

    /**
     * Returns the underlying Java array containing the data of the
     * given Buffer, or null if the Buffer is not backed by a Java array.
     */
    static Object getBaseArray(Buffer b) {
        return b.hasArray() ? b.array() : null;
    }

    /**
     * Returns the offset in bytes from the start of the underlying
     * Java array object containing the data of the given Buffer to
     * the actual start of the data. The start of the data takes into
     * account the Buffer's current position. This method is only
     * meaningful if getBaseArray() returns non-null.
     */
    static int getBaseArrayOffset(Buffer b) {
        return b.hasArray() ?
                ((b.arrayOffset() + b.position()) << Buffer_Delegate.elementSizeShift(b)) : 0;
    }


}
