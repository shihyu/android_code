/*
 * Copyright 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.managedprovisioning.analytics;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.PROVISIONING_COPY_ACCOUNT_TASK_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.PROVISIONING_CREATE_PROFILE_TASK_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.PROVISIONING_DOWNLOAD_PACKAGE_TASK_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.PROVISIONING_ENCRYPT_DEVICE_ACTIVITY_TIME_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.PROVISIONING_INSTALL_PACKAGE_TASK_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.PROVISIONING_PREPROVISIONING_ACTIVITY_TIME_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.PROVISIONING_PROVISIONING_ACTIVITY_TIME_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.PROVISIONING_START_PROFILE_TASK_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.PROVISIONING_TERMS_ACTIVITY_TIME_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.PROVISIONING_TOTAL_TASK_TIME_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.PROVISIONING_WEB_ACTIVITY_TIME_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.VIEW_UNKNOWN;
import static com.android.managedprovisioning.common.Globals.ACTION_RESUME_PROVISIONING;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.stats.devicepolicy.DevicePolicyEnums;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.managedprovisioning.analytics.TimeLogger.TimeCategory;
import com.android.managedprovisioning.common.ManagedProvisioningSharedPreferences;
import com.android.managedprovisioning.task.AbstractProvisioningTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Class containing various auxiliary methods used by provisioning analytics tracker.
 */
public class AnalyticsUtils {

    final static int CATEGORY_VIEW_UNKNOWN = -1;

    public AnalyticsUtils() {}

    private static final String PROVISIONING_EXTRA_PREFIX = "android.app.extra.PROVISIONING_";

    /**
     * Returns package name of the installer package, null if package is not present on the device
     * and empty string if installer package is not present on the device.
     *
     * @param context Context used to get package manager
     * @param packageName Package name of the installed package
     */
    @Nullable
    public static String getInstallerPackageName(Context context, String packageName) {
        try {
            return context.getPackageManager().getInstallerPackageName(packageName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Returns elapsed real time.
     */
    public Long elapsedRealTime() {
        return SystemClock.elapsedRealtime();
    }

    /**
     * Returns list of all valid provisioning extras sent by the dpc.
     *
     * @param intent Intent that started provisioning
     */
    @NonNull
    public static List<String> getAllProvisioningExtras(Intent intent) {
        if (intent == null || ACTION_RESUME_PROVISIONING.equals(intent.getAction())) {
            // Provisioning extras should have already been logged for resume case.
            return new ArrayList<String>();
        } else {
            return getExtrasFromBundle(intent);
        }
    }

    /**
     * Returns unique string for all provisioning task errors.
     *
     * @param task Provisioning task which threw error
     * @param errorCode Unique code from class indicating the error
     */
    @Nullable
    public static String getErrorString(AbstractProvisioningTask task, int errorCode) {
        if (task == null) {
            return null;
        }
        // We do not have definite codes for all provisioning errors yet. We just pass the task's
        // class name and the internal task's error code to generate a unique error code.
        return task.getClass().getSimpleName() + ":" + errorCode;
    }

    @NonNull
    private static List<String> getExtrasFromBundle(Intent intent) {
        List<String> provisioningExtras = new ArrayList<String>();
        if (intent != null && intent.getExtras() != null) {
            final Set<String> keys = intent.getExtras().keySet();
            for (String key : keys) {
                if (isValidProvisioningExtra(key)) {
                    provisioningExtras.add(key);
                }
            }
        }
        return provisioningExtras;
    }

    /**
     * Returns if a string is a valid provisioning extra.
     */
    private static boolean isValidProvisioningExtra(String provisioningExtra) {
        // Currently it verifies using the prefix. We should further change this to verify using the
        // actual DPM extras.
        return provisioningExtra != null && provisioningExtra.startsWith(PROVISIONING_EXTRA_PREFIX);
    }

    /**
     * Converts from {@link MetricsEvent} constants to {@link DevicePolicyEnums} constants.
     * <p>If such a {@link MetricsEvent} does not exist, the metric is assumed
     * to belong to {@link DevicePolicyEnums}.
     */
    static int getDevicePolicyEventForCategory(@TimeCategory int metricsEvent) {
        switch (metricsEvent) {
            case PROVISIONING_COPY_ACCOUNT_TASK_MS:
                return DevicePolicyEnums.PROVISIONING_COPY_ACCOUNT_TASK_MS;
            case PROVISIONING_CREATE_PROFILE_TASK_MS:
                return DevicePolicyEnums.PROVISIONING_CREATE_PROFILE_TASK_MS;
            case PROVISIONING_DOWNLOAD_PACKAGE_TASK_MS:
                return DevicePolicyEnums.PROVISIONING_DOWNLOAD_PACKAGE_TASK_MS;
            case PROVISIONING_ENCRYPT_DEVICE_ACTIVITY_TIME_MS:
                return DevicePolicyEnums.PROVISIONING_ENCRYPT_DEVICE_ACTIVITY_TIME_MS;
            case PROVISIONING_INSTALL_PACKAGE_TASK_MS:
                return DevicePolicyEnums.PROVISIONING_INSTALL_PACKAGE_TASK_MS;
            case PROVISIONING_PREPROVISIONING_ACTIVITY_TIME_MS:
                return DevicePolicyEnums.PROVISIONING_PREPROVISIONING_ACTIVITY_TIME_MS;
            case PROVISIONING_PROVISIONING_ACTIVITY_TIME_MS:
                return DevicePolicyEnums.PROVISIONING_PROVISIONING_ACTIVITY_TIME_MS;
            case PROVISIONING_START_PROFILE_TASK_MS:
                return DevicePolicyEnums.PROVISIONING_START_PROFILE_TASK_MS;
            case PROVISIONING_WEB_ACTIVITY_TIME_MS:
                return DevicePolicyEnums.PROVISIONING_WEB_ACTIVITY_TIME_MS;
            case PROVISIONING_TERMS_ACTIVITY_TIME_MS:
                return DevicePolicyEnums.PROVISIONING_TERMS_ACTIVITY_TIME_MS;
            case PROVISIONING_TOTAL_TASK_TIME_MS:
                return DevicePolicyEnums.PROVISIONING_TOTAL_TASK_TIME_MS;
            case VIEW_UNKNOWN:
                return -1;
            default:
                return metricsEvent;
        }
    }

    /**
     * Returns the time passed since provisioning started, in milliseconds.
     * Returns <code>-1</code> if the provisioning start time was not specified via
     * {@link ManagedProvisioningSharedPreferences#writeProvisioningStartedTimestamp(long)}.
     */
    static long getProvisioningTime(ManagedProvisioningSharedPreferences sharedPreferences) {
        return getProvisioningTime(sharedPreferences, SystemClock::elapsedRealtime);
    }

    @VisibleForTesting
    static long getProvisioningTime(ManagedProvisioningSharedPreferences sharedPreferences,
            LongSupplier getTimeFunction) {
        if (sharedPreferences.getProvisioningStartedTimestamp() == 0) {
            return -1;
        }
        return getTimeFunction.getAsLong() - sharedPreferences.getProvisioningStartedTimestamp();
    }
}
