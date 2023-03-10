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

package com.android.tv.settings.compat;

import android.content.Context;
import android.util.AttributeSet;

/** Preference category compat class to display wifi list. */
public class TsCollapsibleCategory extends TsPreferenceCategory {
    public static final String COLLAPSE = "collapse";
    private static final int COLLAPSED_ITEM_COUNT = 3;
    private boolean mCollapsed = true;

    public TsCollapsibleCategory(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public TsCollapsibleCategory(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public TsCollapsibleCategory(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TsCollapsibleCategory(Context context) {
        super(context);
    }

    public TsCollapsibleCategory(Context context, String[] keys) {
        super(context);
        setKeys(keys);
    }

    @Override
    public int getPreferenceCount() {
        if (mCollapsed && shouldShowCollapsePref()) {
            return COLLAPSED_ITEM_COUNT;
        } else {
            return super.getPreferenceCount();
        }
    }

    public int getRealPreferenceCount() {
        return super.getPreferenceCount();
    }

    /**
     * Only show the collapse preference if the list would be longer than the collapsed list plus
     * the
     * collapse preference itself.
     *
     * @return true if collapse pref should be shown
     */
    public boolean shouldShowCollapsePref() {
        return super.getPreferenceCount() >= COLLAPSED_ITEM_COUNT + 1;
    }

    public boolean isCollapsed() {
        return mCollapsed;
    }

    public void setCollapsed(Boolean collapsed) {
        if (collapsed != null) {
            this.mCollapsed = collapsed;
            notifyHierarchyChanged();
        }
    }
}
