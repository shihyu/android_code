//! HAL interface.

use crate::error::UwbErr;
use crate::uci::uci_hrcv;
use crate::uci::uci_logger::{RealFileFactory, UciLogMode, UciLogger, UciLoggerImpl};
use crate::uci::HalCallback;
use android_hardware_uwb::aidl::android::hardware::uwb::{
    IUwb::IUwbAsync,
    IUwbChip::IUwbChipAsync,
    IUwbClientCallback::{BnUwbClientCallback, IUwbClientCallbackAsyncServer},
    UwbEvent::UwbEvent,
    UwbStatus::UwbStatus,
};
use android_hardware_uwb::binder::{
    BinderFeatures, DeathRecipient, Interface, Result as BinderResult, Strong,
};
use async_trait::async_trait;
use binder::IBinder;
use binder_tokio::{Tokio, TokioRuntime};
use log::error;
#[cfg(target_os = "android")]
use rustutils::system_properties;
use std::sync::Arc;
use tokio::runtime::Handle;
use tokio::sync::{mpsc, Mutex};
use uwb_uci_packets::{
    Packet, PacketDefrager, UciCommandPacket, UciPacketChild, UciPacketHalPacket, UciPacketPacket,
};

type Result<T> = std::result::Result<T, UwbErr>;
type SyncUciLogger = Arc<dyn UciLogger + Send + Sync>;

const UCI_LOG_DEFAULT: UciLogMode = UciLogMode::Disabled;

pub struct UwbClientCallback {
    rsp_sender: mpsc::UnboundedSender<HalCallback>,
    logger: SyncUciLogger,
    defrager: Mutex<PacketDefrager>,
}

impl UwbClientCallback {
    fn new(rsp_sender: mpsc::UnboundedSender<HalCallback>, logger: SyncUciLogger) -> Self {
        UwbClientCallback { rsp_sender, logger, defrager: Default::default() }
    }

    async fn log_uci_packet(&self, packet: UciPacketPacket) {
        match packet.specialize() {
            UciPacketChild::UciResponse(pkt) => self.logger.log_uci_response(pkt).await,
            UciPacketChild::UciNotification(pkt) => self.logger.log_uci_notification(pkt).await,
            _ => {}
        }
    }
}

impl Interface for UwbClientCallback {}

#[async_trait]
impl IUwbClientCallbackAsyncServer for UwbClientCallback {
    async fn onHalEvent(&self, event: UwbEvent, event_status: UwbStatus) -> BinderResult<()> {
        self.rsp_sender
            .send(HalCallback::Event { event, event_status })
            .unwrap_or_else(|e| error!("Error sending evt callback: {:?}", e));
        Ok(())
    }

    async fn onUciMessage(&self, data: &[u8]) -> BinderResult<()> {
        if let Some(packet) = self.defrager.lock().await.defragment_packet(data) {
            // all fragments for the packet received.
            self.log_uci_packet(packet.clone()).await;
            let packet_msg = uci_hrcv::uci_message(packet);
            match packet_msg {
                Ok(uci_hrcv::UciMessage::Response(evt)) => self
                    .rsp_sender
                    .send(HalCallback::UciRsp(evt))
                    .unwrap_or_else(|e| error!("Error sending uci response: {:?}", e)),
                Ok(uci_hrcv::UciMessage::Notification(evt)) => self
                    .rsp_sender
                    .send(HalCallback::UciNtf(evt))
                    .unwrap_or_else(|e| error!("Error sending uci notification: {:?}", e)),
                _ => error!("UCI message which is neither a UCI RSP or NTF: {:?}", data),
            }
        }
        Ok(())
    }
}

async fn get_hal_service() -> Result<Strong<dyn IUwbChipAsync<Tokio>>> {
    let service_name: &str = "android.hardware.uwb.IUwb/default";
    let i_uwb: Strong<dyn IUwbAsync<Tokio>> = binder_tokio::get_interface(service_name).await?;
    let chip_names = i_uwb.getChips().await?;
    let i_uwb_chip = i_uwb.getChip(&chip_names[0]).await?.into_async();
    Ok(i_uwb_chip)
}

#[async_trait]
pub trait UwbAdaptation {
    async fn finalize(&mut self, exit_status: bool);
    async fn hal_open(&self) -> Result<()>;
    async fn hal_close(&self) -> Result<()>;
    async fn core_initialization(&self) -> Result<()>;
    async fn session_initialization(&self, session_id: i32) -> Result<()>;
    async fn send_uci_message(&self, cmd: UciCommandPacket) -> Result<()>;
}

#[derive(Clone)]
pub struct UwbAdaptationImpl {
    hal: Strong<dyn IUwbChipAsync<Tokio>>,
    #[allow(dead_code)]
    // Need to store the death recipient since link_to_death stores a weak pointer.
    hal_death_recipient: Arc<Mutex<DeathRecipient>>,
    rsp_sender: mpsc::UnboundedSender<HalCallback>,
    logger: SyncUciLogger,
}

impl UwbAdaptationImpl {
    #[cfg(target_os = "android")]
    fn get_uci_log_mode() -> UciLogMode {
        match system_properties::read("persist.uwb.uci_logger_mode") {
            Ok(Some(logger_mode)) => match logger_mode.as_str() {
                "disabled" => UciLogMode::Disabled,
                "filtered" => UciLogMode::Filtered,
                "enabled" => UciLogMode::Enabled,
                str => {
                    error!("Logger mode not recognized! Value: {:?}", str);
                    UCI_LOG_DEFAULT
                }
            },
            Ok(None) => UCI_LOG_DEFAULT,
            Err(e) => {
                error!("Failed to get uci_logger_mode {:?}", e);
                UCI_LOG_DEFAULT
            }
        }
    }

