pub(crate) use mono_proto_client::adapter::messages::messages_location_send::*;

pub type LocationSendSpec =
    mono_proto_client::adapter::messages::messages_location_send::LocationSendSpec;

#[uniffi::remote(Record)]
pub struct LocationSendSpec {
    pub latitude: f64,
    pub longitude: f64,
    pub live_period: i32,
    pub heading: Option<i32>,
}
