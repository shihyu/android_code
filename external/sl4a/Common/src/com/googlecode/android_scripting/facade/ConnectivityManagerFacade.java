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

package com.googlecode.android_scripting.facade;

import static android.net.NetworkCapabilities.TRANSPORT_WIFI;

import android.app.Service;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStats.Bucket;
import android.app.usage.NetworkStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkPolicy;
import android.net.NetworkPolicyManager;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.Uri;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import com.android.modules.utils.build.SdkLevel;

import com.google.common.io.ByteStreams;
import com.googlecode.android_scripting.FileUtils;
import com.googlecode.android_scripting.Log;
import com.googlecode.android_scripting.facade.wifi.WifiAwareManagerFacade;
import com.googlecode.android_scripting.facade.wifi.WifiManagerFacade;
import com.googlecode.android_scripting.jsonrpc.RpcReceiver;
import com.googlecode.android_scripting.rpc.Rpc;
import com.googlecode.android_scripting.rpc.RpcOptional;
import com.googlecode.android_scripting.rpc.RpcParameter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;

/**
 * Access ConnectivityManager functions.
 */
public class ConnectivityManagerFacade extends RpcReceiver {

    public static int AIRPLANE_MODE_OFF = 0;
    public static int AIRPLANE_MODE_ON = 1;
    public static int DATA_ROAMING_ON = 1;

    private static HashMap<Long, Network> sNetworkHashMap = new HashMap<Long, Network>();

    class ConnectivityReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (!action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                Log.e("ConnectivityReceiver received non-connectivity action!");
                return;
            }

            Bundle b = intent.getExtras();

            if (b == null) {
                Log.e("ConnectivityReceiver failed to receive extras!");
                return;
            }

            int netType =
                    b.getInt(ConnectivityManager.EXTRA_NETWORK_TYPE,
                            ConnectivityManager.TYPE_NONE);

            if (netType == ConnectivityManager.TYPE_NONE) {
                Log.i("ConnectivityReceiver received change to TYPE_NONE.");
                return;
            }

