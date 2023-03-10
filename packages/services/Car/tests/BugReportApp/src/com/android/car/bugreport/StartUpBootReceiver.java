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
package com.android.car.bugreport;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;
import android.util.Log;

/**
 * Handles device boot intents.
 *
 * <ul>
 *     <li>Schedules {@link UploadJob}</li>
 *     <li>Schedules {@link ExpireOldBugReportsJob}</li>
 *     <li>Starts {@link TtlPointsDecremental}</li>
 * </ul>
 */
public class StartUpBootReceiver extends BroadcastReceiver {
    public static final String TAG = StartUpBootReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Config.isBugReportEnabled()) {
            return;
        }

        // Run it only once for the system user (u0) and ignore for other users.
        UserManager userManager = context.getSystemService(UserManager.class);
        if (!userManager.isSystemUser()) {
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        Log.d(TAG, "StartUpBootReceiver BOOT_COMPLETED");

        // We removed "persisted" from UploadJob scheduling, instead we will manually schedule
        // the job on boot, because "persisted" seems more fragile.
        JobSchedulingUtils.scheduleUploadJob(context);

        // We schedule ExpireOldBugReportsJob after every boot, so it can be run in garage mode
        // during shutdown.
        JobSchedulingUtils.scheduleExpireOldBugReportsJobInGarageMode(context);

        // Use goAsync() to allow TtlPointsDecremental to complete.
        startTtlPointsDecremental(context, goAsync());
    }

    /** Start {@link TtlPointsDecremental} in a separate thread. */
    private void startTtlPointsDecremental(Context context, PendingResult bootReceiverResult) {
        new Thread(new TtlPointsDecremental(context, bootReceiverResult::finish)).start();
    }
}
