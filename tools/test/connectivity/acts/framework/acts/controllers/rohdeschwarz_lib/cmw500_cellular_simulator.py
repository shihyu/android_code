#!/usr/bin/env python3
#
#   Copyright 2019 - The Android Open Source Project
#
#   Licensed under the Apache License, Version 2.0 (the 'License');
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an 'AS IS' BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.
import time

from acts.controllers.rohdeschwarz_lib import cmw500
from acts.controllers import cellular_simulator as cc
from acts.controllers.cellular_lib import LteSimulation

CMW_TM_MAPPING = {
    LteSimulation.TransmissionMode.TM1: cmw500.TransmissionModes.TM1,
    LteSimulation.TransmissionMode.TM2: cmw500.TransmissionModes.TM2,
    LteSimulation.TransmissionMode.TM3: cmw500.TransmissionModes.TM3,
    LteSimulation.TransmissionMode.TM4: cmw500.TransmissionModes.TM4,
    LteSimulation.TransmissionMode.TM7: cmw500.TransmissionModes.TM7,
    LteSimulation.TransmissionMode.TM8: cmw500.TransmissionModes.TM8,
    LteSimulation.TransmissionMode.TM9: cmw500.TransmissionModes.TM9
}

CMW_SCH_MAPPING = {
    LteSimulation.SchedulingMode.STATIC: cmw500.SchedulingMode.USERDEFINEDCH
}

CMW_MIMO_MAPPING = {
    LteSimulation.MimoMode.MIMO_1x1: cmw500.MimoModes.MIMO1x1,
    LteSimulation.MimoMode.MIMO_2x2: cmw500.MimoModes.MIMO2x2,
    LteSimulation.MimoMode.MIMO_4x4: cmw500.MimoModes.MIMO4x4
}

# get mcs vs tbsi map with 256-qam disabled(downlink)
get_mcs_tbsi_map_dl = {
    cmw500.ModulationType.QPSK: {
        0: 0,
        1: 1,
        2: 2,
        3: 3,
        4: 4,
        5: 5,
        6: 6,
        7: 7,
        8: 8,
        9: 9
    },
    cmw500.ModulationType.Q16: {
        10: 9,
        11: 10,
        12: 11,
        13: 12,
        14: 13,
        15: 14,
        16: 15
    },
    cmw500.ModulationType.Q64: {
        17: 15,
        18: 16,
        19: 17,
        20: 18,
        21: 19,
        22: 20,
        23: 21,
        24: 22,
        25: 23,
        26: 24,
        27: 25,
        28: 26
    }
}

# get mcs vs tbsi map with 256-qam enabled(downlink)
get_mcs_tbsi_map_for_256qam_dl = {
    cmw500.ModulationType.QPSK: {
        0: 0,
        1: 2,
        2: 4,
        3: 6,
        4: 8,
    },
    cmw500.ModulationType.Q16: {
        5: 10,
        6: 11,
        7: 12,
        8: 13,
        9: 14,
        10: 15
    },
    cmw500.ModulationType.Q64: {
        11: 16,
        12: 17,
        13: 18,
        14: 19,
        15: 20,
        16: 21,
        17: 22,
        18: 23,
        19: 24
    },
    cmw500.ModulationType.Q256: {
        20: 25,
        21: 27,
        22: 28,
        23: 29,
        24: 30,
        25: 31,
        26: 32,
        27: 33
    }
}

# get mcs vs tbsi map (uplink)
get_mcs_tbsi_map_ul = {
    cmw500.ModulationType.QPSK: {
        0: 0,
        1: 1,
        2: 2,
        3: 3,
        4: 4,
        5: 5,
        6: 6,
        7: 7,
        8: 8,
        9: 9
    },
    cmw500.ModulationType.Q16: {
        10: 10,
        11: 10,
        12: 11,
        13: 12,
        14: 13,
        15: 14,
        16: 15,
        17: 16,
        18: 17,
        19: 18,
        20: 19,
        21: 19,
        22: 20,
        23: 21,
        24: 22,
        25: 23,
        26: 24,
        27: 25,
        28: 26
    }
}


