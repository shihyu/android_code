/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.InstrumentationRegistry;

import com.android.car.admin.NotificationHelper;
import com.android.car.internal.NotificationHelperBase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class BuiltinPackageDependencyUnitTest {
    @Test
    public void testCreateNotificationHelper() {
        // NotificationHelper code is in this test package already, so target Context is ok.
        NotificationHelperBase helper = BuiltinPackageDependency.createNotificationHelper(
                InstrumentationRegistry.getTargetContext());

        assertThat(helper).isNotNull();
        assertThat(helper.getClass()).isEqualTo(NotificationHelper.class);
    }
}
