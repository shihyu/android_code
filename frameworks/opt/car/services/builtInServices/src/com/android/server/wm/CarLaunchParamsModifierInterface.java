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

package com.android.server.wm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.car.builtin.annotation.PlatformVersion;
import android.graphics.Rect;
import android.view.Display;

import com.android.annotation.AddedIn;

import java.util.List;

/**
 * Interface implemented by {@code CarLaunchParamsModifier} and used by
 * {@code CarLaunchParamsModifierUpdatable}.
 *
 * Because {@code CarLaunchParamsModifierUpdatable} calls {@code CarLaunchParamsModifierInterface}
 * with {@code mLock} acquired, {@code CarLaunchParamsModifierInterface} shouldn't call
 * {@code CarLaunchParamsModifierUpdatable} again during its execution.
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public interface CarLaunchParamsModifierInterface {
    /**
     * Returns {@link TaskDisplayAreaWrapper} of the given {@code featureId} in the given
     * {@code displayId}.
     */
    @AddedIn(PlatformVersion.TIRAMISU_0)
    @Nullable TaskDisplayAreaWrapper findTaskDisplayArea(int displayId, int featureId);

    /**
     * Returns the default {@link TaskDisplayAreaWrapper} of the given {@code displayId}.
     */
    @AddedIn(PlatformVersion.TIRAMISU_0)
    @Nullable TaskDisplayAreaWrapper getDefaultTaskDisplayAreaOnDisplay(int displayId);

    /**
     * Returns the list of fallback {@link TaskDisplayAreaWrapper} from the source of the request.
     */
    @AddedIn(PlatformVersion.TIRAMISU_0)
    @NonNull List<TaskDisplayAreaWrapper> getFallbackDisplayAreasForActivity(
            @NonNull ActivityRecordWrapper activityRecord, @Nullable RequestWrapper request);

}
