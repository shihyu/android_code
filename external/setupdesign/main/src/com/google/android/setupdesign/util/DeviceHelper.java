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

package com.google.android.setupdesign.util;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.google.android.setupdesign.R;

/** Helper class to get attributes of the device, like a friendly display name. */
public final class DeviceHelper {

  private static final String TAG = DeviceHelper.class.getSimpleName();

  @VisibleForTesting
  public static final String SUW_AUTHORITY = "com.google.android.setupwizard.partner";

  @VisibleForTesting public static final String GET_DEVICE_NAME_METHOD = "getDeviceName";

  /**
   * Get the device name text from these resources, if they are unavailable or setupwizard apk is
   * older which does not contains {@link DeviceHelper#GET_DEVICE_NAME_METHOD} method, return the
   * device name as default value "device".
   *
   * <p>Priority: partner config ({@link
   * com.google.android.setupwizard.util.PartnerResource#DEVICE_NAME}) > {@link
   * android.provider.Settings.Global#DEVICE_NAME} > system property ro.product.model)
   */
  @Nullable
  public static CharSequence getDeviceName(@NonNull Context context) {
    Bundle deviceName = null;

    try {
      deviceName =
          context
              .getContentResolver()
              .call(
                  new Uri.Builder()
                      .scheme(ContentResolver.SCHEME_CONTENT)
                      .authority(SUW_AUTHORITY)
                      .build(),
                  GET_DEVICE_NAME_METHOD,
                  /* arg= */ null,
                  /* extras= */ null);
    } catch (IllegalArgumentException | SecurityException exception) {
      Log.w(TAG, "device name unknown; return the device name as default value");
    }

    if (deviceName != null) {
      return deviceName.getCharSequence(GET_DEVICE_NAME_METHOD, null);
    }

    return context.getString(R.string.sud_default_device_name);
  }

  private DeviceHelper() {}
}
