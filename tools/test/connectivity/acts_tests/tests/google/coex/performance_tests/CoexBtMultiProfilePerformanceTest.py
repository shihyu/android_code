#!/usr/bin/env python3
#
# Copyright (C) 2018 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License. You may obtain a copy of
# the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations under
# the License.

"""
Test script to check Wlan performance with MultiProfile Connection.

Test Setup:

Two Android deivce.
One A2DP and HFP Headset connected to Relay.
"""
import time

from acts_contrib.test_utils.bt import BtEnum
from acts_contrib.test_utils.bt.bt_test_utils import clear_bonded_devices
from acts_contrib.test_utils.car.tel_telecom_utils import wait_for_dialing
from acts_contrib.test_utils.coex.CoexPerformanceBaseTest import CoexPerformanceBaseTest
from acts_contrib.test_utils.coex.coex_test_utils import connect_dev_to_headset
from acts_contrib.test_utils.coex.coex_test_utils import music_play_and_check_via_app
from acts_contrib.test_utils.coex.coex_test_utils import pair_and_connect_headset
from acts_contrib.test_utils.coex.coex_test_utils import setup_tel_config
from acts_contrib.test_utils.coex.coex_test_utils import connect_wlan_profile
from acts_contrib.test_utils.tel.tel_voice_utils import hangup_call
from acts_contrib.test_utils.tel.tel_voice_utils import initiate_call
from acts_contrib.test_utils.tel.tel_voice_utils import wait_and_answer_call


class CoexBtMultiProfilePerformanceTest(CoexPerformanceBaseTest):

    def setup_class(self):
        super().setup_class()

        req_params = ["sim_conf_file", "music_file"]
        self.unpack_userparams(req_params)
        self.ag_phone_number, self.re_phone_number = setup_tel_config(
            self.pri_ad, self.sec_ad, self.sim_conf_file)
        if hasattr(self, "music_file"):
            self.push_music_to_android_device(self.pri_ad)

    def setup_test(self):
        super().setup_test()
        self.audio_receiver.enter_pairing_mode()
        if not pair_and_connect_headset(
                self.pri_ad, self.audio_receiver.mac_address,
                set([BtEnum.BluetoothProfile.HEADSET.value]) and
                set([BtEnum.BluetoothProfile.A2DP.value])):
            self.log.error("Failed to pair and connect to headset")
            return False

    def teardown_test(self):
        clear_bonded_devices(self.pri_ad)
        super().teardown_test()
        self.audio_receiver.clean_up()

    def initiate_call_when_a2dp_streaming_on(self):
        """Initiates HFP call when a2dp is streaming, then check for call is
        present or not.

        Disconnect a2dp profile and then connect HFP profile and
        answer the call from reference device.

        Returns:
            True if successful, False otherwise.
        """
        if not self.play_music_and_connect_wifi():
            return False

        if not initiate_call(self.log, self.pri_ad, self.re_phone_number):
            self.log.error("Failed to initiate call")
            return False
        if wait_for_dialing(self.log, self.pri_ad):
            self.pri_ad.droid.bluetoothDisconnectConnectedProfile(
                self.audio_receiver.mac_address,
                [BtEnum.BluetoothProfile.A2DP.value])
            if not connect_dev_to_headset(
                self.pri_ad, self.audio_receiver.mac_address,
                [BtEnum.BluetoothProfile.HEADSET.value]):
                return False
        if not wait_and_answer_call(self.log, self.sec_ad):
            self.log.error("Failed to answer call in second device")
            return False
        time.sleep(self.iperf["duration"])
        if not hangup_call(self.log, self.pri_ad):
            self.log.error("Failed to hangup call")
            return False
        return True

    def play_music_and_connect_wifi(self):
        """Perform a2dp music streaming and scan and connect to wifi
        network

        Returns:
            True if successful, False otherwise.
        """
        if not music_play_and_check_via_app(
                self.pri_ad, self.audio_receiver.mac_address):
            self.log.error("Failed to stream music file")
            return False
        if not connect_wlan_profile(self.pri_ad, self.network):
            return False
        return True

    def initiate_call_when_a2dp_streaming_with_iperf(self):
        """Wrapper function to initiate call when a2dp streaming and starts
         iperf.
         """
        tasks = [(self.initiate_call_when_a2dp_streaming_on, ()),
                 (self.run_iperf_and_get_result, ())]
        if not self.set_attenuation_and_run_iperf(tasks):
            return False
        return self.teardown_result()

    def test_performance_a2dp_streaming_hfp_call_tcp_ul(self):
        """Check performance when a2dp streaming and hfp call.

        This test is to check wifi performance when a2dp streaming and
        hfp call performed sequentially with TCP-uplink traffic.

        Steps:
        1.Enable bluetooth.
        2.Start a2dp streaming.
        3.Run TCP-uplink traffic.
        4.Initiate hfp call.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Kpi_041
        """
        if not self.initiate_call_when_a2dp_streaming_with_iperf():
            return False
        return True

    def test_performance_a2dp_streaming_hfp_call_tcp_dl(self):
        """Check performance when a2dp streaming and hfp call.

        This test is to check wifi performance when a2dp streaming and
        hfp call performed sequentially with TCP-downlink traffic.

        Steps:
        1.Enable bluetooth.
        2.Start a2dp streaming.
        3.Run TCP-downlink traffic.
        4.Initiate hfp call.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Kpi_042
        """
        if not self.initiate_call_when_a2dp_streaming_with_iperf():
            return False
        return True

    def test_performance_a2dp_streaming_hfp_call_udp_ul(self):
        """Check performance when a2dp streaming and hfp call.

        This test is to check wifi performance when a2dp streaming and
        hfp call performed sequentially with UDP-uplink traffic.

        Steps:
        1.Enable bluetooth.
        2.Start a2dp streaming.
        3.Run UDP-uplink traffic.
        4.Initiate hfp call.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Kpi_043
        """
        if not self.initiate_call_when_a2dp_streaming_with_iperf():
            return False
        return True

    def test_performance_a2dp_streaming_hfp_call_udp_dl(self):
        """Check performance when a2dp streaming and hfp call.

        This test is to check wifi performance when a2dp streaming and
        hfp call performed sequentially with UDP-uplink traffic.

        Steps:
        1.Enable bluetooth.
        2.Start a2dp streaming.
        3.Run UDP-uplink traffic.
        4.Initiate hfp call.

        Returns:
            True if successful, False otherwise.

        Test Id: Bt_CoEx_Kpi_044
        """
        if not self.initiate_call_when_a2dp_streaming_with_iperf():
            return False
        return True
