/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.car.pm;

import android.car.builtin.content.pm.PackageManagerHelper;
import android.car.builtin.util.Slogf;
import android.car.content.pm.CarAppBlockingPolicy;
import android.car.content.pm.ICarAppBlockingPolicy;
import android.car.content.pm.ICarAppBlockingPolicySetter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;

import com.android.car.CarLog;
import com.android.internal.annotations.GuardedBy;

public class AppBlockingPolicyProxy implements ServiceConnection {

    private static final String TAG = CarLog.tagFor(AppBlockingPolicyProxy.class);

    private final CarPackageManagerService mService;
    private final Context mContext;
    private final ServiceInfo mServiceInfo;
    private final ICarAppBlockingPolicySetterImpl mSetter;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private ICarAppBlockingPolicy mPolicyService;

    /**
     * policy not set within this time after binding will be treated as failure and will be
     * ignored.
     */
    private static final long TIMEOUT_MS = 5000;
    private static final int MAX_CRASH_RETRY = 2;
    @GuardedBy("mLock")
    private int mCrashCount;
    @GuardedBy("mLock")
    private boolean mBound;

    private final Handler mHandler;
    private final Runnable mTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            Slogf.w(TAG, "Timeout for policy setting for service:" + mServiceInfo);
            disconnect();
            mService.onPolicyConnectionFailure(AppBlockingPolicyProxy.this);
        }
    };

    public AppBlockingPolicyProxy(CarPackageManagerService service, Context context,
            ServiceInfo serviceInfo) {
        mService = service;
        mContext = context;
        mServiceInfo = serviceInfo;
        mSetter = new ICarAppBlockingPolicySetterImpl();
        mHandler = new Handler(mService.getLooper());
    }

    public String getPackageName() {
        return mServiceInfo.packageName;
    }

    public void connect() {
        Intent intent = new Intent();
        intent.setComponent(PackageManagerHelper.getComponentName(mServiceInfo));
        mContext.bindServiceAsUser(intent, this, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT,
                UserHandle.CURRENT);
        synchronized (mLock) {
            mBound = true;
        }
        mHandler.postDelayed(mTimeoutRunnable, TIMEOUT_MS);
    }

    public void disconnect() {
        synchronized (mLock) {
            if (!mBound) {
                return;
            }
            mBound = false;
            mPolicyService = null;
        }
        mHandler.removeCallbacks(mTimeoutRunnable);
        try {
            mContext.unbindService(this);
        } catch (IllegalArgumentException e) {
            Slogf.w(TAG, "unbind", e);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        ICarAppBlockingPolicy policy = null;
        boolean failed = false;
        synchronized (mLock) {
            mPolicyService = ICarAppBlockingPolicy.Stub.asInterface(service);
            policy = mPolicyService;
            if (policy == null) {
                failed = true;
            }
        }
        if (failed) {
            Slogf.w(TAG, "Policy service connected with null binder:" + name);
            mService.onPolicyConnectionFailure(this);
            return;
        }
        try {
            policy.setAppBlockingPolicySetter(mSetter);
        } catch (RemoteException e) {
            // let retry handle this
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        boolean failed = false;
        synchronized (mLock) {
            mCrashCount++;
            if (mCrashCount > MAX_CRASH_RETRY) {
                mPolicyService = null;
                failed = true;
            }
        }
        if (failed) {
            Slogf.w(TAG, "Policy service keep crashing, giving up:" + name);
            mService.onPolicyConnectionFailure(this);
        }
    }

    @Override
    public String toString() {
        synchronized (mLock) {
            return "AppBlockingPolicyProxy [mServiceInfo=" + mServiceInfo + ", mCrashCount="
                    + mCrashCount + "]";
        }
    }

    private class ICarAppBlockingPolicySetterImpl extends ICarAppBlockingPolicySetter.Stub {

        @Override
        public void setAppBlockingPolicy(CarAppBlockingPolicy policy) {
            mHandler.removeCallbacks(mTimeoutRunnable);
            if (policy == null) {
                Slogf.w(TAG, "setAppBlockingPolicy null policy from policy service:"
                        + mServiceInfo);
            }
            mService.onPolicyConnectionAndSet(AppBlockingPolicyProxy.this, policy);
        }
    }
}
