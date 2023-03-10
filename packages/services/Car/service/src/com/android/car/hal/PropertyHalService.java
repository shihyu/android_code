/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.car.hal;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import static java.lang.Integer.toHexString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.VehiclePropertyIds;
import android.car.builtin.os.BuildHelper;
import android.car.builtin.util.Slogf;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.CarPropertyManager;
import android.hardware.automotive.vehicle.VehiclePropError;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.os.ServiceSpecificException;
import android.util.Pair;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.CarServiceUtils;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Common interface for HAL services that send Vehicle Properties back and forth via ICarProperty.
 * Services that communicate by passing vehicle properties back and forth via ICarProperty should
 * extend this class.
 */
public class PropertyHalService extends HalServiceBase {
    private final boolean mDbg = true;
    private final LinkedList<CarPropertyEvent> mEventsToDispatch = new LinkedList<>();
    // Use SparseArray to save memory.
    @GuardedBy("mLock")
    private final SparseArray<CarPropertyConfig<?>> mMgrPropIdToCarPropConfig = new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseArray<HalPropConfig> mHalPropIdToPropConfig =
            new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseArray<Pair<String, String>> mMgrPropIdToPermissions = new SparseArray<>();
    // Only contains propId if the property Id is different in HAL and manager
    private static final Map<Integer, Integer> PROPERTY_ID_HAL_TO_MANAGER = Map.of(
            VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS,
            VehiclePropertyIds.VEHICLE_SPEED_DISPLAY_UNITS);
    // Only contains propId if the property Id is different in HAL and manager
    private static final Map<Integer, Integer> PROPERTY_ID_MANAGER_TO_HAL = Map.of(
            VehiclePropertyIds.VEHICLE_SPEED_DISPLAY_UNITS,
            VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS);
    private static final String TAG = CarLog.tagFor(PropertyHalService.class);
    private final VehicleHal mVehicleHal;
    private final PropertyHalServiceIds mPropIds;
    private final HalPropValueBuilder mPropValueBuilder;

    @GuardedBy("mLock")
    private PropertyHalListener mListener;
    @GuardedBy("mLock")
    private Set<Integer> mSubscribedHalPropIds;

    private final Object mLock = new Object();

    /**
     * Converts manager property ID to Vehicle HAL property ID.
     */
    private static int managerToHalPropId(int mgrPropId) {
        return PROPERTY_ID_MANAGER_TO_HAL.getOrDefault(mgrPropId, mgrPropId);
    }

    /**
     * Converts Vehicle HAL property ID to manager property ID.
     */
    private static int halToManagerPropId(int halPropId) {
        return PROPERTY_ID_HAL_TO_MANAGER.getOrDefault(halPropId, halPropId);
    }

    // Checks if the property exists in this VHAL before calling methods in IVehicle.
    private boolean isPropertySupportedInVehicle(int halPropId) {
        synchronized (mLock) {
            return mHalPropIdToPropConfig.contains(halPropId);
        }
    }

    /**
     * PropertyHalListener used to send events to CarPropertyService
     */
    public interface PropertyHalListener {
        /**
         * This event is sent whenever the property value is updated
         * @param events
         */
        void onPropertyChange(List<CarPropertyEvent> events);
        /**
         * This event is sent when the set property call fails
         * @param property
         * @param area
         */
        void onPropertySetError(int property, int area,
                @CarPropertyManager.CarSetPropertyErrorCode int errorCode);

    }

    public PropertyHalService(VehicleHal vehicleHal) {
        mPropIds = new PropertyHalServiceIds();
        mSubscribedHalPropIds = new HashSet<Integer>();
        mVehicleHal = vehicleHal;
        if (mDbg) {
            Slogf.d(TAG, "started PropertyHalService");
        }
        mPropValueBuilder = vehicleHal.getHalPropValueBuilder();
    }

    /**
     * Set the listener for the HAL service
     * @param listener
     */
    public void setListener(PropertyHalListener listener) {
        synchronized (mLock) {
            mListener = listener;
        }
    }

    /**
     *
     * @return SparseArray<CarPropertyConfig> List of configs available.
     */
    public SparseArray<CarPropertyConfig<?>> getPropertyList() {
        if (mDbg) {
            Slogf.d(TAG, "getPropertyList");
        }
        synchronized (mLock) {
            if (mMgrPropIdToCarPropConfig.size() == 0) {
                for (int i = 0; i < mHalPropIdToPropConfig.size(); i++) {
                    HalPropConfig p = mHalPropIdToPropConfig.valueAt(i);
                    int mgrPropId = halToManagerPropId(p.getPropId());
                    CarPropertyConfig config = p.toCarPropertyConfig(mgrPropId);
                    mMgrPropIdToCarPropConfig.put(mgrPropId, config);
                }
            }
            return mMgrPropIdToCarPropConfig;
        }
    }

