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

package com.googlecode.android_scripting.facade.bluetooth;

import android.app.Service;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothA2dpSink;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAdapter.OobDataCallback;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidHost;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothMap;
import android.bluetooth.BluetoothMapClient;
import android.bluetooth.BluetoothPan;
import android.bluetooth.BluetoothPbapClient;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.OobData;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.ParcelUuid;

import com.googlecode.android_scripting.Log;
import com.googlecode.android_scripting.facade.EventFacade;
import com.googlecode.android_scripting.facade.FacadeManager;
import com.googlecode.android_scripting.jsonrpc.RpcReceiver;
import com.googlecode.android_scripting.rpc.Rpc;
import com.googlecode.android_scripting.rpc.RpcDefault;
import com.googlecode.android_scripting.rpc.RpcOptional;
import com.googlecode.android_scripting.rpc.RpcParameter;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BluetoothConnectionFacade extends RpcReceiver {

    private final Service mService;
    private final Context mContext;
    private final BluetoothAdapter mBluetoothAdapter;
    private final BluetoothManager mBluetoothManager;
    private final BluetoothPairingHelper mPairingHelper;
    private final Map<String, BroadcastReceiver> listeningDevices;
    private final EventFacade mEventFacade;
    private final OobDataCallback mGenerateOobDataCallback = new OobDataCallback() {
            @Override
            public void onError(int error) {
                Log.d("onError: " + error);
                Bundle results = new Bundle();
                results.putInt("Error", error);
                mEventFacade.postEvent("ErrorOobData", results.clone());
            }

            @Override
            public void onOobData(int transport, OobData data) {
                Log.d("Transport: " + transport);
                Log.d("OobData: " + data);
                Bundle results = new Bundle();
                results.putInt("transport", transport);
                // Just what we need create a bond
                results.putString("address_with_type",
                        toHexString(data.getDeviceAddressWithType()));
                results.putString("confirmation", toHexString(data.getConfirmationHash()));
                results.putString("randomizer", toHexString(data.getRandomizerHash()));
                mEventFacade.postEvent("GeneratedOobData", results.clone());
            }
        };

    private final IntentFilter mDiscoverConnectFilter;
    private final IntentFilter mPairingFilter;
    private final IntentFilter mBondFilter;
    private final IntentFilter mA2dpStateChangeFilter;
    private final IntentFilter mA2dpSinkStateChangeFilter;
    private final IntentFilter mHidStateChangeFilter;
    private final IntentFilter mHidDeviceStateChangeFilter;
    private final IntentFilter mHspStateChangeFilter;
    private final IntentFilter mHfpClientStateChangeFilter;
    private final IntentFilter mPbapClientStateChangeFilter;
    private final IntentFilter mPanStateChangeFilter;
    private final IntentFilter mMapClientStateChangeFilter;
    private final IntentFilter mMapStateChangeFilter;

    private final Bundle mGoodNews;
    private final Bundle mBadNews;

    private BluetoothA2dpFacade mA2dpProfile;
    private BluetoothA2dpSinkFacade mA2dpSinkProfile;
    private BluetoothHidFacade mHidProfile;
    private BluetoothHidDeviceFacade mHidDeviceProfile;
    private BluetoothHspFacade mHspProfile;
    private BluetoothHfpClientFacade mHfpClientProfile;
    private BluetoothPbapClientFacade mPbapClientProfile;
    private BluetoothPanFacade mPanProfile;
    private BluetoothMapClientFacade mMapClientProfile;
    private BluetoothMapFacade mMapProfile;
    private ArrayList<String> mDeviceMonitorList;

    public BluetoothConnectionFacade(FacadeManager manager) {
        super(manager);
        mService = manager.getService();
        mContext = mService.getApplicationContext();
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothManager = (BluetoothManager) mContext.getSystemService(
                Service.BLUETOOTH_SERVICE);
        mDeviceMonitorList = new ArrayList<String>();
        // Use a synchronized map to avoid racing problems
        listeningDevices = Collections.synchronizedMap(new HashMap<String, BroadcastReceiver>());

        mEventFacade = manager.getReceiver(EventFacade.class);
        mPairingHelper = new BluetoothPairingHelper(mEventFacade);
        mA2dpProfile = manager.getReceiver(BluetoothA2dpFacade.class);
        mA2dpSinkProfile = manager.getReceiver(BluetoothA2dpSinkFacade.class);
        mHidProfile = manager.getReceiver(BluetoothHidFacade.class);
        mHidDeviceProfile = manager.getReceiver(BluetoothHidDeviceFacade.class);
        mHspProfile = manager.getReceiver(BluetoothHspFacade.class);
        mHfpClientProfile = manager.getReceiver(BluetoothHfpClientFacade.class);
        mPbapClientProfile = manager.getReceiver(BluetoothPbapClientFacade.class);
        mPanProfile = manager.getReceiver(BluetoothPanFacade.class);
        mMapClientProfile = manager.getReceiver(BluetoothMapClientFacade.class);
        mMapProfile = manager.getReceiver(BluetoothMapFacade.class);

        mDiscoverConnectFilter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        mDiscoverConnectFilter.addAction(BluetoothDevice.ACTION_UUID);
        mDiscoverConnectFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);

        mPairingFilter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        mPairingFilter.addAction(BluetoothDevice.ACTION_CONNECTION_ACCESS_REQUEST);
        mPairingFilter.addAction(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY);
        mPairingFilter.setPriority(999);

        mBondFilter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        mBondFilter.addAction(BluetoothDevice.ACTION_FOUND);
        mBondFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);

        mA2dpStateChangeFilter = new IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        mA2dpSinkStateChangeFilter =
            new IntentFilter(BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED);
        mHidStateChangeFilter =
            new IntentFilter(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED);
        mHidDeviceStateChangeFilter =
                new IntentFilter(BluetoothHidDevice.ACTION_CONNECTION_STATE_CHANGED);
        mHspStateChangeFilter = new IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        mHfpClientStateChangeFilter =
            new IntentFilter(BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED);
        mPbapClientStateChangeFilter =
            new IntentFilter(BluetoothPbapClient.ACTION_CONNECTION_STATE_CHANGED);
        mPanStateChangeFilter =
            new IntentFilter(BluetoothPan.ACTION_CONNECTION_STATE_CHANGED);
        mMapClientStateChangeFilter =
            new IntentFilter(BluetoothMapClient.ACTION_CONNECTION_STATE_CHANGED);
        mMapStateChangeFilter =
            new IntentFilter(BluetoothMap.ACTION_CONNECTION_STATE_CHANGED);

        mGoodNews = new Bundle();
        mGoodNews.putBoolean("Status", true);
        mBadNews = new Bundle();
        mBadNews.putBoolean("Status", false);
    }

    private void unregisterCachedListener(String listenerId) {
        BroadcastReceiver listener = listeningDevices.remove(listenerId);
        if (listener != null) {
            mService.unregisterReceiver(listener);
        }
    }

    /**
     * Connect to a specific device upon its discovery
     */
    public class DiscoverConnectReceiver extends BroadcastReceiver {
        private final String mDeviceID;
        private BluetoothDevice mDevice;

        /**
         * Constructor
         *
         * @param deviceID Either the device alias name or mac address.
         * @param bond     If true, bond the device only.
         */
        public DiscoverConnectReceiver(String deviceID) {
            super();
            mDeviceID = deviceID;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            // The specified device is found.
            if (action.equals(BluetoothDevice.ACTION_FOUND)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (BluetoothFacade.deviceMatch(device, mDeviceID)) {
                    Log.d("Found device " + device.getAlias() + " for connection.");
                    mBluetoothAdapter.cancelDiscovery();
                    mDevice = device;
                }
                // After discovery stops.
            } else if (action.equals(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)) {
                if (mDevice == null) {
                    Log.d("Device " + mDeviceID + " not discovered.");
                    mEventFacade.postEvent("Bond" + mDeviceID, mBadNews);
                    return;
                }
                boolean status = mDevice.fetchUuidsWithSdp();
                Log.d("Initiated ACL connection: " + status);
            } else if (action.equals(BluetoothDevice.ACTION_UUID)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (BluetoothFacade.deviceMatch(device, mDeviceID)) {
                    Log.d("Initiating connections.");
                    connectProfile(device, mDeviceID);
                    mService.unregisterReceiver(listeningDevices.remove("Connect" + mDeviceID));
                }
            }
        }
    }

    /**
     * Connect to a specific device upon its discovery
     */
    public class DiscoverBondReceiver extends BroadcastReceiver {
        private final String mDeviceID;
        private BluetoothDevice mDevice = null;
        private boolean started = false;

        /**
         * Constructor
         *
         * @param deviceID Either the device alias name or Mac address.
         */
        public DiscoverBondReceiver(String deviceID) {
            super();
            mDeviceID = deviceID;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            // The specified device is found.
            if (action.equals(BluetoothDevice.ACTION_FOUND)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (BluetoothFacade.deviceMatch(device, mDeviceID)) {
                    Log.d("Found device " + device.getAlias() + " for connection.");
                    mEventFacade.postEvent("Discovery" + mDeviceID, mGoodNews);
                    mBluetoothAdapter.cancelDiscovery();
                    mDevice = device;
                }
                // After discovery stops.
            } else if (action.equals(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)) {
                if (mDevice == null) {
                    Log.d("Device " + mDeviceID + " was not discovered.");
                    mEventFacade.postEvent("Discovery", mBadNews);
                    mEventFacade.postEvent("Bond", mBadNews);
                    return;
                }
                // Attempt to initiate bonding.
                if (!started) {
                    Log.d("Bond with " + mDevice.getAlias());
                    if (mDevice.createBond()) {
                        started = true;
                        Log.d("Bonding started.");
                    } else {
                        Log.e("Failed to bond with " + mDevice.getAlias());
                        mEventFacade.postEvent("Bond", mBadNews);
                        mService.unregisterReceiver(listeningDevices.remove("Bond" + mDeviceID));
                    }
                }
            } else if (action.equals(BluetoothDevice.ACTION_BOND_STATE_CHANGED)) {
                Log.d("Bond state changing.");
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (BluetoothFacade.deviceMatch(device, mDeviceID)) {
                    int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                    Log.d("New state is " + state);
                    if (state == BluetoothDevice.BOND_BONDED) {
                        Log.d("Bonding with " + mDeviceID + " successful.");
                        mEventFacade.postEvent("Bond" + mDeviceID, mGoodNews);
                        mService.unregisterReceiver(listeningDevices.remove("Bond" + mDeviceID));
                    }
                }
            }
        }
    }

    public class ConnectStateChangeReceiver extends BroadcastReceiver {
        private final String mDeviceID;

        public ConnectStateChangeReceiver(String deviceID) {
            mDeviceID = deviceID;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            // no matter what the action, just push it...
            String action = intent.getAction();
            Log.d("Action received: " + action);

            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            // Check if received the specified device
            if (!BluetoothFacade.deviceMatch(device, mDeviceID)) {
                Log.e("Action devices does match act: " + device + " exp " + mDeviceID);
                return;
            }
            // Find the state.
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
            if (state == -1) {
                Log.e("Action does not have a state.");
                return;
            }

            // Switch Only Necessary for Old implementation. Left in for backwards compatability.
            int profile = -1;
            switch (action) {
                case BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED:
                    profile = BluetoothProfile.A2DP;
                    break;
                case BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED:
                    profile = BluetoothProfile.HID_HOST;
                    break;
                case BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED:
                    profile = BluetoothProfile.HEADSET;
                    break;
                case BluetoothPan.ACTION_CONNECTION_STATE_CHANGED:
                    profile = BluetoothProfile.PAN;
                    break;
                case BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED:
                    profile = BluetoothProfile.HEADSET_CLIENT;
                    break;
                case BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED:
                    profile = BluetoothProfile.A2DP_SINK;
                    break;
                case BluetoothPbapClient.ACTION_CONNECTION_STATE_CHANGED:
                    profile = BluetoothProfile.PBAP_CLIENT;
                    break;
                case BluetoothMapClient.ACTION_CONNECTION_STATE_CHANGED:
                    profile = BluetoothProfile.MAP_CLIENT;
                    break;
            }

            if (profile == -1) {
                Log.e("Action does not match any given profiles " + action);
            }

            // The newer implementation will just post the Bundle with the literal event
            // intead of the old implemenatation of posting BluetoothProfileConnectionStateChanged
            // with the action inside of the Bundle. This makes for cleaner connection handling
            // from test frameworks. Left the old implemenation in for backwards compatability.

            // Post an event to Facade.
            Bundle news = new Bundle();
            news.putInt("state", state);
            news.putString("addr", device.getAddress());
            mEventFacade.postEvent(action, news);

            news.putInt("profile", profile);
            news.putString("action", action);
            mEventFacade.postEvent("BluetoothProfileConnectionStateChanged", news);
        }
    }

    /**
     * Converts a given JSONArray to an ArrayList of Integers
     *
     * @param jsonArray the JSONArray to be converted
     * @return <code>List<Integer></></code> the converted list of Integers
     */
    private List<Integer> jsonArrayToIntegerList(JSONArray jsonArray) throws JSONException {
        if (jsonArray == null) {
            return null;
        }
        List<Integer> intArray = new ArrayList<Integer>();
        for (int i = 0; i < jsonArray.length(); i++) {
            intArray.add(jsonArray.getInt(i));
        }
        return intArray;

    }

    @Rpc(description = "Start monitoring state changes for input device.")
    public void bluetoothStartConnectionStateChangeMonitor(
        @RpcParameter(name = "deviceID",
                    description = "Name or MAC address of a bluetooth device.")
                    String deviceID) {
        if (!mDeviceMonitorList.contains(deviceID)) {
            ConnectStateChangeReceiver receiver = new ConnectStateChangeReceiver(deviceID);
            mService.registerReceiver(receiver, mA2dpStateChangeFilter);
            mService.registerReceiver(receiver, mA2dpSinkStateChangeFilter);
            mService.registerReceiver(receiver, mHidStateChangeFilter);
            mService.registerReceiver(receiver, mHspStateChangeFilter);
            mService.registerReceiver(receiver, mHfpClientStateChangeFilter);
            mService.registerReceiver(receiver, mPbapClientStateChangeFilter);
            mService.registerReceiver(receiver, mPanStateChangeFilter);
            mService.registerReceiver(receiver, mMapClientStateChangeFilter);
            mService.registerReceiver(receiver, mMapStateChangeFilter);
            listeningDevices.put("StateChangeListener:" + deviceID, receiver);
        }
    }

    /**
     * Connect on all the profiles to the given Bluetooth device
     *
     * @param device   The <code>BluetoothDevice</code> to connect to
     * @param deviceID Name (String) of the device to connect to
     */
    private void connectProfile(BluetoothDevice device, String deviceID) {
        mService.registerReceiver(mPairingHelper, mPairingFilter);
        ParcelUuid[] deviceUuids = device.getUuids();
        Log.d("Device uuid is " + Arrays.toString(deviceUuids));
        if (deviceUuids == null) {
            mEventFacade.postEvent("BluetoothProfileConnectionEvent", mBadNews);
        }
        Log.d("Connecting to " + device.getAlias());
        if (BluetoothUuid.containsAnyUuid(BluetoothA2dpFacade.SINK_UUIDS, deviceUuids)) {
            mA2dpProfile.a2dpConnect(device);
        }
        if (BluetoothUuid.containsAnyUuid(BluetoothA2dpSinkFacade.SOURCE_UUIDS, deviceUuids)) {
            mA2dpSinkProfile.a2dpSinkConnect(device);
        }
        if (BluetoothUuid.containsAnyUuid(BluetoothHidFacade.UUIDS, deviceUuids)) {
            mHidProfile.hidConnect(device);
        }
        if (BluetoothUuid.containsAnyUuid(BluetoothHspFacade.UUIDS, deviceUuids)) {
            mHspProfile.hspConnect(device);
        }
        if (BluetoothUuid.containsAnyUuid(BluetoothHfpClientFacade.UUIDS, deviceUuids)) {
            mHfpClientProfile.hfpClientConnect(device);
        }
        if (BluetoothUuid.containsAnyUuid(BluetoothMapClientFacade.MAP_UUIDS, deviceUuids)) {
            mMapClientProfile.mapClientConnect(device);
        }
        if (BluetoothUuid.containsAnyUuid(BluetoothPanFacade.UUIDS, deviceUuids)) {
            mPanProfile.panConnect(device);
        }
        if (BluetoothUuid.containsAnyUuid(BluetoothPbapClientFacade.UUIDS, deviceUuids)) {
            mPbapClientProfile.pbapClientConnect(device);
        }
        mService.unregisterReceiver(mPairingHelper);
    }

    /**
     * Disconnect on all available profiles from the given device
     *
     * @param device   The <code>BluetoothDevice</code> to disconnect from
     * @param deviceID Name (String) of the device to disconnect from
     */
    private void disconnectProfiles(BluetoothDevice device, String deviceID) {
        Log.d("Disconnecting device " + device);
        // Blindly disconnect all profiles. We may not have some of them connected so that will be a
        // null op.
        mA2dpProfile.a2dpDisconnect(device);
        mA2dpSinkProfile.a2dpSinkDisconnect(device);
        mHidProfile.hidDisconnect(device);
        mHidDeviceProfile.hidDeviceDisconnect(device);
        mHspProfile.hspDisconnect(device);
        mHfpClientProfile.hfpClientDisconnect(device);
        mPbapClientProfile.pbapClientDisconnect(device);
        mPanProfile.panDisconnect(device);
        mMapClientProfile.mapClientDisconnect(device);
    }

    /**
     * Disconnect from specific profiles provided in the given List of profiles.
     *
     * @param device     The {@link BluetoothDevice} to disconnect from
     * @param deviceID   Name/BDADDR (String) of the device to disconnect from
     * @param profileIds The list of profiles we want to disconnect on.
     */
    private void disconnectProfiles(BluetoothDevice device, String deviceID,
            List<Integer> profileIds) {
        boolean result;
        for (int profileId : profileIds) {
            switch (profileId) {
                case BluetoothProfile.A2DP_SINK:
                    mA2dpSinkProfile.a2dpSinkDisconnect(device);
                    break;
                case BluetoothProfile.A2DP:
                    mA2dpProfile.a2dpDisconnect(device);
                    break;
                case BluetoothProfile.HID_HOST:
                    mHidProfile.hidDisconnect(device);
                    break;
                case BluetoothProfile.HID_DEVICE:
                    mHidDeviceProfile.hidDeviceDisconnect(device);
                    break;
                case BluetoothProfile.HEADSET:
                    mHspProfile.hspDisconnect(device);
                    break;
                case BluetoothProfile.HEADSET_CLIENT:
                    mHfpClientProfile.hfpClientDisconnect(device);
                    break;
                case BluetoothProfile.PAN:
                    mPanProfile.panDisconnect(device);
                    break;
                case BluetoothProfile.PBAP_CLIENT:
                    mPbapClientProfile.pbapClientDisconnect(device);
                    break;
                case BluetoothProfile.MAP_CLIENT:
                    mMapClientProfile.mapDisconnect(device);
                    break;
                default:
                    Log.d("Unknown Profile Id to disconnect from. Quitting");
                    return; // returns on the first unknown profile  it encounters.
            }
        }
    }

    @Rpc(description = "Start intercepting all bluetooth connection pop-ups.")
    public void bluetoothStartPairingHelper(
        @RpcParameter(name = "autoConfirm",
                    description = "Whether connection should be auto confirmed")
        @RpcDefault("true") @RpcOptional
        Boolean autoConfirm) {
        Log.d("Staring pairing helper");
        mPairingHelper.setAutoConfirm(autoConfirm);
        mService.registerReceiver(mPairingHelper, mPairingFilter);
    }

    @Rpc(description = "Return a list of devices connected through bluetooth")
    public List<BluetoothDevice> bluetoothGetConnectedDevices() {
        ArrayList<BluetoothDevice> results = new ArrayList<BluetoothDevice>();
        for (BluetoothDevice bd : mBluetoothAdapter.getBondedDevices()) {
            if (bd.isConnected()) {
                results.add(bd);
            }
        }
        return results;
    }

    /**
     * Return a list of service UUIDS supported by the bonded device.
     * @param macAddress the String mac address of the bonded device.
     *
     * @return the String list of supported UUIDS.
     * @throws Exception
     */
    @Rpc(description = "Return a list of service UUIDS supported by the bonded device")
    public List<String> bluetoothGetBondedDeviceUuids(
        @RpcParameter(name = "macAddress") String macAddress) throws Exception {
        BluetoothDevice mDevice = BluetoothFacade.getDevice(mBluetoothAdapter.getBondedDevices(),
                macAddress);
        ArrayList<String> uuidStrings = new ArrayList<>();
        for (ParcelUuid parcelUuid : mDevice.getUuids()) {
            uuidStrings.add(parcelUuid.toString());
        }
        return uuidStrings;
    }

    @Rpc(description = "Return a list of devices connected through bluetooth LE")
    public List<BluetoothDevice> bluetoothGetConnectedLeDevices(Integer profile) {
        return mBluetoothManager.getConnectedDevices(profile);
    }

    @Rpc(description = "Bluetooth init Bond by Mac Address")
    public boolean bluetoothBond(@RpcParameter(name = "macAddress") String macAddress) {
        mContext.registerReceiver(new BondBroadcastReceiver(),
                new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
        return mBluetoothAdapter.getRemoteDevice(macAddress).createBond();
    }

    @Rpc(description = "Bluetooth init LE Bond by Mac Address")
    public boolean bluetoothLeBond(@RpcParameter(name = "macAddress") String macAddress) {
        mContext.registerReceiver(new BondBroadcastReceiver(),
                new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
        return mBluetoothAdapter.getRemoteDevice(macAddress).createBond(BluetoothDevice.TRANSPORT_LE);
    }

    @Rpc(description = "Return true if a bluetooth device is connected.")
    public Boolean bluetoothIsDeviceConnected(String deviceID) {
        for (BluetoothDevice bd : mBluetoothAdapter.getBondedDevices()) {
            if (BluetoothFacade.deviceMatch(bd, deviceID)) {
                return bd.isConnected();
            }
        }
        return false;
    }

    /**
     * Generates the local Out of Band data for the given transport.
     */
    @Rpc(description = "Generate Out of Band data for OOB Pairing.")
    public void bluetoothGenerateLocalOobData(@RpcParameter(name = "transport") String transport) {
        Log.d("bluetoothGenerateLocalOobData(" + transport + ")");
        mBluetoothAdapter.generateLocalOobData(Integer.parseInt(transport),
                mContext.getMainExecutor(), mGenerateOobDataCallback);

    }

    private static byte[] hexStringToByteArray(String s) {
        if (s == null) {
            throw new IllegalArgumentException("Hex String must not be null!");
        }
        int len = s.length();
        if ((len % 2) != 0 || len < 1) { // Multiple of 2 or empty
            throw new IllegalArgumentException("Hex String must be an even number > 0");
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((byte) (Character.digit(s.charAt(i), 16) << 4)
                    + (byte) Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    private static String toHexString(byte[] a) {
        if (a == null) return null;
        StringBuilder builder = new StringBuilder(a.length * 2);
        for (byte b : a) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    /**
     * Bond to a device using Out of Band Data.
     *
     * @param address String representation of address like "00:11:22:33:44:55"
     * @param transport String "1", "2", "3" to match TRANSPORT_*
     * @param c Hex String of the 16 octet confirmation
     * @param r Hex String of the 16 octet randomizer
     */
    @Rpc(description = "Creates and Out of Band bond.")
    public void bluetoothCreateBondOutOfBand(@RpcParameter(name = "address") String address,
            @RpcParameter(name = "transport") String transport,
            @RpcParameter(name = "c") String c, @RpcParameter(name = "r") String r) {
        Log.d("bluetoothCreateBondOutOfBand(" + address + ", " + transport + "," + c + ", "
                + r + ")");
        BluetoothDevice remoteDevice = mBluetoothAdapter.getRemoteDevice(address);
        byte[] addressBytes = new byte[7];
        int i = 0;
        for (String s : address.split(":")) {
            addressBytes[i] = hexStringToByteArray(s)[0];
            i++;
        }
        addressBytes[i] = 0x01;
        OobData p192 = null;
        OobData p256 = new OobData.LeBuilder(hexStringToByteArray(c),
                addressBytes, OobData.LE_DEVICE_ROLE_BOTH_PREFER_CENTRAL)
                .setRandomizerHash(hexStringToByteArray(r))
                .build();
        mContext.registerReceiver(new BondBroadcastReceiver(),
                new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
        remoteDevice.createBondOutOfBand(Integer.parseInt(transport), p192, p256);
    }

    private class BondBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d("BondBroadcastReceiver onReceive(" + context + ", " + intent + ")");
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.BOND_NONE);
            if (state == BluetoothDevice.BOND_BONDED) {
                Bundle event = new Bundle();
                event.putBoolean("bonded_state", state == BluetoothDevice.BOND_BONDED);
                mEventFacade.postEvent("Bonded", event);
                mContext.unregisterReceiver(this);
            } else if (state == BluetoothDevice.BOND_NONE) {
                Bundle event = new Bundle();
                event.putBoolean("bonded_state", state == BluetoothDevice.BOND_BONDED);
                mEventFacade.postEvent("Unbonded", event);
                mContext.unregisterReceiver(this);
            }
        }
    }

    @Rpc(description = "Return list of connected bluetooth devices over a profile",
            returns = "List of devices connected over the profile")
    public List<BluetoothDevice> bluetoothGetConnectedDevicesOnProfile(
            @RpcParameter(name = "profileId",
                    description = "profileId same as BluetoothProfile")
                    Integer profileId) {
        BluetoothProfile profile = null;
        switch (profileId) {
            case BluetoothProfile.A2DP_SINK:
                return mA2dpSinkProfile.bluetoothA2dpSinkGetConnectedDevices();
            case BluetoothProfile.HEADSET_CLIENT:
                return mHfpClientProfile.bluetoothHfpClientGetConnectedDevices();
            case BluetoothProfile.PBAP_CLIENT:
                return mPbapClientProfile.bluetoothPbapClientGetConnectedDevices();
            case BluetoothProfile.MAP_CLIENT:
                return mMapClientProfile.bluetoothMapClientGetConnectedDevices();
            case BluetoothProfile.HID_HOST:
                return mHidProfile.bluetoothHidGetConnectedDevices();
            default:
                Log.w("Profile id " + profileId + " is not yet supported.");
                return new ArrayList<BluetoothDevice>();
        }
    }

    @Rpc(description = "Connect to a specified device once it's discovered.",
            returns = "Whether discovery started successfully.")
    public Boolean bluetoothDiscoverAndConnect(
            @RpcParameter(name = "deviceID",
                    description = "Name or MAC address of a bluetooth device.")
                    String deviceID) {
        mBluetoothAdapter.cancelDiscovery();
        if (listeningDevices.containsKey(deviceID)) {
            Log.d("This device is already in the process of discovery and connecting.");
            return true;
        }
        DiscoverConnectReceiver receiver = new DiscoverConnectReceiver(deviceID);
        listeningDevices.put("Connect" + deviceID, receiver);
        mService.registerReceiver(receiver, mDiscoverConnectFilter);
        return mBluetoothAdapter.startDiscovery();
    }

    @Rpc(description = "Bond to a specified device once it's discovered.",
            returns = "Whether discovery started successfully. ")
    public Boolean bluetoothDiscoverAndBond(
            @RpcParameter(name = "deviceID",
                    description = "Name or MAC address of a bluetooth device.")
                    String deviceID) {
        mBluetoothAdapter.cancelDiscovery();
        if (listeningDevices.containsKey(deviceID)) {
            Log.d("This device is already in the process of discovery and bonding.");
            return true;
        }
        if (BluetoothFacade.deviceExists(mBluetoothAdapter.getBondedDevices(), deviceID)) {
            Log.d("Device " + deviceID + " is already bonded.");
            mEventFacade.postEvent("Bond" + deviceID, mGoodNews);
            return true;
        }
        DiscoverBondReceiver receiver = new DiscoverBondReceiver(deviceID);
        if (listeningDevices.containsKey("Bond" + deviceID)) {
            mService.unregisterReceiver(listeningDevices.remove("Bond" + deviceID));
        }
        listeningDevices.put("Bond" + deviceID, receiver);
        mService.registerReceiver(receiver, mBondFilter);
        Log.d("Start discovery for bonding.");
        return mBluetoothAdapter.startDiscovery();
    }

    @Rpc(description = "Unbond a device.",
            returns = "Whether the device was successfully unbonded.")
    public Boolean bluetoothUnbond(
            @RpcParameter(name = "deviceID",
                    description = "Name or MAC address of a bluetooth device.")
                    String deviceID) throws Exception {
        BluetoothDevice mDevice = BluetoothFacade.getDevice(mBluetoothAdapter.getBondedDevices(),
                deviceID);
        mContext.registerReceiver(new BondBroadcastReceiver(),
                new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
        return mDevice.removeBond();
    }

    @Rpc(description = "Connect to a device that is already bonded.")
    public void bluetoothConnectBonded(
            @RpcParameter(name = "deviceID",
                    description = "Name or MAC address of a bluetooth device.")
                    String deviceID) throws Exception {
        BluetoothDevice mDevice = BluetoothFacade.getDevice(mBluetoothAdapter.getBondedDevices(),
                deviceID);
        connectProfile(mDevice, deviceID);
    }

    @Rpc(description = "Disconnect from a device that is already connected.")
    public void bluetoothDisconnectConnected(
            @RpcParameter(name = "deviceID",
                    description = "Name or MAC address of a bluetooth device.")
                    String deviceID) throws Exception {
        BluetoothDevice mDevice = BluetoothFacade.getDevice(mBluetoothAdapter.getBondedDevices(),
                deviceID);
        disconnectProfiles(mDevice, deviceID);
    }

    @Rpc(description = "Disconnect on a profile from a device that is already connected.")
    public void bluetoothDisconnectConnectedProfile(
            @RpcParameter(name = "deviceID",
                    description = "Name or MAC address of a bluetooth device.")
                    String deviceID,
            @RpcParameter(name = "profileSet",
                    description = "List of profiles to disconnect from.")
                    JSONArray profileSet
    ) throws Exception {
        BluetoothDevice mDevice = BluetoothFacade.getDevice(mBluetoothAdapter.getBondedDevices(),
                deviceID);
        disconnectProfiles(mDevice, deviceID, jsonArrayToIntegerList(profileSet));
    }

    @Rpc(description = "Change permissions for a profile.")
    public void bluetoothChangeProfileAccessPermission(
            @RpcParameter(name = "deviceID",
                    description = "Name or MAC address of a bluetooth device.")
                    String deviceID,
            @RpcParameter(name = "profileID",
                    description = "Number of Profile to change access permission")
                    Integer profileID,
            @RpcParameter(name = "access",
                    description = "Access level 0 = Unknown, 1 = Allowed, 2 = Rejected")
                    Integer access
    ) throws Exception {
        if (access < 0 || access > 2) {
            Log.w("Unsupported access level.");
            return;
        }
        BluetoothDevice mDevice = BluetoothFacade.getDevice(mBluetoothAdapter.getBondedDevices(),
                deviceID);
        switch (profileID) {
            case BluetoothProfile.PBAP:
                mDevice.setPhonebookAccessPermission(access);
                break;
            default:
                Log.w("Unsupported profile access change.");
        }
    }


    @Override
    public void shutdown() {
        for (BroadcastReceiver receiver : listeningDevices.values()) {
            try {
                mService.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ex) {
                Log.e("Failed to unregister " + ex);
            }
        }
        listeningDevices.clear();
        mService.unregisterReceiver(mPairingHelper);
    }
}