            /*
             * Technically there is a race condition here, but retrieving the NetworkInfo from the
             * bundle is deprecated. See ConnectivityManager.EXTRA_NETWORK_INFO
             */
            for (NetworkInfo info : mManager.getAllNetworkInfo()) {
                if (info.getType() == netType) {
                    mEventFacade.postEvent(ConnectivityConstants.EventConnectivityChanged, info);
                }
            }
        }
    }

    /**
     * Used to dispatch to a different constructor depending on R or S, since
     * {@link ConnectivityManager.NetworkCallback#NetworkCallback(int)} was added in S.
     */
    private NetworkCallback newNetworkCallback(int events) {
        if (SdkLevel.isAtLeastS()) {
            return new NetworkCallback(events);
        } else {
            return new NetworkCallback(events, false);
        }
    }

    private class NetworkCallback extends ConnectivityManager.NetworkCallback {
        public static final int EVENT_INVALID = -1;
        public static final int EVENT_NONE = 0;
        public static final int EVENT_PRECHECK = 1 << 0;
        public static final int EVENT_AVAILABLE = 1 << 1;
        public static final int EVENT_LOSING = 1 << 2;
        public static final int EVENT_LOST = 1 << 3;
        public static final int EVENT_UNAVAILABLE = 1 << 4;
        public static final int EVENT_CAPABILITIES_CHANGED = 1 << 5;
        public static final int EVENT_SUSPENDED = 1 << 6;
        public static final int EVENT_RESUMED = 1 << 7;
        public static final int EVENT_LINK_PROPERTIES_CHANGED = 1 << 8;
        public static final int EVENT_BLOCKED_STATUS_CHANGED = 1 << 9;
        public static final int EVENT_ALL =
                EVENT_PRECHECK
                        | EVENT_AVAILABLE
                        | EVENT_LOSING
                        | EVENT_LOST
                        | EVENT_UNAVAILABLE
                        | EVENT_CAPABILITIES_CHANGED
                        | EVENT_SUSPENDED
                        | EVENT_RESUMED
                        | EVENT_LINK_PROPERTIES_CHANGED
                        | EVENT_BLOCKED_STATUS_CHANGED;

        private int mEvents;
        public String mId;
        private long mCreateTimestamp;

        /** Called in >= Android S where super(int) does exist. */
        private NetworkCallback(int events) {
            super(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO);
            init(events);
        }

        /**
         * Called in <= Android R where super(int) doesn't exist.
         * @param ignore placeholder argument to differentiate between R and S constructors' method
         *              signatures.
         */
        private NetworkCallback(int events, boolean ignore) {
            super();
            init(events);
        }

        private void init(int events) {
            mEvents = events;
            mId = this.toString();
            mCreateTimestamp = System.currentTimeMillis();
        }

        public void startListeningForEvents(int events) {
            mEvents |= events & EVENT_ALL;
        }

        public void stopListeningForEvents(int events) {
            mEvents &= ~(events & EVENT_ALL);
        }

        @Override
        public void onPreCheck(Network network) {
            Log.d("NetworkCallback onPreCheck");
            if ((mEvents & EVENT_PRECHECK) == EVENT_PRECHECK) {
                mEventFacade.postEvent(
                        ConnectivityConstants.EventNetworkCallback,
                    new ConnectivityEvents.NetworkCallbackEventBase(
                        mId,
                        getNetworkCallbackEventString(EVENT_PRECHECK), mCreateTimestamp));
            }
        }

        @Override
        public void onAvailable(Network network) {
            Log.d("NetworkCallback onAvailable");
            if ((mEvents & EVENT_AVAILABLE) == EVENT_AVAILABLE) {
                mEventFacade.postEvent(
                        ConnectivityConstants.EventNetworkCallback,
                    new ConnectivityEvents.NetworkCallbackEventBase(
                        mId,
                        getNetworkCallbackEventString(EVENT_AVAILABLE), mCreateTimestamp));
            }
        }

        @Override
        public void onLosing(Network network, int maxMsToLive) {
            Log.d("NetworkCallback onLosing");
            if ((mEvents & EVENT_LOSING) == EVENT_LOSING) {
                mEventFacade.postEvent(
                        ConnectivityConstants.EventNetworkCallback,
                    new ConnectivityEvents.NetworkCallbackEventOnLosing(
                        mId,
                        getNetworkCallbackEventString(EVENT_LOSING), mCreateTimestamp,
                        maxMsToLive));
            }
        }

        @Override
        public void onLost(Network network) {
            Log.d("NetworkCallback onLost");
            if ((mEvents & EVENT_LOST) == EVENT_LOST) {
                mEventFacade.postEvent(
                        ConnectivityConstants.EventNetworkCallback,
                    new ConnectivityEvents.NetworkCallbackEventBase(
                        mId,
                        getNetworkCallbackEventString(EVENT_LOST), mCreateTimestamp));
            }
        }

        @Override
        public void onUnavailable() {
            Log.d("NetworkCallback onUnavailable");
            if ((mEvents & EVENT_UNAVAILABLE) == EVENT_UNAVAILABLE) {
                mEventFacade.postEvent(
                        ConnectivityConstants.EventNetworkCallback,
                    new ConnectivityEvents.NetworkCallbackEventBase(
                        mId,
                        getNetworkCallbackEventString(EVENT_UNAVAILABLE), mCreateTimestamp));
            }
        }

        @Override
        public void onCapabilitiesChanged(Network network,
                NetworkCapabilities networkCapabilities) {
            Log.d("NetworkCallback onCapabilitiesChanged. RSSI:" +
                    networkCapabilities.getSignalStrength());
            if ((mEvents & EVENT_CAPABILITIES_CHANGED) == EVENT_CAPABILITIES_CHANGED) {
                mEventFacade.postEvent(
                        ConnectivityConstants.EventNetworkCallback,
                    new ConnectivityEvents.NetworkCallbackEventOnCapabilitiesChanged(
                        mId,
                        getNetworkCallbackEventString(EVENT_CAPABILITIES_CHANGED), mCreateTimestamp,
                        networkCapabilities));
            }
        }

        @Override
        public void onBlockedStatusChanged(Network network, boolean blocked) {
            Log.d("NetworkCallback onBlockedStatusChanged");
            if ((mEvents & EVENT_BLOCKED_STATUS_CHANGED) == EVENT_BLOCKED_STATUS_CHANGED) {
                mEventFacade.postEvent(
                        ConnectivityConstants.EventNetworkCallback,
                        new ConnectivityEvents.NetworkCallbackEventBase(
                            mId,
                            getNetworkCallbackEventString(EVENT_BLOCKED_STATUS_CHANGED),
                            mCreateTimestamp));
            }
        }

        @Override
        public void onNetworkSuspended(Network network) {
            Log.d("NetworkCallback onNetworkSuspended");
            if ((mEvents & EVENT_SUSPENDED) == EVENT_SUSPENDED) {
                mEventFacade.postEvent(
                        ConnectivityConstants.EventNetworkCallback,
                    new ConnectivityEvents.NetworkCallbackEventBase(
                        mId,
                        getNetworkCallbackEventString(EVENT_SUSPENDED), mCreateTimestamp));
            }
        }

        @Override
        public void onLinkPropertiesChanged(Network network,
                LinkProperties linkProperties) {
            Log.d("NetworkCallback onLinkPropertiesChanged");
            if ((mEvents & EVENT_LINK_PROPERTIES_CHANGED) == EVENT_LINK_PROPERTIES_CHANGED) {
                mEventFacade.postEvent(
                        ConnectivityConstants.EventNetworkCallback,
                        new ConnectivityEvents.NetworkCallbackEventOnLinkPropertiesChanged(mId,
                                getNetworkCallbackEventString(EVENT_LINK_PROPERTIES_CHANGED),
                                mCreateTimestamp,
                                linkProperties.getInterfaceName()));
            }
        }

        @Override
        public void onNetworkResumed(Network network) {
            Log.d("NetworkCallback onNetworkResumed");
            if ((mEvents & EVENT_RESUMED) == EVENT_RESUMED) {
                mEventFacade.postEvent(
                        ConnectivityConstants.EventNetworkCallback,
                    new ConnectivityEvents.NetworkCallbackEventBase(
                        mId,
                        getNetworkCallbackEventString(EVENT_RESUMED), mCreateTimestamp));
            }
        }
    }

    private static int getNetworkCallbackEvent(String event) {
        switch (event) {
            case ConnectivityConstants.NetworkCallbackPreCheck:
                return NetworkCallback.EVENT_PRECHECK;
            case ConnectivityConstants.NetworkCallbackAvailable:
                return NetworkCallback.EVENT_AVAILABLE;
            case ConnectivityConstants.NetworkCallbackLosing:
                return NetworkCallback.EVENT_LOSING;
            case ConnectivityConstants.NetworkCallbackLost:
                return NetworkCallback.EVENT_LOST;
            case ConnectivityConstants.NetworkCallbackUnavailable:
                return NetworkCallback.EVENT_UNAVAILABLE;
            case ConnectivityConstants.NetworkCallbackCapabilitiesChanged:
                return NetworkCallback.EVENT_CAPABILITIES_CHANGED;
            case ConnectivityConstants.NetworkCallbackSuspended:
                return NetworkCallback.EVENT_SUSPENDED;
            case ConnectivityConstants.NetworkCallbackResumed:
                return NetworkCallback.EVENT_RESUMED;
            case ConnectivityConstants.NetworkCallbackLinkPropertiesChanged:
                return NetworkCallback.EVENT_LINK_PROPERTIES_CHANGED;
            case ConnectivityConstants.NetworkCallbackBlockedStatusChanged:
                return NetworkCallback.EVENT_BLOCKED_STATUS_CHANGED;
        }
        return NetworkCallback.EVENT_INVALID;
    }

    private static String getNetworkCallbackEventString(int event) {
        switch (event) {
            case NetworkCallback.EVENT_PRECHECK:
                return ConnectivityConstants.NetworkCallbackPreCheck;
            case NetworkCallback.EVENT_AVAILABLE:
                return ConnectivityConstants.NetworkCallbackAvailable;
            case NetworkCallback.EVENT_LOSING:
                return ConnectivityConstants.NetworkCallbackLosing;
            case NetworkCallback.EVENT_LOST:
                return ConnectivityConstants.NetworkCallbackLost;
            case NetworkCallback.EVENT_UNAVAILABLE:
                return ConnectivityConstants.NetworkCallbackUnavailable;
            case NetworkCallback.EVENT_CAPABILITIES_CHANGED:
                return ConnectivityConstants.NetworkCallbackCapabilitiesChanged;
            case NetworkCallback.EVENT_SUSPENDED:
                return ConnectivityConstants.NetworkCallbackSuspended;
            case NetworkCallback.EVENT_RESUMED:
                return ConnectivityConstants.NetworkCallbackResumed;
            case NetworkCallback.EVENT_LINK_PROPERTIES_CHANGED:
                return ConnectivityConstants.NetworkCallbackLinkPropertiesChanged;
            case NetworkCallback.EVENT_BLOCKED_STATUS_CHANGED:
                return ConnectivityConstants.NetworkCallbackBlockedStatusChanged;
        }
        return ConnectivityConstants.NetworkCallbackInvalid;
    }

    /**
     * Callbacks used in ConnectivityManager to confirm tethering has started/failed.
     */
    class OnStartTetheringCallback extends ConnectivityManager.OnStartTetheringCallback {
        @Override
        public void onTetheringStarted() {
            mEventFacade.postEvent(ConnectivityConstants.TetheringStartedCallback, null);
        }

        @Override
        public void onTetheringFailed() {
            mEventFacade.postEvent(ConnectivityConstants.TetheringFailedCallback, null);
        }
    }

    private final ConnectivityManager mManager;
    private NetworkPolicyManager mNetPolicyManager;
    private NetworkStatsManager mNetStatsManager;
    private final Service mService;
    private final Context mContext;
    private final ConnectivityReceiver mConnectivityReceiver;
    private final EventFacade mEventFacade;
    private NetworkCallback mNetworkCallback;
    private TelephonyManager mTelephonyManager;
    private static HashMap<String, NetworkCallback> mNetworkCallbackMap =
            new HashMap<String, NetworkCallback>();
    private boolean mTrackingConnectivityStateChange;

    public ConnectivityManagerFacade(FacadeManager manager) {
        super(manager);
        mService = manager.getService();
        mContext = mService.getBaseContext();
        mManager = (ConnectivityManager) mService.getSystemService(Context.CONNECTIVITY_SERVICE);
        mNetPolicyManager = NetworkPolicyManager.from(mContext);
        mNetStatsManager = (NetworkStatsManager)
              mService.getSystemService(Context.NETWORK_STATS_SERVICE);
        mEventFacade = manager.getReceiver(EventFacade.class);
        mConnectivityReceiver = new ConnectivityReceiver();
        mTrackingConnectivityStateChange = false;
        mTelephonyManager = (TelephonyManager) mService.getSystemService(Context.TELEPHONY_SERVICE);
    }

    @Rpc(description = "Listen for connectivity changes")
    public void connectivityStartTrackingConnectivityStateChange() {
        if (!mTrackingConnectivityStateChange) {
            mTrackingConnectivityStateChange = true;
            mContext.registerReceiver(mConnectivityReceiver,
                    new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        }
    }

    @Rpc(description = "start listening for NetworkCallback Event")
    public Boolean connectivityNetworkCallbackStartListeningForEvent(String key, String eventString) {
        NetworkCallback mNetworkCallback = mNetworkCallbackMap.get(key);
        if (mNetworkCallback != null) {
            int event = getNetworkCallbackEvent(eventString);
            if (event == NetworkCallback.EVENT_INVALID) {
                return false;
            }
            mNetworkCallback.startListeningForEvents(event);
            return true;
        } else {
            return false;
        }
    }

    @Rpc(description = "stop listening for NetworkCallback Event")
    public Boolean connectivityNetworkCallbackStopListeningForEvent(String key, String eventString) {
        NetworkCallback mNetworkCallback = mNetworkCallbackMap.get(key);
        if (mNetworkCallback != null) {
            int event = getNetworkCallbackEvent(eventString);
            if (event == NetworkCallback.EVENT_INVALID) {
                return false;
            }
            mNetworkCallback.stopListeningForEvents(event);
            return true;
        } else {
            return false;
        }
    }

    @Rpc(description = "Set Rssi Threshold Monitor")
    public String connectivitySetRssiThresholdMonitor(Integer rssi) {
        Log.d("SL4A:setRssiThresholdMonitor rssi = " + rssi);
        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        builder.setSignalStrength((int) rssi);
        builder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        NetworkRequest networkRequest = builder.build();
        mNetworkCallback = newNetworkCallback(NetworkCallback.EVENT_ALL);
        mManager.registerNetworkCallback(networkRequest, mNetworkCallback);
        String key = mNetworkCallback.mId;
        mNetworkCallbackMap.put(key, mNetworkCallback);
        return key;
    }

    @Rpc(description = "Stop Rssi Threshold Monitor")
    public Boolean connectivityStopRssiThresholdMonitor(String key) {
        Log.d("SL4A:stopRssiThresholdMonitor key = " + key);
        return connectivityUnregisterNetworkCallback(key);
    }

    private NetworkRequest buildNetworkRequestFromJson(JSONObject configJson)
            throws JSONException {
        return makeNetworkRequestBuilderFromJson(configJson).build();
    }

    private NetworkRequest buildWifiAwareNetworkRequestFromJson(JSONObject configJson)
            throws JSONException {
        final NetworkRequest.Builder builder = makeNetworkRequestBuilderFromJson(configJson);
        if (configJson.has("NetworkSpecifier")) {
            final String strSpecifier = configJson.getString("NetworkSpecifier");
            Log.d("build NetworkSpecifier" + strSpecifier);
            final NetworkSpecifier specifier = WifiAwareManagerFacade.getNetworkSpecifier(
                    new JSONObject(strSpecifier));
            builder.setNetworkSpecifier(specifier);
        }
        return builder.build();
    }

    private NetworkRequest.Builder makeNetworkRequestBuilderFromJson(JSONObject configJson)
            throws JSONException {
        NetworkRequest.Builder builder = new NetworkRequest.Builder();

        if (configJson.has("ClearCapabilities")) {
            /* the 'ClearCapabilities' property does not have a value (that we use). Its presence
             is used to clear the capabilities of the constructed network request (which is
             constructed with some default capabilities already present). */
            Log.d("build ClearCapabilities");
            builder.clearCapabilities();
        }
        if (configJson.has(ConnectivityConstants.NET_CAPABILITIES_TRANSPORT_TYPE)) {
            Log.d("build TransportType"
                    + configJson.getInt(ConnectivityConstants.NET_CAPABILITIES_TRANSPORT_TYPE));
            builder.addTransportType(
                    configJson.getInt(ConnectivityConstants.NET_CAPABILITIES_TRANSPORT_TYPE));
        }
        if (configJson.has("SignalStrength")) {
            Log.d("build SignalStrength" + configJson.getInt("SignalStrength"));
            builder.setSignalStrength(configJson.getInt("SignalStrength"));
        }
        if (configJson.has(ConnectivityConstants.NET_CAPABILITIES_CAPABILITIES)) {
            JSONArray capabilities =
                    configJson.getJSONArray(ConnectivityConstants.NET_CAPABILITIES_CAPABILITIES);
            for (int i = 0; i < capabilities.length(); i++) {
                Log.d("build Capability" + capabilities.getInt(i));
                builder.addCapability(capabilities.getInt(i));
            }
        }
        if (configJson.has("LinkUpstreamBandwidthKbps")) {
            Log.d("build LinkUpstreamBandwidthKbps" + configJson.getInt(
                    "LinkUpstreamBandwidthKbps"));
            builder.setLinkUpstreamBandwidthKbps(configJson.getInt(
                    "LinkUpstreamBandwidthKbps"));
        }
        if (configJson.has("LinkDownstreamBandwidthKbps")) {
            Log.d("build LinkDownstreamBandwidthKbps" + configJson.getInt(
                    "LinkDownstreamBandwidthKbps"));
            builder.setLinkDownstreamBandwidthKbps(configJson.getInt(
                    "LinkDownstreamBandwidthKbps"));
        }
        return builder;
    }

    @Rpc(description = "register a network callback")
    public String connectivityRegisterNetworkCallback(@RpcParameter(name = "configJson")
    JSONObject configJson) throws JSONException {
        NetworkRequest networkRequest = buildNetworkRequestFromJson(configJson);
        mNetworkCallback = newNetworkCallback(NetworkCallback.EVENT_ALL);
        mManager.registerNetworkCallback(networkRequest, mNetworkCallback);
        String key = mNetworkCallback.mId;
        mNetworkCallbackMap.put(key, mNetworkCallback);
        return key;
    }

    @Rpc(description = "unregister a network callback")
    public Boolean connectivityUnregisterNetworkCallback(@RpcParameter(name = "key")
    String key) {
        mNetworkCallback = mNetworkCallbackMap.get(key);
        if (mNetworkCallback != null) {
            mNetworkCallbackMap.remove(key);
            mManager.unregisterNetworkCallback(mNetworkCallback);
            return true;
        } else {
            return false;
        }
    }

    @Rpc(description = "register a default network callback")
    public String connectivityRegisterDefaultNetworkCallback() {
        mNetworkCallback = newNetworkCallback(NetworkCallback.EVENT_ALL);
        mManager.registerDefaultNetworkCallback(mNetworkCallback);
        String key = mNetworkCallback.mId;
        mNetworkCallbackMap.put(key, mNetworkCallback);
        return key;
    }

    @Rpc(description = "request a network")
    public String connectivityRequestNetwork(@RpcParameter(name = "configJson")
    JSONObject configJson) throws JSONException {
        NetworkRequest networkRequest = buildNetworkRequestFromJson(configJson);
        mNetworkCallback = newNetworkCallback(NetworkCallback.EVENT_ALL);
        mManager.requestNetwork(networkRequest, mNetworkCallback);
        String key = mNetworkCallback.mId;
        mNetworkCallbackMap.put(key, mNetworkCallback);
        return key;
    }

    @Rpc(description = "Request a Wi-Fi Aware network")
    public String connectivityRequestWifiAwareNetwork(@RpcParameter(name = "configJson")
            JSONObject configJson) throws JSONException {
        NetworkRequest networkRequest = buildWifiAwareNetworkRequestFromJson(configJson);
        mNetworkCallback = newNetworkCallback(NetworkCallback.EVENT_ALL);
        mManager.requestNetwork(networkRequest, mNetworkCallback);
        String key = mNetworkCallback.mId;
        mNetworkCallbackMap.put(key, mNetworkCallback);
        return key;
    }

    /**
     * Initiates a network request {@link NetworkRequest} using {@link WifiNetworkSpecifier}.
     *
     * @param wNs JSONObject Dictionary of wifi network specifier parameters
     * @param timeoutInMs Timeout for the request. 0 indicates no timeout.
     * @throws JSONException
     * @throws GeneralSecurityException
     */
    @Rpc(description = "Initiates a network request using the provided network specifier")
    public String connectivityRequestWifiNetwork(
            @RpcParameter(name = "wifiNetworkSpecifier") JSONObject wNs,
            @RpcParameter(name = "timeoutInMS") Integer timeoutInMs)
            throws JSONException, GeneralSecurityException {
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI)
                .setNetworkSpecifier(WifiManagerFacade.genWifiNetworkSpecifier(wNs))
                .build();
        mNetworkCallback = newNetworkCallback(NetworkCallback.EVENT_ALL);
        if (timeoutInMs != 0) {
            mManager.requestNetwork(networkRequest, mNetworkCallback, timeoutInMs);
        } else {
            mManager.requestNetwork(networkRequest, mNetworkCallback);
        }
        String key = mNetworkCallback.mId;
        mNetworkCallbackMap.put(key, mNetworkCallback);
        return key;
    }

    @Rpc(description = "Stop listening for connectivity changes")
    public void connectivityStopTrackingConnectivityStateChange() {
        if (mTrackingConnectivityStateChange) {
            mTrackingConnectivityStateChange = false;
            mContext.unregisterReceiver(mConnectivityReceiver);
        }
    }

    @Rpc(description = "Get the extra information about the network state provided by lower network layers.")
    public String connectivityNetworkGetActiveConnectionExtraInfo() {
        NetworkInfo current = mManager.getActiveNetworkInfo();
        if (current == null) {
            Log.d("No network is active at the moment.");
            return null;
        }
        return current.getExtraInfo();
    }

    @Rpc(description = "Return the subtype name of the current network, null if not connected")
    public String connectivityNetworkGetActiveConnectionSubtypeName() {
        NetworkInfo current = mManager.getActiveNetworkInfo();
        if (current == null) {
            Log.d("No network is active at the moment.");
            return null;
        }
        return current.getSubtypeName();
    }

    @Rpc(description = "Return a human-readable name describe the type of the network, e.g. WIFI")
    public String connectivityNetworkGetActiveConnectionTypeName() {
        NetworkInfo current = mManager.getActiveNetworkInfo();
        if (current == null) {
            Log.d("No network is active at the moment.");
            return null;
        }
        return current.getTypeName();
    }

    @Rpc(description = "Get connection status information about all network types supported by the device.")
    public NetworkInfo[] connectivityNetworkGetAllInfo() {
        return mManager.getAllNetworkInfo();
    }

    @Rpc(description = "Get connection status information about all network types supported by the device.")
    public NetworkCapabilities[] connectivityNetworkGetAllCapabilities() {
        Network[] networks = mManager.getAllNetworks();
        NetworkCapabilities[] networkCapabilties = new NetworkCapabilities[networks.length];
        for (int i = 0; i < networks.length; i++) {
            networkCapabilties[i] = mManager.getNetworkCapabilities(networks[i]);
        }
        return networkCapabilties;
    }

    @Rpc(description = "Check whether the active network is connected to the Internet.")
    public Boolean connectivityNetworkIsConnected() {
        NetworkInfo current = mManager.getActiveNetworkInfo();
        if (current == null) {
            Log.d("No network is active at the moment.");
            return false;
        }
        return current.isConnected();
    }

    @Rpc(description = "Checks the airplane mode setting.",
            returns = "True if airplane mode is enabled.")
    public Boolean connectivityCheckAirplaneMode() {
        try {
            return Settings.Global.getInt(mService.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON) == AIRPLANE_MODE_ON;
        } catch (Settings.SettingNotFoundException e) {
            Log.e("Settings.Global.AIRPLANE_MODE_ON not found!");
            return false;
        }
    }

    @Rpc(description = "Toggles airplane mode on and off.",
            returns = "True if airplane mode is enabled.")
    public void connectivityToggleAirplaneMode(@RpcParameter(name = "enabled")
    @RpcOptional
    Boolean enabled) {
        if (enabled == null) {
            enabled = !connectivityCheckAirplaneMode();
        }
        mManager.setAirplaneMode(enabled);
    }

    /**
    * Check global data roaming setting.
    * @return True if roaming is enabled; false otherwise.
    */
    @Rpc(description = "Checks data roaming mode setting.",
            returns = "True if data roaming mode is enabled.")
    public Boolean connectivityCheckDataRoamingMode() {
        return mTelephonyManager.isDataRoamingEnabled();
    }

    /**
    * Enable or disable data roaming.
    * @param roaming 1: Enable data roaming; 0: Disable data roaming.
    * @return True for setting roaming mode successfully; false otherwise.
    */
    @Rpc(description = "Set Data Roaming Enabled or Disabled")
    public boolean connectivitySetDataRoaming(
            @RpcParameter(name = "roaming") Integer roaming) {
        Log.d("connectivitySetDataRoaming by SubscriptionManager");
        return Settings.Global.putInt(mService.getContentResolver(),
                    Settings.Global.DATA_ROAMING, roaming);
    }

    @Rpc(description = "Check if tethering supported or not.",
            returns = "True if tethering is supported.")
    public boolean connectivityIsTetheringSupported() {
        return mManager.isTetheringSupported();
    }

    @Rpc(description = "Call to start tethering with a provisioning check if needed")
    public void connectivityStartTethering(@RpcParameter(name = "type") Integer type,
            @RpcParameter(name = "showProvisioningUi") Boolean showProvisioningUi) {
        Log.d("startTethering for type: " + type + " showProvUi: " + showProvisioningUi);
        OnStartTetheringCallback tetherCallback = new OnStartTetheringCallback();
        mManager.startTethering(type, showProvisioningUi, tetherCallback);
    }

    @Rpc(description = "Call to stop tethering")
    public void connectivityStopTethering(@RpcParameter(name = "type") Integer type) {
        Log.d("stopTethering for type: " + type);
        mManager.stopTethering(type);
    }

    private Enumeration<InetAddress> getInetAddrsForInterface(String ifaceName) {
        NetworkInterface iface = null;
        try {
            iface = NetworkInterface.getByName(ifaceName);
        } catch (SocketException e) {
            return null;
        }

        if (iface == null)
            return null;
        return iface.getInetAddresses();
    }

    @Rpc(description = "Returns the link local IPv6 address of the interface.")
    public String connectivityGetLinkLocalIpv6Address(@RpcParameter(name = "ifaceName")
            String ifaceName) {
        Inet6Address inet6Address = null;
        Enumeration<InetAddress> inetAddresses = getInetAddrsForInterface(ifaceName);
        if (inetAddresses == null) {
            return null;
        }

        while (inetAddresses.hasMoreElements()) {
            InetAddress addr = inetAddresses.nextElement();
            if (addr instanceof Inet6Address) {
                if (((Inet6Address) addr).isLinkLocalAddress()) {
                    inet6Address = (Inet6Address) addr;
                    break;
                }
            }
        }

        if (inet6Address == null) {
            return null;
        }

        return inet6Address.getHostAddress();
    }

    @Rpc(description = "Return IPv4 address of an interface")
    public List<String> connectivityGetIPv4Addresses(
            @RpcParameter(name = "ifaceName") String ifaceName) {
        Enumeration<InetAddress> inetAddresses
                = getInetAddrsForInterface(ifaceName);
        if (inetAddresses == null)
            return null;

        List<String> inetAddrs = new ArrayList<String>();
        while (inetAddresses.hasMoreElements()) {
            InetAddress addr = inetAddresses.nextElement();
            if (addr instanceof Inet4Address) {
                Inet4Address inet4Address =  (Inet4Address) addr;
                inetAddrs.add(inet4Address.getHostAddress());
            }
        }

        return inetAddrs;
    }

    @Rpc(description = "Return IPv6 addrs of an interface except link local")
    public List<String> connectivityGetIPv6Addresses(
            @RpcParameter(name = "ifaceName") String ifaceName) {
        Enumeration<InetAddress> inetAddresses
                = getInetAddrsForInterface(ifaceName);
        if (inetAddresses == null)
            return null;

        List<String> inetAddrs = new ArrayList<String>();
        while (inetAddresses.hasMoreElements()) {
            InetAddress addr = inetAddresses.nextElement();
            if (addr instanceof Inet6Address) {
                if (((Inet6Address) addr).isLinkLocalAddress())
                    continue;
                Inet6Address inet6Address =  (Inet6Address) addr;
                inetAddrs.add(inet6Address.getHostAddress());
            }
        }

        return inetAddrs;
    }

    @Rpc(description = "Returns active link properties")
    public LinkProperties connectivityGetActiveLinkProperties() {
        return mManager.getActiveLinkProperties();
    }

    /**
     * Get the IPv4 default gateway for the active network.
     */
    @Rpc(description = "Return default gateway of the active network")
    public String connectivityGetIPv4DefaultGateway() {
        LinkProperties linkProp = mManager.getActiveLinkProperties();
        List<RouteInfo> routeInfos = linkProp.getRoutes();
        for (RouteInfo routeInfo: routeInfos) {
            if (routeInfo.isIPv4Default()) {
                return routeInfo.getGateway().toString().split("/")[1];
            }
        }
        return null;
    }

    @Rpc(description = "Returns all IP addresses of the active link")
    public List<InetAddress> connectivityGetAllAddressesOfActiveLink() {
        LinkProperties linkProp = mManager.getActiveLinkProperties();
        return linkProp.getAllAddresses();
    }

    @Rpc(description = "Check if active link has default IPv6 route")
    public boolean connectivityHasIPv6DefaultRoute() {
        LinkProperties linkProp = mManager.getActiveLinkProperties();
        return linkProp.hasIPv6DefaultRoute();
    }

    @Rpc(description = "Factory reset of network policies")
    public void connectivityFactoryResetNetworkPolicies(String subscriberId) {
        mNetPolicyManager.factoryReset(subscriberId);
    }

    /**
     * Method to set data warning limit on the device.
     */
    @Rpc(description = "Set data warning limit for subscriber ID")
    public void connectivitySetDataWarningLimit(String subscriberId, Long dataLimit) {
        NetworkPolicy[] allPolicies = mNetPolicyManager.getNetworkPolicies();
        for (int i = 0; i < allPolicies.length; i++) {
            String subId = allPolicies[i].template.getSubscriberId();
            if (subId != null && subId.equals(subscriberId)) {
                allPolicies[i].warningBytes = dataLimit.longValue();
                break;
            }
        }
        mNetPolicyManager.setNetworkPolicies(allPolicies);
    }

    /**
     * Method to set data usage limit on the device.
     */
    @Rpc(description = "Set data usage limit for subscriber ID")
    public void connectivitySetDataUsageLimit(String subscriberId, Long dataLimit) {
        NetworkPolicy[] allPolicies = mNetPolicyManager.getNetworkPolicies();
        for (int i = 0; i < allPolicies.length; i++) {
            String subId = allPolicies[i].template.getSubscriberId();
            if (subId != null && subId.equals(subscriberId)) {
                allPolicies[i].limitBytes = dataLimit.longValue();
                break;
            }
        }
        mNetPolicyManager.setNetworkPolicies(allPolicies);
    }

    /**
     * Method to get data usage limit on the device.
     */
    @Rpc(description = "Get data usage limit for subscriber ID")
    public long connectivityGetDataUsageLimit(String subscriberId) {
        NetworkPolicy[] allPolicies = mNetPolicyManager.getNetworkPolicies();
        for (int i = 0; i < allPolicies.length; i++) {
            String subId = allPolicies[i].template.getSubscriberId();
            if (subId != null && subId.equals(subscriberId)) return allPolicies[i].limitBytes;
        }
        return -1;
    }

    /**
     * Method to get data warning limit on the device
     */
    @Rpc(description = "Get data warning limit for subscriber ID")
    public long connectivityGetDataWarningLimit(String subscriberId) {
        NetworkPolicy[] allPolicies = mNetPolicyManager.getNetworkPolicies();
        for (int i = 0; i < allPolicies.length; i++) {
            String subId = allPolicies[i].template.getSubscriberId();
            if (subId != null && subId.equals(subscriberId)) return allPolicies[i].warningBytes;
        }
        return -1;
    }

    @Rpc(description = "Get network stats for device")
    public long connectivityQuerySummaryForDevice(Integer connType,
            String subscriberId, Long startTime, Long endTime)
            throws SecurityException, RemoteException {
        Bucket bucket = mNetStatsManager.querySummaryForDevice(
              connType, subscriberId, startTime, endTime);
        return bucket.getTxBytes() + bucket.getRxBytes();
    }

    @Rpc(description = "Get network stats for device - Rx bytes")
    public long connectivityQuerySummaryForDeviceRxBytes(Integer connType,
            String subscriberId, Long startTime, Long endTime)
            throws SecurityException, RemoteException {
        Bucket bucket = mNetStatsManager.querySummaryForDevice(
              connType, subscriberId, startTime, endTime);
        return bucket.getRxBytes();
    }

    @Rpc(description = "Get network stats for UID")
    public long connectivityQueryDetailsForUid(Integer connType,
            String subscriberId, Long startTime, Long endTime, Integer uid)
            throws SecurityException, RemoteException {
        long totalData = 0;
        NetworkStats netStats = mNetStatsManager.queryDetailsForUid(
                connType, subscriberId, startTime, endTime, uid);
        Bucket bucket = new Bucket();
        while(netStats.hasNextBucket() && netStats.getNextBucket(bucket)) {
            totalData += bucket.getTxBytes() + bucket.getRxBytes();
        }
        netStats.close();
        return totalData;
    }

    @Rpc(description = "Get network stats for UID - Rx bytes")
    public long connectivityQueryDetailsForUidRxBytes(Integer connType,
            String subscriberId, Long startTime, Long endTime, Integer uid)
            throws SecurityException, RemoteException {
        long rxBytes = 0;
        NetworkStats netStats = mNetStatsManager.queryDetailsForUid(
                connType, subscriberId, startTime, endTime, uid);
        Bucket bucket = new Bucket();
        while(netStats.hasNextBucket() && netStats.getNextBucket(bucket)) {
            rxBytes += bucket.getRxBytes();
        }
        netStats.close();
        return rxBytes;
    }

    @Rpc(description = "Returns all interfaces on the android deivce")
    public List<NetworkInterface> connectivityGetNetworkInterfaces() {
        List<NetworkInterface> interfaces = null;
        try {
            interfaces = Collections.list(
                  NetworkInterface.getNetworkInterfaces());
        } catch (SocketException e) {
            return null;
        };

        return interfaces;
    }

    /**
    * Get multipath preference for a given network.
    * @param networkId : network id of wifi or cell network
    * @return Integer value of multipath preference
    */
    @Rpc(description = "Return Multipath preference for a given network")
    public Integer connectivityGetMultipathPreferenceForNetwork(Long networkId) {
        Network network = sNetworkHashMap.get(networkId.longValue());
        return mManager.getMultipathPreference(network);
    }

    /**
    * Return HashMap key for Network object.
    * @return long value of Network object key
    */
    @Rpc(description = "Return key to active network stored in a hash map")
    public long connectivityGetActiveNetwork() {
        Network network = mManager.getActiveNetwork();
        long id = network.getNetworkHandle();
        sNetworkHashMap.put(id, network);
        return id;
    }

    /**
    * Get mutlipath preference for active network.
    * @return Integer value of multipath preference
    */
    @Rpc(description = "Return Multipath preference for active network")
    public Integer connectivityGetMultipathPreference() {
        Network network = mManager.getActiveNetwork();
        return mManager.getMultipathPreference(network);
    }

    /**
    * Download file of a given url using Network#openConnection call.
    * @param networkId : network id of wifi or cell network
    * @param urlString : url in String format
    */
    @Rpc(description = "Download file on a given network with Network#openConnection")
    public void connectivityNetworkOpenConnection(Long networkId, String urlString) {
        Network network = sNetworkHashMap.get(networkId.longValue());
        try {
            URL url = new URL(urlString);
            URLConnection urlConnection = network.openConnection(url);
            File outFile = FileUtils.getExternalDownload();
            int lastIdx = urlString.lastIndexOf('/');
            String filename = urlString.substring(lastIdx + 1);
            Log.d("Using name from url: " + filename);
            outFile = new File(outFile, filename);
            InputStream in = new BufferedInputStream(urlConnection.getInputStream());
            OutputStream output = new FileOutputStream(outFile);
            ByteStreams.copy(in, output);
        } catch (IOException e) {
            Log.e("Failed to download file: " + e.toString());
        }
    }

    /**
     * Sets the global proxy using the given information.
     *
     * @param hostname hostname of the proxy
     * @param port     port set on the proxy server
     * @param exclList List of hostnames excluded
     */
    @Rpc(description = "Set global proxy")
    public void connectivitySetGlobalProxy(String hostname, Integer port, String exclList) {
        ProxyInfo proxyInfo = new ProxyInfo(hostname, port.intValue(), exclList);
        mManager.setGlobalProxy(proxyInfo);
    }

    /**
     * Sets the global proxy using a PAC URI.
     *
     * @param pac PAC URI in string
     */
    @Rpc(description = "Set global proxy with proxy autoconfig")
    public void connectivitySetGlobalPacProxy(String pac) {
        Uri uri = Uri.parse(pac);
        ProxyInfo proxyInfo = new ProxyInfo(uri);
        mManager.setGlobalProxy(proxyInfo);
    }

    /**
     * Gets the global proxy settings.
     *
     * @return ProxyInfo object in dictionary
     */
    @Rpc(description = "Get global proxy")
    public ProxyInfo connectivityGetGlobalProxy() {
        ProxyInfo proxyInfo = mManager.getGlobalProxy();
        if (proxyInfo == null) return null;
        return proxyInfo;
    }

    /**
     * Resets the global proxy settings.
     */
    @Rpc(description = "Reset global proxy")
    public void connectivityResetGlobalProxy() {
        mManager.setGlobalProxy(null);
    }

    /**
     * Check if active network is metered.
     */
    @Rpc(description = "Is active network metered")
    public boolean connectivityIsActiveNetworkMetered() {
        return mManager.isActiveNetworkMetered();
    }

    /**
     * Check if device connected to WiFi network
     */
    @Rpc(description = "Is active network WiFi")
    public boolean connectivityIsActiveNetworkWiFi() {
        NetworkInfo mWifi = mManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        return mWifi.isConnected();
    }

    @Override
    public void shutdown() {
        connectivityStopTrackingConnectivityStateChange();
        for (NetworkCallback networkCallback : mNetworkCallbackMap.values()) {
            mManager.unregisterNetworkCallback(networkCallback);
        }
    }
}
