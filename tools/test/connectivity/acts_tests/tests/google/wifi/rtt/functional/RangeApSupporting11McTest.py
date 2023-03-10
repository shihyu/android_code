#!/usr/bin/python3.4
#
#   Copyright 2017 - The Android Open Source Project
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

import queue
import time

from acts import asserts
from acts.test_decorators import test_tracker_info
from acts_contrib.test_utils.tel.tel_wifi_utils import WIFI_CONFIG_APBAND_5G
from acts_contrib.test_utils.wifi import wifi_test_utils as wutils
from acts_contrib.test_utils.wifi.rtt import rtt_const as rconsts
from acts_contrib.test_utils.wifi.rtt import rtt_test_utils as rutils
from acts_contrib.test_utils.wifi.rtt.RttBaseTest import RttBaseTest


class RangeApSupporting11McTest(RttBaseTest):
    """Test class for RTT ranging to Access Points which support IEEE 802.11mc"""

    # Number of RTT iterations
    NUM_ITER = 10

    # Time gap (in seconds) between iterations
    TIME_BETWEEN_ITERATIONS = 0

    # Soft AP SSID
    SOFT_AP_SSID = "RTT_TEST_SSID"

    # Soft AP Password (irrelevant)
    SOFT_AP_PASSWORD = "ABCDEFGH"

    # Time to wait before configuration changes
    WAIT_FOR_CONFIG_CHANGES_SEC = 1

    def run_test_rtt_80211mc_supporting_aps(self, dut, accuracy_evaluation=False):
        """Scan for APs and perform RTT only to those which support 802.11mc
        Args:
            dut: test device
            accuracy_evaluation: False - only evaluate success rate.
                                 True - evaluate both success rate and accuracy
                                 default is False.
        """
        rtt_supporting_aps = rutils.select_best_scan_results(
            rutils.scan_with_rtt_support_constraint(dut, True, repeat=10),
            select_count=2)
        dut.log.debug("RTT Supporting APs=%s", rtt_supporting_aps)
        events = rutils.run_ranging(dut, rtt_supporting_aps, self.NUM_ITER,
                                    self.TIME_BETWEEN_ITERATIONS)
        stats = rutils.analyze_results(events, self.rtt_reference_distance_mm,
                                       self.rtt_reference_distance_margin_mm,
                                       self.rtt_min_expected_rssi_dbm,
                                       self.lci_reference, self.lcr_reference)
        dut.log.debug("Stats=%s", stats)

        for bssid, stat in stats.items():
            asserts.assert_true(
                stat['num_no_results'] == 0,
                "Missing (timed-out) results",
                extras=stats)
            asserts.assert_false(
                stat['any_lci_mismatch'], "LCI mismatch", extras=stats)
            asserts.assert_false(
                stat['any_lcr_mismatch'], "LCR mismatch", extras=stats)
            asserts.assert_false(
                stat['invalid_num_attempted'],
                "Invalid (0) number of attempts",
                extras=stats)
            asserts.assert_false(
                stat['invalid_num_successful'],
                "Invalid (0) number of successes",
                extras=stats)
            asserts.assert_equal(
                stat['num_invalid_rssi'], 0, "Invalid RSSI", extras=stats)
            asserts.assert_true(
                stat['num_failures'] <=
                self.rtt_max_failure_rate_two_sided_rtt_percentage *
                stat['num_results'] / 100,
                "Failure rate is too high",
                extras=stats)
            if accuracy_evaluation:
                asserts.assert_true(
                    stat['num_range_out_of_margin'] <=
                    self.rtt_max_margin_exceeded_rate_two_sided_rtt_percentage *
                    stat['num_success_results'] / 100,
                    "Results exceeding error margin rate is too high",
                    extras=stats)
        asserts.explicit_pass("RTT test done", extras=stats)

    @test_tracker_info(uuid="6705270f-924b-4bef-b50a-0f0a7eb9ce52")
    def test_rtt_80211mc_supporting_aps(self):
        """Scan for APs and perform RTT only to those which support 802.11mc,
        Functionality test: Only evaluate success rate."""
        dut = self.android_devices[0]
        self.run_test_rtt_80211mc_supporting_aps(dut)

    @test_tracker_info(uuid="56a8ca4c-b69d-436e-aa80-e86adb6f57d8")
    def test_rtt_80211mc_supporting_aps_with_accuracy_evaluation(self):
        """Scan for APs and perform RTT only to those which support 802.11mc,
        Performance test: evaluate success rate and accuracy."""
        dut = self.android_devices[0]
        self.run_test_rtt_80211mc_supporting_aps(dut, accuracy_evaluation=True)

    @test_tracker_info(uuid="eb3fc9f5-ae15-47f5-8468-697bb9aa9ddf")
    def test_rtt_in_and_after_softap_mode(self):
        """Verify behavior when a SoftAP is enabled and then disabled on the
        device:

        - SAP Enabled: depending on device characteristics RTT may succeed or
                       fail.
        - SAP Disabled: RTT must now succeed.
        """
        supp_required_params = ("dbs_supported_models", )
        self.unpack_userparams(supp_required_params)

        dut = self.android_devices[0]

        rtt_supporting_aps = rutils.select_best_scan_results(
            rutils.scan_with_rtt_support_constraint(dut, True, repeat=10),
            select_count=1)
        dut.log.debug("RTT Supporting APs=%s", rtt_supporting_aps)

        # phase 1 (pre-SAP)
        events = rutils.run_ranging(dut, rtt_supporting_aps, self.NUM_ITER,
                                    self.TIME_BETWEEN_ITERATIONS)
        stats = rutils.analyze_results(events, self.rtt_reference_distance_mm,
                                       self.rtt_reference_distance_margin_mm,
                                       self.rtt_min_expected_rssi_dbm,
                                       self.lci_reference, self.lcr_reference)
        dut.log.debug("Stats Phase 1 (pre-SAP)=%s", stats)

        for bssid, stat in stats.items():
            asserts.assert_true(
                stat['num_no_results'] == 0,
                "Phase 1 (pre-SAP) missing (timed-out) results",
                extras=stats)

        # phase 2 (SAP)
        wutils.start_wifi_tethering(
            dut,
            self.SOFT_AP_SSID,
            self.SOFT_AP_PASSWORD,
            band=WIFI_CONFIG_APBAND_5G,
            hidden=False)
        time.sleep(self.WAIT_FOR_CONFIG_CHANGES_SEC)

        if dut.model not in self.dbs_supported_models:
            rutils.wait_for_event(dut, rconsts.BROADCAST_WIFI_RTT_NOT_AVAILABLE)
            asserts.assert_false(dut.droid.wifiIsRttAvailable(),
                                 "RTT is available")

        events = rutils.run_ranging(dut, rtt_supporting_aps, self.NUM_ITER,
                                    self.TIME_BETWEEN_ITERATIONS)
        stats = rutils.analyze_results(events, self.rtt_reference_distance_mm,
                                       self.rtt_reference_distance_margin_mm,
                                       self.rtt_min_expected_rssi_dbm,
                                       self.lci_reference, self.lcr_reference)
        dut.log.debug("Stats Phase 2 (SAP)=%s", stats)

        for bssid, stat in stats.items():
            if dut.model in self.dbs_supported_models:
                asserts.assert_true(
                    stat['num_no_results'] == 0,
                    "Phase 2 (SAP) missing (timed-out) results",
                    extras=stats)
            else:
                asserts.assert_true(
                    stat['num_success_results'] == 0,
                    "Phase 2 (SAP) valid results - but unexpected in SAP!?",
                    extras=stats)

        # phase 3 (post-SAP)

        # enabling Wi-Fi first: on some devices this will also disable SAP
        # (that's the scenario we're primarily testing). Additionally,
        # explicitly disable SAP (which may be a NOP on some devices).
        wutils.wifi_toggle_state(dut, True)
        time.sleep(self.WAIT_FOR_CONFIG_CHANGES_SEC)
        wutils.stop_wifi_tethering(dut)

        if dut.model not in self.dbs_supported_models:
            rutils.wait_for_event(dut, rconsts.BROADCAST_WIFI_RTT_AVAILABLE)
            asserts.assert_true(dut.droid.wifiIsRttAvailable(),
                                "RTT is not available")

        events = rutils.run_ranging(dut, rtt_supporting_aps, self.NUM_ITER,
                                    self.TIME_BETWEEN_ITERATIONS)
        stats = rutils.analyze_results(events, self.rtt_reference_distance_mm,
                                       self.rtt_reference_distance_margin_mm,
                                       self.rtt_min_expected_rssi_dbm,
                                       self.lci_reference, self.lcr_reference)
        dut.log.debug("Stats Phase 3 (post-SAP)=%s", stats)

        for bssid, stat in stats.items():
            asserts.assert_true(
                stat['num_no_results'] == 0,
                "Phase 3 (post-SAP) missing (timed-out) results",
                extras=stats)

    #########################################################################
    #
    # LEGACY API test code
    #
    #########################################################################

    @test_tracker_info(uuid="18be9737-2f03-4e35-9a23-f722dea7b82d")
    def test_legacy_rtt_80211mc_supporting_aps(self):
        """Scan for APs and perform RTT only to those which support 802.11mc - using
        the LEGACY API!
        """
        dut = self.android_devices[0]
        rtt_supporting_aps = rutils.select_best_scan_results(
            rutils.scan_with_rtt_support_constraint(dut, True, repeat=10),
            select_count=2)
        dut.log.debug("RTT Supporting APs=%s", rtt_supporting_aps)

        rtt_configs = []
        for ap in rtt_supporting_aps:
            rtt_configs.append(self.rtt_config_from_scan_result(ap))
        dut.log.debug("RTT configs=%s", rtt_configs)

        results = []
        num_missing = 0
        num_failed_aborted = 0
        for i in range(self.NUM_ITER):
            idx = dut.droid.wifiRttStartRanging(rtt_configs)
            event = None
            try:
                events = dut.ed.pop_events("WifiRttRanging%d" % idx, 30)
                dut.log.debug("Event=%s", events)
                for event in events:
                    if rconsts.EVENT_CB_RANGING_KEY_RESULTS in event["data"]:
                        results.append(event["data"][
                            rconsts.EVENT_CB_RANGING_KEY_RESULTS])
                    else:
                        self.log.info("RTT failed/aborted - %s", event)
                        results.append([])
                        num_failed_aborted = num_failed_aborted + 1
            except queue.Empty:
                self.log.debug("Waiting for RTT event timed out.")
                results.append([])
                num_missing = num_missing + 1

        # basic error checking:
        # 1. no missing
        # 2. no full failed/aborted (i.e. operation not even tried)
        # 3. overall (all BSSIDs) success rate > threshold
        asserts.assert_equal(
            num_missing,
            0,
            "Missing results (timeout waiting for event)",
            extras={"data": results})
        asserts.assert_equal(
            num_failed_aborted,
            0,
            "Failed or aborted operations (not tried)",
            extras={"data": results})

        num_results = 0
        num_errors = 0
        for result_group in results:
            num_results = num_results + len(result_group)
            for result in result_group:
                if result["status"] != 0:
                    num_errors = num_errors + 1

        extras = [
            results, {
                "num_results": num_results,
                "num_errors": num_errors
            }
        ]
        asserts.assert_true(
            num_errors <= self.rtt_max_failure_rate_two_sided_rtt_percentage *
            num_results / 100,
            "Failure rate is too high",
            extras={"data": extras})
        asserts.explicit_pass("RTT test done", extras={"data": extras})

    def rtt_config_from_scan_result(self, scan_result):
        """Creates an Rtt configuration based on the scan result of a network.
        """
        WifiEnums = wutils.WifiEnums
        ScanResult = WifiEnums.ScanResult
        RttParam = WifiEnums.RttParam
        RttBW = WifiEnums.RttBW
        RttPreamble = WifiEnums.RttPreamble
        RttType = WifiEnums.RttType

        scan_result_channel_width_to_rtt = {
            ScanResult.CHANNEL_WIDTH_20MHZ: RttBW.BW_20_SUPPORT,
            ScanResult.CHANNEL_WIDTH_40MHZ: RttBW.BW_40_SUPPORT,
            ScanResult.CHANNEL_WIDTH_80MHZ: RttBW.BW_80_SUPPORT,
            ScanResult.CHANNEL_WIDTH_160MHZ: RttBW.BW_160_SUPPORT,
            ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ: RttBW.BW_160_SUPPORT
        }
        p = {}
        freq = scan_result[RttParam.frequency]
        p[RttParam.frequency] = freq
        p[RttParam.BSSID] = scan_result[WifiEnums.BSSID_KEY]
        if freq > 5000:
            p[RttParam.preamble] = RttPreamble.PREAMBLE_VHT
        else:
            p[RttParam.preamble] = RttPreamble.PREAMBLE_HT
        cf0 = scan_result[RttParam.center_freq0]
        if cf0 > 0:
            p[RttParam.center_freq0] = cf0
        cf1 = scan_result[RttParam.center_freq1]
        if cf1 > 0:
            p[RttParam.center_freq1] = cf1
        cw = scan_result["channelWidth"]
        p[RttParam.channel_width] = cw
        p[RttParam.bandwidth] = scan_result_channel_width_to_rtt[cw]
        if scan_result["is80211McRTTResponder"]:
            p[RttParam.request_type] = RttType.TYPE_TWO_SIDED
        else:
            p[RttParam.request_type] = RttType.TYPE_ONE_SIDED
        return p
