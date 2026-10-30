pub use mono_proto_client::adapter::messages::instant_view::*;

mod rpc {
    pub use mono_proto_client::adapter::messages::instant_view::InstantViewDto;

    #[uniffi::remote(Record)]
    pub struct InstantViewDto {
        pub url: String,
        pub display_url: String,
        pub title: Option<String>,
        pub site_name: Option<String>,
        pub description: Option<String>,
        pub webpage_type: Option<String>,
        pub hash: i32,
        pub has_instant_view: bool,
        pub part: bool,
        pub rtl: bool,
        pub v2: bool,
        pub not_modified: bool,
        pub blocks_json: String,
    }
}

pub use rpc::InstantViewDto;
