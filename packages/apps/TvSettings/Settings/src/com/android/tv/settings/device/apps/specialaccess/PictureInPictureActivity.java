/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.tv.settings.device.apps.specialaccess;

import androidx.fragment.app.Fragment;

import com.android.tv.settings.TvSettingsActivity;
import com.android.tv.settings.system.SecurityFragment;

/**
 * Wrapper activity for {@link PictureInPicture}
 */
public class PictureInPictureActivity extends TvSettingsActivity {

    @Override
    protected Fragment createSettingsFragment() {
        if (SecurityFragment.isRestrictedProfileInEffect(this)) {
            finish();
            return null;
        } else {
            return com.android.tv.settings.overlay.FlavorUtils.getFeatureFactory(
                    this).getSettingsFragmentProvider()
                    .newSettingsFragment(PictureInPicture.class.getName(), null);
        }
    }
}