    #[cfg(not(target_os = "android"))]
    fn get_uci_log_mode() -> UciLogMode {
        // system_properties is not supported on host builds.
        UCI_LOG_DEFAULT
    }

    async fn new_with_args(
        rsp_sender: mpsc::UnboundedSender<HalCallback>,
        hal: Strong<dyn IUwbChipAsync<Tokio>>,
        hal_death_recipient: Arc<Mutex<DeathRecipient>>,
    ) -> Result<Self> {
        let logger = UciLoggerImpl::new(
            UwbAdaptationImpl::get_uci_log_mode(),
            Arc::new(Mutex::new(RealFileFactory::default())),
        )
        .await;
        Ok(UwbAdaptationImpl { hal, rsp_sender, logger: Arc::new(logger), hal_death_recipient })
    }

    pub async fn new(rsp_sender: mpsc::UnboundedSender<HalCallback>) -> Result<Self> {
        let hal = get_hal_service().await?;
        let rsp_sender_clone = rsp_sender.clone();
        let mut hal_death_recipient = DeathRecipient::new(move || {
            error!("UWB HAL died. Resetting stack...");
            // Send error HAL event to trigger stack recovery.
            rsp_sender_clone
                .send(HalCallback::Event {
                    event: UwbEvent::ERROR,
                    event_status: UwbStatus::FAILED,
                })
                .unwrap_or_else(|e| error!("Error sending error evt callback: {:?}", e));
        });
        // Register for death notification.
        hal.as_binder().link_to_death(&mut hal_death_recipient)?;
        Self::new_with_args(rsp_sender, hal, Arc::new(Mutex::new(hal_death_recipient))).await
    }
}

#[async_trait]
impl UwbAdaptation for UwbAdaptationImpl {
    async fn finalize(&mut self, _exit_status: bool) {}

    async fn hal_open(&self) -> Result<()> {
        let m_cback = BnUwbClientCallback::new_async_binder(
            UwbClientCallback::new(self.rsp_sender.clone(), self.logger.clone()),
            TokioRuntime(Handle::current()),
            BinderFeatures::default(),
        );
        Ok(self.hal.open(&m_cback).await?)
    }

    async fn hal_close(&self) -> Result<()> {
        self.logger.close_file().await;
        Ok(self.hal.close().await?)
    }

    async fn core_initialization(&self) -> Result<()> {
        Ok(self.hal.coreInit().await?)
    }

    async fn session_initialization(&self, session_id: i32) -> Result<()> {
        Ok(self.hal.sessionInit(session_id).await?)
    }

    async fn send_uci_message(&self, cmd: UciCommandPacket) -> Result<()> {
        self.logger.log_uci_command(cmd.clone()).await;
        let packet: UciPacketPacket = cmd.into();
        // fragment packet.
        let fragmented_packets: Vec<UciPacketHalPacket> = packet.into();
        for packet in fragmented_packets {
            self.hal.sendUciMessage(&packet.to_vec()).await?;
        }
        // TODO should we be validating the returned number?
        Ok(())
    }
}

#[cfg(any(test, fuzzing))]
pub mod mock_adaptation;
#[cfg(test)]
mod mock_hal;

#[cfg(test)]
pub mod tests {
    use super::*;
    use crate::adaptation::mock_hal::MockHal;
    use crate::uci::mock_uci_logger::MockUciLogger;
    use bytes::Bytes;
    use uwb_uci_packets::*;

    fn create_uwb_client_callback(
        rsp_sender: mpsc::UnboundedSender<HalCallback>,
    ) -> UwbClientCallback {
        // Add tests for the mock logger.
        UwbClientCallback::new(rsp_sender, Arc::new(MockUciLogger::new()))
    }

    fn setup_client_callback() -> (mpsc::UnboundedReceiver<HalCallback>, UwbClientCallback) {
        // TODO: Remove this once we call it somewhere real.
        logger::init(
            logger::Config::default()
                .with_tag_on_device("uwb_test")
                .with_min_level(log::Level::Debug),
        );
        let (rsp_sender, rsp_receiver) = mpsc::unbounded_channel::<HalCallback>();
        let uwb_client_callback = create_uwb_client_callback(rsp_sender);
        (rsp_receiver, uwb_client_callback)
    }

