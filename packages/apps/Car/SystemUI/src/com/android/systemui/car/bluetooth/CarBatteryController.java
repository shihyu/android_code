/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.car.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import com.android.systemui.statusbar.policy.BatteryController;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * A {@link BatteryController} that is specific to the Auto use-case. For Auto, the battery icon
 * displays the battery status of a device that is connected via bluetooth and not the system's
 * battery.
 */
public class CarBatteryController extends BroadcastReceiver implements BatteryController {
    private static final String TAG = "CarBatteryController";

    private static final int INVALID_BATTERY_LEVEL = -1;

    private final Context mContext;

    private final BluetoothAdapter mAdapter = BluetoothAdapter.getDefaultAdapter();
    private final ArrayList<BatteryStateChangeCallback> mChangeCallbacks = new ArrayList<>();
    private int mLevel;
    private BatteryViewHandler mBatteryViewHandler;

    public CarBatteryController(Context context) {
        mContext = context;

        if (mAdapter == null) {
            return;
        }
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("CarBatteryController state:");
        pw.print("    mLevel=");
        pw.println(mLevel);
    }

    @Override
    public void setPowerSaveMode(boolean powerSave) {
        // No-op. No power save mode for the car.
    }

    @Override
    public void setPowerSaveMode(boolean powerSave, View view) {
        // No-op. No power save mode for the car.
    }

    @Override
    public void addCallback(BatteryController.BatteryStateChangeCallback cb) {
        mChangeCallbacks.add(cb);

        // There is no way to know if the phone is plugged in or charging via bluetooth, so pass
        // false for these values.
        cb.onBatteryLevelChanged(mLevel, false /* pluggedIn */, false /* charging */);
        cb.onPowerSaveChanged(false /* isPowerSave */);
    }

    @Override
    public void removeCallback(BatteryController.BatteryStateChangeCallback cb) {
        mChangeCallbacks.remove(cb);
    }

    /** Sets {@link BatteryViewHandler}. */
    public void addBatteryViewHandler(BatteryViewHandler batteryViewHandler) {
        mBatteryViewHandler = batteryViewHandler;
    }

    /** Starts listening for bluetooth broadcast messages. */
    public void startListening() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED);
        filter.addAction(BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED);
        mContext.registerReceiver(this, filter);
    }

    /** Stops listening for bluetooth broadcast messages. */
    public void stopListening() {
        mContext.unregisterReceiver(this);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onReceive(). action: " + action);
        }

        if (BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED.equals(action)) {
            BluetoothDevice device =
                    (BluetoothDevice) intent.getExtra(BluetoothDevice.EXTRA_DEVICE);
            int batteryLevel = intent.getIntExtra(BluetoothDevice.EXTRA_BATTERY_LEVEL,
                    INVALID_BATTERY_LEVEL);

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Received ACTION_BATTERY_LEVEL_CHANGED event: device=" + device
                        + ", level=" + batteryLevel);
            }

            updateBatteryLevel(batteryLevel);

            if (batteryLevel != INVALID_BATTERY_LEVEL && mBatteryViewHandler != null) {
                mBatteryViewHandler.showBatteryView();
            }
        } else if (BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            int newState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                int oldState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1);
                Log.d(TAG, "ACTION_CONNECTION_STATE_CHANGED event: "
                        + oldState + " -> " + newState);

            }
            BluetoothDevice device =
                    (BluetoothDevice) intent.getExtra(BluetoothDevice.EXTRA_DEVICE);
            updateBatteryIcon(device, newState);
        }
    }

    /**
     * Verifies battery level is a valid percentage and notifies
     * any {@link BatteryStateChangeCallback}s of this.
     */
    private void updateBatteryLevel(int batteryLevel) {
        // Valid battery level is from 0 to 100, inclusive.
        if (batteryLevel < 0 || batteryLevel > 100) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Battery level invalid. Ignoring.");
            }
            return;
        }

        mLevel = batteryLevel;

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Battery level: " + mLevel);
        }

        notifyBatteryLevelChanged();
    }

    /**
     * Updates the display of the battery icon depending on the given connection state from the
     * given {@link BluetoothDevice}.
     */
    private void updateBatteryIcon(BluetoothDevice device, int newState) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Device connected");
            }

            if (mBatteryViewHandler != null) {
                mBatteryViewHandler.showBatteryView();
            }

            if (device == null) {
                return;
            }

            int batteryLevel = device.getBatteryLevel();
            updateBatteryLevel(batteryLevel);
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Device disconnected");
            }

            if (mBatteryViewHandler != null) {
                mBatteryViewHandler.hideBatteryView();
            }
        }
    }

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        // TODO: Car demo mode.
    }

    @Override
    public boolean isPluggedIn() {
        return true;
    }

    @Override
    public boolean isPowerSave() {
        // Power save is not valid for the car, so always return false.
        return false;
    }

    @Override
    public boolean isAodPowerSave() {
        return false;
    }

    private void notifyBatteryLevelChanged() {
        for (int i = 0, size = mChangeCallbacks.size(); i < size; i++) {
            mChangeCallbacks.get(i)
                    .onBatteryLevelChanged(mLevel, false /* pluggedIn */, false /* charging */);
        }
    }

    /**
     * An interface indicating the container of a View that will display what the information
     * in the {@link CarBatteryController}.
     */
    public interface BatteryViewHandler {
        /** Hides the battery view. */
        void hideBatteryView();

        /** Shows the battery view. */
        void showBatteryView();
    }

}