class CMW500CellularSimulator(cc.AbstractCellularSimulator):
    """ A cellular simulator for telephony simulations based on the CMW 500
    controller. """

    # The maximum number of carriers that this simulator can support for LTE
    LTE_MAX_CARRIERS = 1

    def __init__(self, ip_address, port):
        """ Initializes the cellular simulator.

        Args:
            ip_address: the ip address of the CMW500
            port: the port number for the CMW500 controller
        """
        super().__init__()

        try:
            self.cmw = cmw500.Cmw500(ip_address, port)
        except cmw500.CmwError:
            raise cc.CellularSimulatorError('Could not connect to CMW500.')

        self.bts = None
        self.dl_modulation = None
        self.ul_modulation = None

    def destroy(self):
        """ Sends finalization commands to the cellular equipment and closes
        the connection. """
        self.cmw.disconnect()

    def setup_lte_scenario(self):
        """ Configures the equipment for an LTE simulation. """
        self.cmw.connection_type = cmw500.ConnectionType.DAU
        self.bts = [self.cmw.get_base_station()]
        self.cmw.switch_lte_signalling(cmw500.LteState.LTE_ON)

    def set_band_combination(self, bands):
        """ Prepares the test equipment for the indicated band combination.

        Args:
            bands: a list of bands represented as ints or strings
        """
        self.num_carriers = len(bands)

    def set_lte_rrc_state_change_timer(self, enabled, time=10):
        """ Configures the LTE RRC state change timer.

        Args:
            enabled: a boolean indicating if the timer should be on or off.
            time: time in seconds for the timer to expire
        """
        if enabled:
            self.cmw.rrc_connection = cmw500.RrcState.RRC_OFF
            self.cmw.rrc_connection_timer = time
        else:
            self.cmw.rrc_connection = cmw500.RrcState.RRC_ON

    def set_band(self, bts_index, band):
        """ Sets the band for the indicated base station.

        Args:
            bts_index: the base station number
            band: the new band
        """
        bts = self.bts[bts_index]
        bts.duplex_mode = self.get_duplex_mode(band)
        band = 'OB' + band
        bts.band = band
        self.log.debug('Band set to {}'.format(band))

    def get_duplex_mode(self, band):
        """ Determines if the band uses FDD or TDD duplex mode

        Args:
            band: a band number

        Returns:
            an variable of class DuplexMode indicating if band is FDD or TDD
        """
        if 33 <= int(band) <= 46:
            return cmw500.DuplexMode.TDD
        else:
            return cmw500.DuplexMode.FDD

    def set_input_power(self, bts_index, input_power):
        """ Sets the input power for the indicated base station.

        Args:
            bts_index: the base station number
            input_power: the new input power
        """
        bts = self.bts[bts_index]
        if input_power > 23:
            self.log.warning('Open loop supports-50dBm to 23 dBm. '
                             'Setting it to max power 23 dBm')
            input_power = 23
        bts.uplink_power_control = input_power
        bts.tpc_power_control = cmw500.TpcPowerControl.CLOSED_LOOP
        bts.tpc_closed_loop_target_power = input_power

    def set_output_power(self, bts_index, output_power):
        """ Sets the output power for the indicated base station.

        Args:
            bts_index: the base station number
            output_power: the new output power
        """
        bts = self.bts[bts_index]
        bts.downlink_power_level = output_power

    def set_tdd_config(self, bts_index, tdd_config):
        """ Sets the tdd configuration number for the indicated base station.

        Args:
            bts_index: the base station number
            tdd_config: the new tdd configuration number
        """
        self.bts[bts_index].uldl_configuration = tdd_config

    def set_ssf_config(self, bts_index, ssf_config):
        """ Sets the Special Sub-Frame config number for the indicated
        base station.

        Args:
            bts_index: the base station number
            ssf_config: the new ssf config number
        """
        if not 0 <= ssf_config <= 9:
            raise ValueError('The Special Sub-Frame configuration has to be a '
                             'number between 0 and 9.')

        self.bts[bts_index].tdd_special_subframe = ssf_config

    def set_bandwidth(self, bts_index, bandwidth):
        """ Sets the bandwidth for the indicated base station.

        Args:
            bts_index: the base station number
            bandwidth: the new bandwidth
        """
        bts = self.bts[bts_index]

        if bandwidth == 20:
            bts.bandwidth = cmw500.LteBandwidth.BANDWIDTH_20MHz
        elif bandwidth == 15:
            bts.bandwidth = cmw500.LteBandwidth.BANDWIDTH_15MHz
        elif bandwidth == 10:
            bts.bandwidth = cmw500.LteBandwidth.BANDWIDTH_10MHz
        elif bandwidth == 5:
            bts.bandwidth = cmw500.LteBandwidth.BANDWIDTH_5MHz
        elif bandwidth == 3:
            bts.bandwidth = cmw500.LteBandwidth.BANDWIDTH_3MHz
        elif bandwidth == 1.4:
            bts.bandwidth = cmw500.LteBandwidth.BANDWIDTH_1MHz
        else:
            msg = 'Bandwidth {} MHz is not valid for LTE'.format(bandwidth)
            raise ValueError(msg)

    def set_downlink_channel_number(self, bts_index, channel_number):
        """ Sets the downlink channel number for the indicated base station.

        Args:
            bts_index: the base station number
            channel_number: the new channel number
        """
        bts = self.bts[bts_index]
        bts.dl_channel = channel_number
        self.log.debug('Downlink Channel set to {}'.format(bts.dl_channel))

    def set_mimo_mode(self, bts_index, mimo_mode):
        """ Sets the mimo mode for the indicated base station.

        Args:
            bts_index: the base station number
            mimo_mode: the new mimo mode
        """
        bts = self.bts[bts_index]
        mimo_mode = CMW_MIMO_MAPPING[mimo_mode]
        if mimo_mode == cmw500.MimoModes.MIMO1x1:
            self.cmw.configure_mimo_settings(cmw500.MimoScenario.SCEN1x1)
            bts.dl_antenna = cmw500.MimoModes.MIMO1x1

        elif mimo_mode == cmw500.MimoModes.MIMO2x2:
            self.cmw.configure_mimo_settings(cmw500.MimoScenario.SCEN2x2)
            bts.dl_antenna = cmw500.MimoModes.MIMO2x2

        elif mimo_mode == cmw500.MimoModes.MIMO4x4:
            self.cmw.configure_mimo_settings(cmw500.MimoScenario.SCEN4x4)
            bts.dl_antenna = cmw500.MimoModes.MIMO4x4
        else:
            raise RuntimeError('The requested MIMO mode is not supported.')

    def set_transmission_mode(self, bts_index, tmode):
        """ Sets the transmission mode for the indicated base station.

        Args:
            bts_index: the base station number
            tmode: the new transmission mode
        """
        bts = self.bts[bts_index]

        tmode = CMW_TM_MAPPING[tmode]

        if (tmode in [
            cmw500.TransmissionModes.TM1,
            cmw500.TransmissionModes.TM7
        ] and bts.dl_antenna == cmw500.MimoModes.MIMO1x1.value):
            bts.transmode = tmode
        elif (tmode.value in cmw500.TransmissionModes.__members__ and
              bts.dl_antenna == cmw500.MimoModes.MIMO2x2.value):
            bts.transmode = tmode
        elif (tmode in [
            cmw500.TransmissionModes.TM2,
            cmw500.TransmissionModes.TM3,
            cmw500.TransmissionModes.TM4,
            cmw500.TransmissionModes.TM9
        ] and bts.dl_antenna == cmw500.MimoModes.MIMO4x4.value):
            bts.transmode = tmode

        else:
            raise ValueError('Transmission modes should support the current '
                             'mimo mode')

    def set_scheduling_mode(self, bts_index, scheduling, mcs_dl=None,
                            mcs_ul=None, nrb_dl=None, nrb_ul=None):
        """ Sets the scheduling mode for the indicated base station.

        Args:
            bts_index: the base station number.
            scheduling: the new scheduling mode.
            mcs_dl: Downlink MCS.
            mcs_ul: Uplink MCS.
            nrb_dl: Number of RBs for downlink.
            nrb_ul: Number of RBs for uplink.
        """
        bts = self.bts[bts_index]
        bts.reduced_pdcch = cmw500.ReducedPdcch.ON

        scheduling = CMW_SCH_MAPPING[scheduling]
        bts.scheduling_mode = scheduling

        if not (self.ul_modulation and self.dl_modulation):
            raise ValueError('Modulation should be set prior to scheduling '
                             'call')

        if scheduling == cmw500.SchedulingMode.RMC:

            if not nrb_ul and nrb_dl:
                raise ValueError('nrb_ul and nrb dl should not be none')

            bts.rb_configuration_ul = (nrb_ul, self.ul_modulation, 'KEEP')
            self.log.info('ul rb configurations set to {}'.format(
                bts.rb_configuration_ul))

            time.sleep(1)

            self.log.debug('Setting rb configurations for down link')
            bts.rb_configuration_dl = (nrb_dl, self.dl_modulation, 'KEEP')
            self.log.info('dl rb configurations set to {}'.format(
                bts.rb_configuration_ul))

        elif scheduling == cmw500.SchedulingMode.USERDEFINEDCH:

            if not all([nrb_ul, nrb_dl, mcs_dl, mcs_ul]):
                raise ValueError('All parameters are mandatory.')

            tbs = get_mcs_tbsi_map_ul[self.ul_modulation][mcs_ul]

            bts.rb_configuration_ul = (nrb_ul, 0, self.ul_modulation, tbs)
            self.log.info('ul rb configurations set to {}'.format(
                bts.rb_configuration_ul))

            time.sleep(1)

            if self.dl_256_qam_enabled:
                tbs = get_mcs_tbsi_map_for_256qam_dl[
                    self.dl_modulation][mcs_dl]
            else:
                tbs = get_mcs_tbsi_map_dl[self.dl_modulation][mcs_dl]

            bts.rb_configuration_dl = (nrb_dl, 0, self.dl_modulation, tbs)
            self.log.info('dl rb configurations set to {}'.format(
                bts.rb_configuration_dl))

    def set_dl_256_qam_enabled(self, bts_index, enabled):
        """ Determines what MCS table should be used for the downlink.
        This only saves the setting that will be used when configuring MCS.

        Args:
            bts_index: the base station number
            enabled: whether 256 QAM should be used
        """
        self.log.info('Set 256 QAM DL MCS enabled: ' + str(enabled))
        self.dl_modulation = cmw500.ModulationType.Q256 if enabled \
            else cmw500.ModulationType.Q64
        self.dl_256_qam_enabled = enabled

    def set_ul_64_qam_enabled(self, bts_index, enabled):
        """ Determines what MCS table should be used for the uplink.
        This only saves the setting that will be used when configuring MCS.

        Args:
            bts_index: the base station number
            enabled: whether 64 QAM should be used
        """
        self.log.info('Set 64 QAM UL MCS enabled: ' + str(enabled))
        self.ul_modulation = cmw500.ModulationType.Q64 if enabled \
            else cmw500.ModulationType.Q16
        self.ul_64_qam_enabled = enabled

    def set_mac_padding(self, bts_index, mac_padding):
        """ Enables or disables MAC padding in the indicated base station.

        Args:
            bts_index: the base station number
            mac_padding: the new MAC padding setting
        """
        # TODO (b/143918664): CMW500 doesn't have an equivalent setting.
        pass

    def set_cfi(self, bts_index, cfi):
        """ Sets the Channel Format Indicator for the indicated base station.

        Args:
            bts_index: the base station number
            cfi: the new CFI setting
        """
        # TODO (b/143497738): implement.
        self.log.error('Setting CFI is not yet implemented in the CMW500 '
                       'controller.')

    def set_paging_cycle(self, bts_index, cycle_duration):
        """ Sets the paging cycle duration for the indicated base station.

        Args:
            bts_index: the base station number
            cycle_duration: the new paging cycle duration in milliseconds
        """
        # TODO (b/146068532): implement.
        self.log.error('Setting the paging cycle duration is not yet '
                       'implemented in the CMW500 controller.')

    def set_phich_resource(self, bts_index, phich):
        """ Sets the PHICH Resource setting for the indicated base station.

        Args:
            bts_index: the base station number
            phich: the new PHICH resource setting
        """
        self.log.error('Configuring the PHICH resource setting is not yet '
                       'implemented in the CMW500 controller.')

    def lte_attach_secondary_carriers(self, ue_capability_enquiry):
        """ Activates the secondary carriers for CA. Requires the DUT to be
        attached to the primary carrier first.

        Args:
            ue_capability_enquiry: UE capability enquiry message to be sent to
        the UE before starting carrier aggregation.
        """
        raise NotImplementedError()

    def wait_until_attached(self, timeout=120):
        """ Waits until the DUT is attached to the primary carrier.

        Args:
            timeout: after this amount of time the method will raise a
                CellularSimulatorError exception. Default is 120 seconds.
        """
        try:
            self.cmw.wait_for_attached_state(timeout=timeout)
        except cmw500.CmwError:
            raise cc.CellularSimulatorError('The phone was not in '
                                            'attached state before '
                                            'the timeout period ended.')

    def wait_until_communication_state(self, timeout=120):
        """ Waits until the DUT is in Communication state.

        Args:
            timeout: after this amount of time the method will raise a
                CellularSimulatorError exception. Default is 120 seconds.
        """
        try:
            self.cmw.wait_for_rrc_state(cmw500.LTE_CONN_RESP, timeout=timeout)
        except cmw500.CmwError:
            raise cc.CellularSimulatorError('The phone was not in '
                                            'Communication state before '
                                            'the timeout period ended.')

    def wait_until_idle_state(self, timeout=120):
        """ Waits until the DUT is in Idle state.

        Args:
            timeout: after this amount of time the method will raise a
                CellularSimulatorError exception. Default is 120 seconds.
        """
        try:
            self.cmw.wait_for_rrc_state(cmw500.LTE_IDLE_RESP, timeout=timeout)
        except cmw500.CmwError:
            raise cc.CellularSimulatorError('The phone was not in '
                                            'Idle state before '
                                            'the timeout period ended.')

    def detach(self):
        """ Turns off all the base stations so the DUT loose connection."""
        self.cmw.detach()

    def stop(self):
        """ Stops current simulation. After calling this method, the simulator
        will need to be set up again. """
        raise NotImplementedError()

    def start_data_traffic(self):
        """ Starts transmitting data from the instrument to the DUT. """
        raise NotImplementedError()

    def stop_data_traffic(self):
        """ Stops transmitting data from the instrument to the DUT. """
        raise NotImplementedError()