    #[tokio::test]
    async fn test_on_hal_event() {
        let event = UwbEvent(0);
        let event_status = UwbStatus(1);
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onHalEvent(event, event_status).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(response, Some(HalCallback::Event { event: _, event_status: _ })));
    }

    #[tokio::test]
    async fn test_get_device_info_rsp() {
        let data = [
            0x40, 0x02, 0x00, 0x0b, 0x01, 0x01, 0x00, 0x02, 0x00, 0x03, 0x00, 0x04, 0x00, 0x01,
            0x0a,
        ];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciRsp(uci_hrcv::UciResponse::GetDeviceInfoRsp(_)))
        ));
    }

    #[tokio::test]
    async fn test_get_caps_info_rsp() {
        let data = [0x40, 0x03, 0x00, 0x05, 0x00, 0x01, 0x00, 0x01, 0x01];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciRsp(uci_hrcv::UciResponse::GetCapsInfoRsp(_)))
        ));
    }

    #[tokio::test]
    async fn test_set_config_rsp() {
        let data = [0x40, 0x04, 0x00, 0x04, 0x01, 0x01, 0x01, 0x01];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciRsp(uci_hrcv::UciResponse::SetConfigRsp(_)))
        ));
    }

    #[tokio::test]
    async fn test_get_config_rsp() {
        let data = [0x40, 0x05, 0x00, 0x05, 0x01, 0x01, 0x00, 0x01, 0x01];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciRsp(uci_hrcv::UciResponse::GetConfigRsp(_)))
        ));
    }

    #[tokio::test]
    async fn test_device_reset_rsp() {
        let data = [0x40, 0x00, 0x00, 0x01, 0x00];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciRsp(uci_hrcv::UciResponse::DeviceResetRsp(_)))
        ));
    }

    #[tokio::test]
    async fn test_session_init_rsp() {
        let data = [0x41, 0x00, 0x00, 0x01, 0x11];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciRsp(uci_hrcv::UciResponse::SessionInitRsp(_)))
        ));
    }

    #[tokio::test]
    async fn test_session_deinit_rsp() {
        let data = [0x41, 0x01, 0x00, 0x01, 0x00];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciRsp(uci_hrcv::UciResponse::SessionDeinitRsp(_)))
        ));
    }

    #[tokio::test]
    async fn test_session_get_app_config_rsp() {
        let data = [0x41, 0x04, 0x00, 0x02, 0x01, 0x00];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciRsp(uci_hrcv::UciResponse::SessionGetAppConfigRsp(_)))
        ));
    }

    #[tokio::test]
    async fn test_session_set_app_config_rsp() {
        let data = [0x41, 0x03, 0x00, 0x04, 0x01, 0x01, 0x01, 0x00];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciRsp(uci_hrcv::UciResponse::SessionSetAppConfigRsp(_)))
        ));
    }

    #[tokio::test]
    async fn test_session_get_state_rsp() {
        let data = [0x41, 0x06, 0x00, 0x02, 0x00, 0x01];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciRsp(uci_hrcv::UciResponse::SessionGetStateRsp(_)))
        ));
    }

    #[tokio::test]
    async fn test_session_get_count_rsp() {
        let data = [0x41, 0x05, 0x00, 0x02, 0x00, 0x01];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciRsp(uci_hrcv::UciResponse::SessionGetCountRsp(_)))
        ));
    }

    #[tokio::test]
    async fn test_session_update_controller_multicast_list_rsp() {
        let data = [0x41, 0x07, 0x00, 0x01, 0x00];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciRsp(
                uci_hrcv::UciResponse::SessionUpdateControllerMulticastListRsp(_)
            ))
        ));
    }

    #[tokio::test]
    async fn test_range_start_rsp() {
        let data = [0x42, 0x00, 0x00, 0x01, 0x00];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciRsp(uci_hrcv::UciResponse::RangeStartRsp(_)))
        ));
    }

    #[tokio::test]
    async fn test_range_stop_rsp() {
        let data = [0x42, 0x01, 0x00, 0x01, 0x00];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciRsp(uci_hrcv::UciResponse::RangeStopRsp(_)))
        ));
    }

    #[tokio::test]
    async fn test_android_set_country_code_rsp() {
        let data = [0x4c, 0x01, 0x00, 0x01, 0x00];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciRsp(uci_hrcv::UciResponse::AndroidSetCountryCodeRsp(_)))
        ));
    }

    #[tokio::test]
    async fn test_android_get_power_stats_rsp() {
        let data = [
            0x4c, 0x00, 0x00, 0x11, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        ];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciRsp(uci_hrcv::UciResponse::AndroidGetPowerStatsRsp(_)))
        ));
    }

    #[tokio::test]
    async fn test_raw_vendor_rsp_fragmented_packet() {
        let fragment_1 = [
            0x59, 0x01, 0x00, 0xff, 0x81, 0x93, 0xf8, 0x56, 0x53, 0x74, 0x5d, 0xcf, 0x45, 0xfa,
            0x34, 0xbd, 0xf1, 0x56, 0x53, 0x8f, 0x13, 0xff, 0x9b, 0xdd, 0xee, 0xaf, 0x0e, 0xff,
            0x1e, 0x63, 0xb6, 0xd7, 0xd4, 0x7b, 0xb7, 0x78, 0x30, 0xc7, 0x92, 0xd0, 0x8a, 0x5e,
            0xf0, 0x00, 0x1d, 0x05, 0xea, 0xf9, 0x56, 0xce, 0x8b, 0xbc, 0x8b, 0x1b, 0xc2, 0xd4,
            0x2a, 0xb8, 0x14, 0x82, 0x8b, 0xed, 0x12, 0xe5, 0x83, 0xe6, 0xb0, 0xb8, 0xa0, 0xb9,
            0xd0, 0x90, 0x6e, 0x09, 0x4e, 0x2e, 0x22, 0x38, 0x39, 0x03, 0x66, 0xf5, 0x95, 0x14,
            0x1c, 0xd7, 0x60, 0xbf, 0x28, 0x58, 0x9d, 0x47, 0x18, 0x1a, 0x93, 0x59, 0xbb, 0x0d,
            0x88, 0xf7, 0x7c, 0xce, 0x13, 0xa8, 0x2f, 0x3d, 0x0e, 0xd9, 0x5c, 0x19, 0x45, 0x5d,
            0xe8, 0xc3, 0xe0, 0x3a, 0xf3, 0x71, 0x09, 0x6e, 0x73, 0x07, 0x96, 0xa9, 0x1f, 0xf4,
            0x57, 0x84, 0x2e, 0x59, 0x6a, 0xf6, 0x90, 0x28, 0x47, 0xc1, 0x51, 0x7c, 0x59, 0x7e,
            0x95, 0xfc, 0xa6, 0x4d, 0x1b, 0xe6, 0xfe, 0x97, 0xa0, 0x39, 0x91, 0xa8, 0x28, 0xc9,
            0x1d, 0x7e, 0xfc, 0xec, 0x71, 0x1d, 0x43, 0x38, 0xcb, 0xbd, 0x50, 0xea, 0x02, 0xfd,
            0x2c, 0x7a, 0xde, 0x06, 0xdd, 0x77, 0x69, 0x4d, 0x2f, 0x57, 0xf5, 0x4b, 0x97, 0x51,
            0x58, 0x66, 0x7a, 0x8a, 0xcb, 0x7b, 0x91, 0x18, 0xbe, 0x4e, 0x94, 0xe4, 0xf1, 0xed,
            0x52, 0x06, 0xa7, 0xe8, 0x6b, 0xe1, 0x8f, 0x4a, 0x06, 0xe8, 0x2c, 0x9f, 0xc7, 0xcb,
            0xd2, 0x10, 0xb0, 0x0b, 0x71, 0x80, 0x2c, 0xd1, 0xf1, 0x03, 0xc2, 0x79, 0x7e, 0x7f,
            0x70, 0xf4, 0x8c, 0xc9, 0xcf, 0x9f, 0xcf, 0xa2, 0x8e, 0x6a, 0xe4, 0x1a, 0x28, 0x05,
            0xa8, 0xfe, 0x7d, 0xec, 0xd9, 0x5f, 0xa7, 0xd0, 0x29, 0x63, 0x1a, 0xba, 0x39, 0xf7,
            0xfa, 0x5e, 0xff, 0xb8, 0x5a, 0xbd, 0x35,
        ];
        let fragment_2 = [
            0x49, 0x01, 0x00, 0x91, 0xe7, 0x26, 0xfb, 0xc4, 0x48, 0x68, 0x42, 0x93, 0x23, 0x1f,
            0x87, 0xf6, 0x12, 0x5e, 0x60, 0xc8, 0x6a, 0x9d, 0x98, 0xbb, 0xb2, 0xb0, 0x47, 0x2f,
            0xaa, 0xa5, 0xce, 0xdb, 0x32, 0x88, 0x86, 0x0d, 0x6a, 0x5a, 0xfe, 0xc8, 0xda, 0xa1,
            0xc0, 0x06, 0x37, 0x08, 0xda, 0x67, 0x49, 0x6a, 0xa7, 0x04, 0x62, 0x95, 0xf3, 0x1e,
            0xcd, 0x71, 0x00, 0x99, 0x68, 0xb4, 0x03, 0xb3, 0x15, 0x64, 0x8b, 0xde, 0xbc, 0x8f,
            0x41, 0x64, 0xdf, 0x34, 0x6e, 0xff, 0x48, 0xc8, 0xe2, 0xbf, 0x02, 0x15, 0xc5, 0xbc,
            0x0f, 0xf8, 0xa1, 0x49, 0x91, 0x71, 0xdd, 0xb4, 0x37, 0x1c, 0xfa, 0x60, 0xcb, 0x0f,
            0xce, 0x6a, 0x0e, 0x90, 0xaf, 0x14, 0x30, 0xf2, 0x5b, 0x21, 0x6f, 0x85, 0xd3, 0x1b,
            0x89, 0xc9, 0xba, 0x3f, 0x07, 0x11, 0xbd, 0x56, 0xda, 0xdc, 0x88, 0xb4, 0xb0, 0x57,
            0x0b, 0x0c, 0x44, 0xd9, 0xb9, 0xd2, 0x38, 0x4c, 0xb6, 0xff, 0x83, 0xfe, 0xc8, 0x65,
            0xbc, 0x2a, 0x10, 0xed, 0x18, 0x62, 0xd2, 0x1b, 0x87,
        ];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result1 = uwb_client_callback.onUciMessage(&fragment_1).await;
        assert_eq!(result1, Ok(()));
        let result2 = uwb_client_callback.onUciMessage(&fragment_2).await;
        assert_eq!(result2, Ok(()));
        // One defragmented packet sent as response
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciRsp(uci_hrcv::UciResponse::RawVendorRsp(_)))
        ));
    }

    #[tokio::test]
    async fn test_generic_error_ntf() {
        let data = [0x60, 0x07, 0x00, 0x01, 0x01];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciNtf(uci_hrcv::UciNotification::GenericError(_)))
        ));
    }

    #[tokio::test]
    async fn test_device_status_ntf() {
        let data = [0x60, 0x01, 0x00, 0x01, 0x01];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciNtf(uci_hrcv::UciNotification::DeviceStatusNtf(_)))
        ));
    }

    #[tokio::test]
    async fn test_session_status_ntf() {
        let data = [0x61, 0x02, 0x00, 0x06, 0x01, 0x02, 0x03, 0x04, 0x02, 0x21];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciNtf(uci_hrcv::UciNotification::SessionStatusNtf(_)))
        ));
    }

    #[tokio::test]
    async fn test_session_update_controller_multicast_list_ntf() {
        let data = [0x61, 0x07, 0x00, 0x06, 0x00, 0x01, 0x02, 0x03, 0x04, 0x00];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciNtf(
                uci_hrcv::UciNotification::SessionUpdateControllerMulticastListNtf(_)
            ))
        ));
    }

    #[tokio::test]
    async fn test_short_mac_two_way_range_data_ntf() {
        let data = [
            0x62, 0x00, 0x00, 0x19, 0x00, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x00, 0x0a,
            0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00,
        ];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciNtf(uci_hrcv::UciNotification::ShortMacTwoWayRangeDataNtf(_)))
        ));
    }

    #[tokio::test]
    async fn test_extended_mac_two_way_range_data_ntf() {
        let data = [
            0x62, 0x00, 0x00, 0x19, 0x00, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x00, 0x0a,
            0x01, 0x01, 0x01, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00,
        ];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciNtf(uci_hrcv::UciNotification::ExtendedMacTwoWayRangeDataNtf(_)))
        ));
    }

    #[tokio::test]
    async fn test_raw_vendor_ntf_fragmented_packet() {
        let fragment_1 = [
            0x79, 0x01, 0x00, 0xff, 0x81, 0x93, 0xf8, 0x56, 0x53, 0x74, 0x5d, 0xcf, 0x45, 0xfa,
            0x34, 0xbd, 0xf1, 0x56, 0x53, 0x8f, 0x13, 0xff, 0x9b, 0xdd, 0xee, 0xaf, 0x0e, 0xff,
            0x1e, 0x63, 0xb6, 0xd7, 0xd4, 0x7b, 0xb7, 0x78, 0x30, 0xc7, 0x92, 0xd0, 0x8a, 0x5e,
            0xf0, 0x00, 0x1d, 0x05, 0xea, 0xf9, 0x56, 0xce, 0x8b, 0xbc, 0x8b, 0x1b, 0xc2, 0xd4,
            0x2a, 0xb8, 0x14, 0x82, 0x8b, 0xed, 0x12, 0xe5, 0x83, 0xe6, 0xb0, 0xb8, 0xa0, 0xb9,
            0xd0, 0x90, 0x6e, 0x09, 0x4e, 0x2e, 0x22, 0x38, 0x39, 0x03, 0x66, 0xf5, 0x95, 0x14,
            0x1c, 0xd7, 0x60, 0xbf, 0x28, 0x58, 0x9d, 0x47, 0x18, 0x1a, 0x93, 0x59, 0xbb, 0x0d,
            0x88, 0xf7, 0x7c, 0xce, 0x13, 0xa8, 0x2f, 0x3d, 0x0e, 0xd9, 0x5c, 0x19, 0x45, 0x5d,
            0xe8, 0xc3, 0xe0, 0x3a, 0xf3, 0x71, 0x09, 0x6e, 0x73, 0x07, 0x96, 0xa9, 0x1f, 0xf4,
            0x57, 0x84, 0x2e, 0x59, 0x6a, 0xf6, 0x90, 0x28, 0x47, 0xc1, 0x51, 0x7c, 0x59, 0x7e,
            0x95, 0xfc, 0xa6, 0x4d, 0x1b, 0xe6, 0xfe, 0x97, 0xa0, 0x39, 0x91, 0xa8, 0x28, 0xc9,
            0x1d, 0x7e, 0xfc, 0xec, 0x71, 0x1d, 0x43, 0x38, 0xcb, 0xbd, 0x50, 0xea, 0x02, 0xfd,
            0x2c, 0x7a, 0xde, 0x06, 0xdd, 0x77, 0x69, 0x4d, 0x2f, 0x57, 0xf5, 0x4b, 0x97, 0x51,
            0x58, 0x66, 0x7a, 0x8a, 0xcb, 0x7b, 0x91, 0x18, 0xbe, 0x4e, 0x94, 0xe4, 0xf1, 0xed,
            0x52, 0x06, 0xa7, 0xe8, 0x6b, 0xe1, 0x8f, 0x4a, 0x06, 0xe8, 0x2c, 0x9f, 0xc7, 0xcb,
            0xd2, 0x10, 0xb0, 0x0b, 0x71, 0x80, 0x2c, 0xd1, 0xf1, 0x03, 0xc2, 0x79, 0x7e, 0x7f,
            0x70, 0xf4, 0x8c, 0xc9, 0xcf, 0x9f, 0xcf, 0xa2, 0x8e, 0x6a, 0xe4, 0x1a, 0x28, 0x05,
            0xa8, 0xfe, 0x7d, 0xec, 0xd9, 0x5f, 0xa7, 0xd0, 0x29, 0x63, 0x1a, 0xba, 0x39, 0xf7,
            0xfa, 0x5e, 0xff, 0xb8, 0x5a, 0xbd, 0x35,
        ];
        let fragment_2 = [
            0x69, 0x01, 0x00, 0x91, 0xe7, 0x26, 0xfb, 0xc4, 0x48, 0x68, 0x42, 0x93, 0x23, 0x1f,
            0x87, 0xf6, 0x12, 0x5e, 0x60, 0xc8, 0x6a, 0x9d, 0x98, 0xbb, 0xb2, 0xb0, 0x47, 0x2f,
            0xaa, 0xa5, 0xce, 0xdb, 0x32, 0x88, 0x86, 0x0d, 0x6a, 0x5a, 0xfe, 0xc8, 0xda, 0xa1,
            0xc0, 0x06, 0x37, 0x08, 0xda, 0x67, 0x49, 0x6a, 0xa7, 0x04, 0x62, 0x95, 0xf3, 0x1e,
            0xcd, 0x71, 0x00, 0x99, 0x68, 0xb4, 0x03, 0xb3, 0x15, 0x64, 0x8b, 0xde, 0xbc, 0x8f,
            0x41, 0x64, 0xdf, 0x34, 0x6e, 0xff, 0x48, 0xc8, 0xe2, 0xbf, 0x02, 0x15, 0xc5, 0xbc,
            0x0f, 0xf8, 0xa1, 0x49, 0x91, 0x71, 0xdd, 0xb4, 0x37, 0x1c, 0xfa, 0x60, 0xcb, 0x0f,
            0xce, 0x6a, 0x0e, 0x90, 0xaf, 0x14, 0x30, 0xf2, 0x5b, 0x21, 0x6f, 0x85, 0xd3, 0x1b,
            0x89, 0xc9, 0xba, 0x3f, 0x07, 0x11, 0xbd, 0x56, 0xda, 0xdc, 0x88, 0xb4, 0xb0, 0x57,
            0x0b, 0x0c, 0x44, 0xd9, 0xb9, 0xd2, 0x38, 0x4c, 0xb6, 0xff, 0x83, 0xfe, 0xc8, 0x65,
            0xbc, 0x2a, 0x10, 0xed, 0x18, 0x62, 0xd2, 0x1b, 0x87,
        ];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result1 = uwb_client_callback.onUciMessage(&fragment_1).await;
        assert_eq!(result1, Ok(()));
        let result2 = uwb_client_callback.onUciMessage(&fragment_2).await;
        assert_eq!(result2, Ok(()));
        // One defragmented packet sent as response
        let response = rsp_receiver.recv().await;
        assert!(matches!(
            response,
            Some(HalCallback::UciNtf(uci_hrcv::UciNotification::RawVendorNtf(_)))
        ));
    }

    #[tokio::test]
    async fn test_on_uci_message_bad() {
        let data = [
            0x42, 0x02, 0x00, 0x0b, 0x01, 0x01, 0x00, 0x02, 0x00, 0x03, 0x00, 0x04, 0x00, 0x01,
            0x0a,
        ];
        let (mut rsp_receiver, uwb_client_callback) = setup_client_callback();
        let result = uwb_client_callback.onUciMessage(&data).await;
        assert_eq!(result, Ok(()));
        let response = rsp_receiver.try_recv();
        assert!(response.is_err());
    }

    async fn setup_adaptation_impl(config_fn: impl Fn(&MockHal)) -> Result<UwbAdaptationImpl> {
        // TODO: Remove this once we call it somewhere real.
        logger::init(
            logger::Config::default()
                .with_tag_on_device("uwb_test")
                .with_min_level(log::Level::Debug),
        );
        let (rsp_sender, _) = mpsc::unbounded_channel::<HalCallback>();
        let mock_hal = MockHal::new();
        config_fn(&mock_hal);

        UwbAdaptationImpl::new_with_args(
            rsp_sender,
            binder::Strong::new(Box::new(mock_hal)),
            Arc::new(Mutex::new(DeathRecipient::new(|| {}))),
        )
        .await
    }

    #[tokio::test]
    async fn test_send_uci_message() {
        let cmd: UciCommandPacket = GetDeviceInfoCmdBuilder {}.build().into();
        let adaptation_impl = setup_adaptation_impl(|mock_hal| {
            let cmd_packet: UciPacketPacket = cmd.clone().into();
            let mut cmd_frag_packets: Vec<UciPacketHalPacket> = cmd_packet.into();
            let cmd_frag_data = cmd_frag_packets.pop().unwrap().to_vec();
            let cmd_frag_data_len = cmd_frag_data.len();
            mock_hal
                .expect_send_uci_message(cmd_frag_data, Ok(cmd_frag_data_len.try_into().unwrap()));
        })
        .await
        .unwrap();
        adaptation_impl.send_uci_message(cmd).await.unwrap();
    }

    #[tokio::test]
    async fn test_send_uci_message_fragmented_packet() {
        let (rsp_sender, _) = mpsc::unbounded_channel::<HalCallback>();
        let mock_hal = MockHal::new();

        let cmd_payload: [u8; 400] = [
            0x81, 0x93, 0xf8, 0x56, 0x53, 0x74, 0x5d, 0xcf, 0x45, 0xfa, 0x34, 0xbd, 0xf1, 0x56,
            0x53, 0x8f, 0x13, 0xff, 0x9b, 0xdd, 0xee, 0xaf, 0x0e, 0xff, 0x1e, 0x63, 0xb6, 0xd7,
            0xd4, 0x7b, 0xb7, 0x78, 0x30, 0xc7, 0x92, 0xd0, 0x8a, 0x5e, 0xf0, 0x00, 0x1d, 0x05,
            0xea, 0xf9, 0x56, 0xce, 0x8b, 0xbc, 0x8b, 0x1b, 0xc2, 0xd4, 0x2a, 0xb8, 0x14, 0x82,
            0x8b, 0xed, 0x12, 0xe5, 0x83, 0xe6, 0xb0, 0xb8, 0xa0, 0xb9, 0xd0, 0x90, 0x6e, 0x09,
            0x4e, 0x2e, 0x22, 0x38, 0x39, 0x03, 0x66, 0xf5, 0x95, 0x14, 0x1c, 0xd7, 0x60, 0xbf,
            0x28, 0x58, 0x9d, 0x47, 0x18, 0x1a, 0x93, 0x59, 0xbb, 0x0d, 0x88, 0xf7, 0x7c, 0xce,
            0x13, 0xa8, 0x2f, 0x3d, 0x0e, 0xd9, 0x5c, 0x19, 0x45, 0x5d, 0xe8, 0xc3, 0xe0, 0x3a,
            0xf3, 0x71, 0x09, 0x6e, 0x73, 0x07, 0x96, 0xa9, 0x1f, 0xf4, 0x57, 0x84, 0x2e, 0x59,
            0x6a, 0xf6, 0x90, 0x28, 0x47, 0xc1, 0x51, 0x7c, 0x59, 0x7e, 0x95, 0xfc, 0xa6, 0x4d,
            0x1b, 0xe6, 0xfe, 0x97, 0xa0, 0x39, 0x91, 0xa8, 0x28, 0xc9, 0x1d, 0x7e, 0xfc, 0xec,
            0x71, 0x1d, 0x43, 0x38, 0xcb, 0xbd, 0x50, 0xea, 0x02, 0xfd, 0x2c, 0x7a, 0xde, 0x06,
            0xdd, 0x77, 0x69, 0x4d, 0x2f, 0x57, 0xf5, 0x4b, 0x97, 0x51, 0x58, 0x66, 0x7a, 0x8a,
            0xcb, 0x7b, 0x91, 0x18, 0xbe, 0x4e, 0x94, 0xe4, 0xf1, 0xed, 0x52, 0x06, 0xa7, 0xe8,
            0x6b, 0xe1, 0x8f, 0x4a, 0x06, 0xe8, 0x2c, 0x9f, 0xc7, 0xcb, 0xd2, 0x10, 0xb0, 0x0b,
            0x71, 0x80, 0x2c, 0xd1, 0xf1, 0x03, 0xc2, 0x79, 0x7e, 0x7f, 0x70, 0xf4, 0x8c, 0xc9,
            0xcf, 0x9f, 0xcf, 0xa2, 0x8e, 0x6a, 0xe4, 0x1a, 0x28, 0x05, 0xa8, 0xfe, 0x7d, 0xec,
            0xd9, 0x5f, 0xa7, 0xd0, 0x29, 0x63, 0x1a, 0xba, 0x39, 0xf7, 0xfa, 0x5e, 0xff, 0xb8,
            0x5a, 0xbd, 0x35, 0xe7, 0x26, 0xfb, 0xc4, 0x48, 0x68, 0x42, 0x93, 0x23, 0x1f, 0x87,
            0xf6, 0x12, 0x5e, 0x60, 0xc8, 0x6a, 0x9d, 0x98, 0xbb, 0xb2, 0xb0, 0x47, 0x2f, 0xaa,
            0xa5, 0xce, 0xdb, 0x32, 0x88, 0x86, 0x0d, 0x6a, 0x5a, 0xfe, 0xc8, 0xda, 0xa1, 0xc0,
            0x06, 0x37, 0x08, 0xda, 0x67, 0x49, 0x6a, 0xa7, 0x04, 0x62, 0x95, 0xf3, 0x1e, 0xcd,
            0x71, 0x00, 0x99, 0x68, 0xb4, 0x03, 0xb3, 0x15, 0x64, 0x8b, 0xde, 0xbc, 0x8f, 0x41,
            0x64, 0xdf, 0x34, 0x6e, 0xff, 0x48, 0xc8, 0xe2, 0xbf, 0x02, 0x15, 0xc5, 0xbc, 0x0f,
            0xf8, 0xa1, 0x49, 0x91, 0x71, 0xdd, 0xb4, 0x37, 0x1c, 0xfa, 0x60, 0xcb, 0x0f, 0xce,
            0x6a, 0x0e, 0x90, 0xaf, 0x14, 0x30, 0xf2, 0x5b, 0x21, 0x6f, 0x85, 0xd3, 0x1b, 0x89,
            0xc9, 0xba, 0x3f, 0x07, 0x11, 0xbd, 0x56, 0xda, 0xdc, 0x88, 0xb4, 0xb0, 0x57, 0x0b,
            0x0c, 0x44, 0xd9, 0xb9, 0xd2, 0x38, 0x4c, 0xb6, 0xff, 0x83, 0xfe, 0xc8, 0x65, 0xbc,
            0x2a, 0x10, 0xed, 0x18, 0x62, 0xd2, 0x1b, 0x87,
        ];
        let cmd: UciCommandPacket = UciVendor_9_CommandBuilder {
            opcode: 1,
            payload: Some(Bytes::from(cmd_payload.to_vec())),
        }
        .build()
        .into();

        let cmd_frag_data_1 = [
            0x39, 0x01, 0x00, 0xff, 0x81, 0x93, 0xf8, 0x56, 0x53, 0x74, 0x5d, 0xcf, 0x45, 0xfa,
            0x34, 0xbd, 0xf1, 0x56, 0x53, 0x8f, 0x13, 0xff, 0x9b, 0xdd, 0xee, 0xaf, 0x0e, 0xff,
            0x1e, 0x63, 0xb6, 0xd7, 0xd4, 0x7b, 0xb7, 0x78, 0x30, 0xc7, 0x92, 0xd0, 0x8a, 0x5e,
            0xf0, 0x00, 0x1d, 0x05, 0xea, 0xf9, 0x56, 0xce, 0x8b, 0xbc, 0x8b, 0x1b, 0xc2, 0xd4,
            0x2a, 0xb8, 0x14, 0x82, 0x8b, 0xed, 0x12, 0xe5, 0x83, 0xe6, 0xb0, 0xb8, 0xa0, 0xb9,
            0xd0, 0x90, 0x6e, 0x09, 0x4e, 0x2e, 0x22, 0x38, 0x39, 0x03, 0x66, 0xf5, 0x95, 0x14,
            0x1c, 0xd7, 0x60, 0xbf, 0x28, 0x58, 0x9d, 0x47, 0x18, 0x1a, 0x93, 0x59, 0xbb, 0x0d,
            0x88, 0xf7, 0x7c, 0xce, 0x13, 0xa8, 0x2f, 0x3d, 0x0e, 0xd9, 0x5c, 0x19, 0x45, 0x5d,
            0xe8, 0xc3, 0xe0, 0x3a, 0xf3, 0x71, 0x09, 0x6e, 0x73, 0x07, 0x96, 0xa9, 0x1f, 0xf4,
            0x57, 0x84, 0x2e, 0x59, 0x6a, 0xf6, 0x90, 0x28, 0x47, 0xc1, 0x51, 0x7c, 0x59, 0x7e,
            0x95, 0xfc, 0xa6, 0x4d, 0x1b, 0xe6, 0xfe, 0x97, 0xa0, 0x39, 0x91, 0xa8, 0x28, 0xc9,
            0x1d, 0x7e, 0xfc, 0xec, 0x71, 0x1d, 0x43, 0x38, 0xcb, 0xbd, 0x50, 0xea, 0x02, 0xfd,
            0x2c, 0x7a, 0xde, 0x06, 0xdd, 0x77, 0x69, 0x4d, 0x2f, 0x57, 0xf5, 0x4b, 0x97, 0x51,
            0x58, 0x66, 0x7a, 0x8a, 0xcb, 0x7b, 0x91, 0x18, 0xbe, 0x4e, 0x94, 0xe4, 0xf1, 0xed,
            0x52, 0x06, 0xa7, 0xe8, 0x6b, 0xe1, 0x8f, 0x4a, 0x06, 0xe8, 0x2c, 0x9f, 0xc7, 0xcb,
            0xd2, 0x10, 0xb0, 0x0b, 0x71, 0x80, 0x2c, 0xd1, 0xf1, 0x03, 0xc2, 0x79, 0x7e, 0x7f,
            0x70, 0xf4, 0x8c, 0xc9, 0xcf, 0x9f, 0xcf, 0xa2, 0x8e, 0x6a, 0xe4, 0x1a, 0x28, 0x05,
            0xa8, 0xfe, 0x7d, 0xec, 0xd9, 0x5f, 0xa7, 0xd0, 0x29, 0x63, 0x1a, 0xba, 0x39, 0xf7,
            0xfa, 0x5e, 0xff, 0xb8, 0x5a, 0xbd, 0x35,
        ];
        let cmd_frag_data_len_1 = cmd_frag_data_1.len();

        let cmd_frag_data_2 = [
            0x29, 0x01, 0x00, 0x91, 0xe7, 0x26, 0xfb, 0xc4, 0x48, 0x68, 0x42, 0x93, 0x23, 0x1f,
            0x87, 0xf6, 0x12, 0x5e, 0x60, 0xc8, 0x6a, 0x9d, 0x98, 0xbb, 0xb2, 0xb0, 0x47, 0x2f,
            0xaa, 0xa5, 0xce, 0xdb, 0x32, 0x88, 0x86, 0x0d, 0x6a, 0x5a, 0xfe, 0xc8, 0xda, 0xa1,
            0xc0, 0x06, 0x37, 0x08, 0xda, 0x67, 0x49, 0x6a, 0xa7, 0x04, 0x62, 0x95, 0xf3, 0x1e,
            0xcd, 0x71, 0x00, 0x99, 0x68, 0xb4, 0x03, 0xb3, 0x15, 0x64, 0x8b, 0xde, 0xbc, 0x8f,
            0x41, 0x64, 0xdf, 0x34, 0x6e, 0xff, 0x48, 0xc8, 0xe2, 0xbf, 0x02, 0x15, 0xc5, 0xbc,
            0x0f, 0xf8, 0xa1, 0x49, 0x91, 0x71, 0xdd, 0xb4, 0x37, 0x1c, 0xfa, 0x60, 0xcb, 0x0f,
            0xce, 0x6a, 0x0e, 0x90, 0xaf, 0x14, 0x30, 0xf2, 0x5b, 0x21, 0x6f, 0x85, 0xd3, 0x1b,
            0x89, 0xc9, 0xba, 0x3f, 0x07, 0x11, 0xbd, 0x56, 0xda, 0xdc, 0x88, 0xb4, 0xb0, 0x57,
            0x0b, 0x0c, 0x44, 0xd9, 0xb9, 0xd2, 0x38, 0x4c, 0xb6, 0xff, 0x83, 0xfe, 0xc8, 0x65,
            0xbc, 0x2a, 0x10, 0xed, 0x18, 0x62, 0xd2, 0x1b, 0x87,
        ];
        let cmd_frag_data_len_2 = cmd_frag_data_2.len();

        mock_hal.expect_send_uci_message(
            cmd_frag_data_1.to_vec(),
            Ok(cmd_frag_data_len_1.try_into().unwrap()),
        );
        mock_hal.expect_send_uci_message(
            cmd_frag_data_2.to_vec(),
            Ok(cmd_frag_data_len_2.try_into().unwrap()),
        );
        let adaptation_impl = UwbAdaptationImpl::new_with_args(
            rsp_sender,
            binder::Strong::new(Box::new(mock_hal)),
            Arc::new(Mutex::new(DeathRecipient::new(|| {}))),
        )
        .await
        .unwrap();
        adaptation_impl.send_uci_message(cmd).await.unwrap();
    }
}
