/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.uwb;

import android.os.Build;

class SystemBuildProperties {
    /** @return if it is an eng build. */
    public boolean isEngBuild() {
        return Build.TYPE.equals("eng");
    }

    /** @return if it is an userdebug build. */
    public boolean isUserdebugBuild() {
        return Build.TYPE.equals("userdebug");
    }

    /** @return if it is a normal user build. */
    public boolean isUserBuild() {
        return Build.TYPE.equals("user");
    }
}