    /**
     * Returns property or null if property is not ready yet.
     * @param mgrPropId property id in {@link VehiclePropertyIds}
     * @param areaId area id
     * @throws IllegalArgumentException if argument is not valid.
     * @throws ServiceSpecificException if there is an exception in HAL.
     */
    @Nullable
    public CarPropertyValue getProperty(int mgrPropId, int areaId)
            throws IllegalArgumentException, ServiceSpecificException {
        int halPropId = managerToHalPropId(mgrPropId);
        if (!isPropertySupportedInVehicle(halPropId)) {
            throw new IllegalArgumentException("Invalid property Id : 0x" + toHexString(mgrPropId));
        }

        // CarPropertyManager catches and rethrows exception, no need to handle here.
        HalPropValue value = mVehicleHal.get(halPropId, areaId);
        if (value == null) {
            return null;
        }
        HalPropConfig propConfig;
        synchronized (mLock) {
            propConfig = mHalPropIdToPropConfig.get(halPropId);
        }
        return value.toCarPropertyValue(mgrPropId, propConfig);
    }

    /**
     * Return property or null if property is not ready yet or there is an exception in HAL.
     */
    @Nullable
    public CarPropertyValue getPropertySafe(int mgrPropId, int areaId) {
        try {
            return getProperty(mgrPropId, areaId);
        } catch (Exception e) {
            Slogf.w(TAG, "get property value failed for property id: 0x "
                    + toHexString(mgrPropId) + " area id: 0x" + toHexString(areaId)
                    + " exception: " + e);
            return null;
        }
    }

    /**
     * Returns sample rate for the property
     * @param mgrPropId
     */
    public float getSampleRate(int mgrPropId) {
        int halPropId = managerToHalPropId(mgrPropId);
        if (!isPropertySupportedInVehicle(halPropId)) {
            throw new IllegalArgumentException("Invalid property Id : 0x" + toHexString(mgrPropId));
        }
        return mVehicleHal.getSampleRate(halPropId);
    }

    /**
     * Get the read permission string for the property.
     * @param mgrPropId
     */
    @Nullable
    public String getReadPermission(int mgrPropId) {
        int halPropId = managerToHalPropId(mgrPropId);
        return mPropIds.getReadPermission(halPropId);
    }

    /**
     * Get the write permission string for the property.
     * @param mgrPropId
     */
    @Nullable
    public String getWritePermission(int mgrPropId) {
        int halPropId = managerToHalPropId(mgrPropId);
        return mPropIds.getWritePermission(halPropId);
    }

    /**
     * Get permissions for all properties in the vehicle.
     * @return a SparseArray. key: propertyId, value: Pair(readPermission, writePermission).
     */
    @NonNull
    public SparseArray<Pair<String, String>> getPermissionsForAllProperties() {
        synchronized (mLock) {
            if (mMgrPropIdToPermissions.size() != 0) {
                return mMgrPropIdToPermissions;
            }
            for (int i = 0; i < mHalPropIdToPropConfig.size(); i++) {
                int halPropId = mHalPropIdToPropConfig.keyAt(i);
                mMgrPropIdToPermissions.put(halToManagerPropId(halPropId),
                        new Pair<>(mPropIds.getReadPermission(halPropId),
                                mPropIds.getWritePermission(halPropId)));
            }
            return mMgrPropIdToPermissions;
        }
    }

    /**
     * Return true if property is a display_units property
     * @param mgrPropId
     */
    public boolean isDisplayUnitsProperty(int mgrPropId) {
        int halPropId = managerToHalPropId(mgrPropId);
        return mPropIds.isPropertyToChangeUnits(halPropId);
    }

    /**
     * Set the property value.
     * @param prop
     *
     * @throws IllegalArgumentException if argument is invalid.
     * @throws ServiceSpecificException if there is an exception in HAL.
     */
    public void setProperty(CarPropertyValue prop)
            throws IllegalArgumentException, ServiceSpecificException {
        int halPropId = managerToHalPropId(prop.getPropertyId());
        if (!isPropertySupportedInVehicle(halPropId)) {
            throw new IllegalArgumentException("Invalid property Id : 0x"
                    + toHexString(prop.getPropertyId()));
        }
        HalPropConfig propConfig;
        synchronized (mLock) {
            propConfig = mHalPropIdToPropConfig.get(halPropId);
        }
        HalPropValue halPropValue = mPropValueBuilder.build(prop, halPropId, propConfig);
        // CarPropertyManager catches and rethrows exception, no need to handle here.
        mVehicleHal.set(halPropValue);
    }

    /**
     * Subscribe to this property at the specified update rate.
     * @param mgrPropId
     * @param rate
     *
     * @throws IllegalArgumentException thrown if property is not supported by VHAL.
     */
    public void subscribeProperty(int mgrPropId, float rate) throws IllegalArgumentException {
        if (mDbg) {
            Slogf.d(TAG, "subscribeProperty propId=0x" + toHexString(mgrPropId) + ", rate=" + rate);
        }
        int halPropId = managerToHalPropId(mgrPropId);
        if (!isPropertySupportedInVehicle(halPropId)) {
            throw new IllegalArgumentException("Invalid property Id : 0x"
                    + toHexString(mgrPropId));
        }
        synchronized (mLock) {
            HalPropConfig cfg = mHalPropIdToPropConfig.get(halPropId);
            if (rate > cfg.getMaxSampleRate()) {
                rate = cfg.getMaxSampleRate();
            } else if (rate < cfg.getMinSampleRate()) {
                rate = cfg.getMinSampleRate();
            }
            mSubscribedHalPropIds.add(halPropId);
        }

        mVehicleHal.subscribeProperty(this, halPropId, rate);
    }

