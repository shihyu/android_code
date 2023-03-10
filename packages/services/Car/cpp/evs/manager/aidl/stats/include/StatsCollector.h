/*
 * Copyright 2022 The Android Open Source Project
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

#ifndef CPP_EVS_MANAGER_AIDL_STATS_INCLUDE_STATSCOLLECTOR_H
#define CPP_EVS_MANAGER_AIDL_STATS_INCLUDE_STATSCOLLECTOR_H

#include "CameraUsageStats.h"
#include "LooperWrapper.h"

#include <android-base/chrono_utils.h>
#include <android-base/logging.h>
#include <android-base/result.h>
#include <utils/Mutex.h>

#include <deque>
#include <thread>
#include <unordered_map>
#include <vector>

namespace aidl::android::automotive::evs::implementation {

class HalCamera;  // From HalCamera.h

enum CollectionEvent {
    INIT = 0,
    PERIODIC,
    CUSTOM_START,
    CUSTOM_END,
    TERMINATED,
};

struct CollectionRecord {
    // Latest statistics collection
    CameraUsageStatsRecord latest = {};

    // History of collected statistics records
    std::deque<CameraUsageStatsRecord> history;
};

struct CollectionInfo {
    // Collection interval between two subsequent collections
    std::chrono::nanoseconds interval = 0ns;

    // The maximum number of records this collection stores
    size_t maxCacheSize = 0;

    // Time when the latest collection was done
    nsecs_t lastCollectionTime = 0;

    // Collected statistics records per instances
    std::unordered_map<std::string, CollectionRecord> records;
};

class StatsCollector : public ::android::MessageHandler {
public:
    explicit StatsCollector() :
          mLooper(new LooperWrapper()),
          mCurrentCollectionEvent(CollectionEvent::INIT),
          mPeriodicCollectionInfo({}),
          mCustomCollectionInfo({}) {}

    virtual ~StatsCollector() { stopCollection(); }

    // Starts collecting CameraUsageStats
    ::android::base::Result<void> startCollection();

    // Stops collecting the statistics
    ::android::base::Result<void> stopCollection();

    // Starts collecting CameraUsageStarts during a given duration at a given
    // interval.
    ::android::base::Result<void> startCustomCollection(std::chrono::nanoseconds interval,
                                                        std::chrono::nanoseconds duration)
            EXCLUDES(mMutex);

    // Stops current custom collection and shows the result from the device with
    // a given unique id.  If this is "all",all results
    // will be returned.
    ::android::base::Result<std::string> stopCustomCollection(const std::string& id = "")
            EXCLUDES(mMutex);

    // Registers HalCamera object to monitor
    ::android::base::Result<void> registerClientToMonitor(const std::shared_ptr<HalCamera>& camera)
            EXCLUDES(mMutex);

    // Unregister HalCamera object
    ::android::base::Result<void> unregisterClientToMonitor(const std::string& id) EXCLUDES(mMutex);

    // Returns a string that contains the latest statistics pulled from
    // currently active clients
    ::android::base::Result<void> toString(std::unordered_map<std::string, std::string>* usages,
                                           const char* indent = "") EXCLUDES(mMutex);

private:
    // Mutex to protect records
    mutable ::android::Mutex mMutex;

    // Looper to message the collection thread
    ::android::sp<LooperWrapper> mLooper;

    // Background thread to pull stats from the clients
    std::thread mCollectionThread;

    // Current state of the monitor
    CollectionEvent mCurrentCollectionEvent GUARDED_BY(mMutex);

    // Periodic collection information
    CollectionInfo mPeriodicCollectionInfo GUARDED_BY(mMutex);

    // A collection during the custom period the user sets
    CollectionInfo mCustomCollectionInfo GUARDED_BY(mMutex);

    // A list of HalCamera objects to monitor
    std::unordered_map<std::string, std::weak_ptr<HalCamera>> mClientsToMonitor GUARDED_BY(mMutex);

    // Handles the messages from the looper
    void handleMessage(const ::android::Message& message) override;

    // Handles each CollectionEvent
    ::android::base::Result<void> handleCollectionEvent(CollectionEvent event, CollectionInfo* info)
            EXCLUDES(mMutex);

    // Pulls the statistics from each active HalCamera objects and generates the
    // records
    ::android::base::Result<void> collectLocked(CollectionInfo* info) REQUIRES(mMutex);

    // Returns a string corresponding to a given collection event
    std::string toString(const CollectionEvent& event) const;
};

}  // namespace aidl::android::automotive::evs::implementation

#endif  // CPP_EVS_MANAGER_AIDL_STATS_INCLUDE_STATSCOLLECTOR_H
