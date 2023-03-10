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

package android.security.cts;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

public class BitmapService extends Service {

    private final IBitmapService.Stub mBinder = new IBitmapService.Stub() {
        @Override
        public int getAllocationSize(BitmapWrapper wrapper) {
            return wrapper.getBitmap().getAllocationByteCount();
        }

        @Override
        public boolean didReceiveBitmap(BitmapWrapper wrapper) {
            return true;
        }


        @Override
        public boolean ping() {
            return true;
        }

        @Override
        public void exit() {
            System.exit(0);
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