    /**
     * Unsubscribe the property and turn off update events for it.
     * @param mgrPropId
     */
    public void unsubscribeProperty(int mgrPropId) {
        if (mDbg) {
            Slogf.d(TAG, "unsubscribeProperty propId=0x" + toHexString(mgrPropId));
        }
        int halPropId = managerToHalPropId(mgrPropId);
        if (!isPropertySupportedInVehicle(halPropId)) {
            throw new IllegalArgumentException("Invalid property Id : 0x"
                    + toHexString(mgrPropId));
        }
        synchronized (mLock) {
            if (mSubscribedHalPropIds.contains(halPropId)) {
                mSubscribedHalPropIds.remove(halPropId);
                mVehicleHal.unsubscribeProperty(this, halPropId);
            }
        }
    }

    @Override
    public void init() {
        if (mDbg) {
            Slogf.d(TAG, "init()");
        }
    }

    @Override
    public void release() {
        if (mDbg) {
            Slogf.d(TAG, "release()");
        }
        synchronized (mLock) {
            for (Integer halProp : mSubscribedHalPropIds) {
                mVehicleHal.unsubscribeProperty(this, halProp);
            }
            mSubscribedHalPropIds.clear();
            mHalPropIdToPropConfig.clear();
            mMgrPropIdToCarPropConfig.clear();
            mMgrPropIdToPermissions.clear();
            mListener = null;
        }
    }

    @Override
    public boolean isSupportedProperty(int propId) {
        return mPropIds.isSupportedProperty(propId);
    }

    @Override
    public int[] getAllSupportedProperties() {
        return CarServiceUtils.EMPTY_INT_ARRAY;
    }

    // The method is called in HAL init(). Avoid handling complex things in here.
    @Override
    public void takeProperties(Collection<HalPropConfig> allProperties) {
        for (HalPropConfig p : allProperties) {
            int propId = p.getPropId();
            if (mPropIds.isSupportedProperty(propId)) {
                synchronized (mLock) {
                    mHalPropIdToPropConfig.put(propId, p);
                }
                if (mDbg) {
                    Slogf.d(TAG, "takeSupportedProperties: " + toHexString(propId));
                }
            }
        }
        if (mDbg) {
            Slogf.d(TAG, "takeSupportedProperties() took " + allProperties.size()
                    + " properties");
        }
        // If vehicle hal support to select permission for vendor properties.
        HalPropConfig customizePermission;
        synchronized (mLock) {
            customizePermission = mHalPropIdToPropConfig.get(
                    VehicleProperty.SUPPORT_CUSTOMIZE_VENDOR_PERMISSION);
        }
        if (customizePermission != null) {
            mPropIds.customizeVendorPermission(customizePermission.getConfigArray());
        }
    }

    @Override
    public void onHalEvents(List<HalPropValue> values) {
        PropertyHalListener listener;
        synchronized (mLock) {
            listener = mListener;
        }
        if (listener != null) {
            for (HalPropValue v : values) {
                if (v == null) {
                    continue;
                }
                int propId = v.getPropId();
                if (!isPropertySupportedInVehicle(propId)) {
                    Slogf.w(TAG, "Property is not supported: 0x" + toHexString(propId));
                    continue;
                }
                // Check payload if it is a userdebug build.
                if (BuildHelper.isDebuggableBuild() && !mPropIds.checkPayload(v)) {
                    Slogf.w(TAG, "Drop event for property: " + v + " because it is failed "
                            + "in payload checking.");
                    continue;
                }
                int mgrPropId = halToManagerPropId(propId);
                HalPropConfig propConfig;
                synchronized (mLock) {
                    propConfig = mHalPropIdToPropConfig.get(propId);
                }
                CarPropertyValue<?> propVal = v.toCarPropertyValue(mgrPropId, propConfig);
                CarPropertyEvent event = new CarPropertyEvent(
                        CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE, propVal);
                mEventsToDispatch.add(event);
            }
            listener.onPropertyChange(mEventsToDispatch);
            mEventsToDispatch.clear();
        }
    }

    @Override
    public void onPropertySetError(ArrayList<VehiclePropError> errors) {
        PropertyHalListener listener;
        synchronized (mLock) {
            listener = mListener;
        }
        if (listener != null) {
            for (VehiclePropError error : errors) {
                int mgrPropId = halToManagerPropId(error.propId);
                listener.onPropertySetError(mgrPropId, error.areaId, error.errorCode);
            }
        }
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(PrintWriter writer) {
        writer.println(TAG);
        writer.println("  Properties available:");
        synchronized (mLock) {
            for (int i = 0; i < mHalPropIdToPropConfig.size(); i++) {
                HalPropConfig p = mHalPropIdToPropConfig.valueAt(i);
                writer.println("    " + p.toString());
            }
        }
    }
}
