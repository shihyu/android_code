#!/usr/bin/env python3
#
#   Copyright 2021 - The Android Open Source Project
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

from acts import utils
from acts_contrib.test_utils.power.PowerGTWGnssBaseTest import PowerGTWGnssBaseTest


class GnssPowerFrequencyTest(PowerGTWGnssBaseTest):
    """Gnss Power Low Power Mode Test"""

    # Test cases
    # L1 only test cases
    def test_L1_only_strong(self):
        self.ad.adb.shell('settings put secure location_mode 3')
        self.set_attenuation(self.atten_level['l1_strong_signal'])
        self.start_gnss_tracking_with_power_data()

    def test_L1_only_weak(self):
        self.ad.adb.shell('settings put secure location_mode 3')
        self.set_attenuation(self.atten_level['l1_weak_signal'])
        self.start_gnss_tracking_with_power_data()

    # L5 tests
    def test_L1L5_strong(self):
        self.ad.adb.shell('settings put secure location_mode 3')
        self.set_attenuation(self.atten_level['l1l5_strong_signal'])
        self.start_gnss_tracking_with_power_data()

    def test_L1L5_weak(self):
        self.ad.adb.shell('settings put secure location_mode 3')
        self.set_attenuation(self.atten_level['l1l5_weak_signal'])
        self.start_gnss_tracking_with_power_data()

    def test_L1_weak_L5_strong(self):
        self.ad.adb.shell('settings put secure location_mode 3')
        self.set_attenuation(self.atten_level['l1_w_l5_s_signal'])
        self.start_gnss_tracking_with_power_data()

    def test_L1_strong_L5_weak(self):
        self.ad.adb.shell('settings put secure location_mode 3')
        self.set_attenuation(self.atten_level['l1_s_l5_w_signal'])
        self.start_gnss_tracking_with_power_data()
