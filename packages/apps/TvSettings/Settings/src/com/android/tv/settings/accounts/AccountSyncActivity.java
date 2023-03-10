/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.tv.settings.accounts;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.fragment.app.Fragment;

import com.android.tv.settings.TvSettingsActivity;

/**
 * Displays the sync settings for a given account.
 */
public class AccountSyncActivity extends TvSettingsActivity {

    private static final String ARG_ACCOUNT = "account";
    public static final String EXTRA_ACCOUNT = "account_name";

    @Override
    protected Fragment createSettingsFragment() {
        String accountName = getIntent().getStringExtra(EXTRA_ACCOUNT);
        Account account = null;
        if (!TextUtils.isEmpty(accountName)) {
            // Search for the account.
            for (Account candidateAccount : AccountManager.get(this).getAccounts()) {
                if (candidateAccount.name.equals(accountName)) {
                    account = candidateAccount;
                    break;
                }
            }
        }
        return com.android.tv.settings.overlay.FlavorUtils.getFeatureFactory(
                this).getSettingsFragmentProvider()
                .newSettingsFragment(AccountSyncFragment.class.getName(), getArguments(account));
    }

    private Bundle getArguments(Account account) {
        final Bundle b = new Bundle(1);
        b.putParcelable(ARG_ACCOUNT, account);
        return b;
    }
}
