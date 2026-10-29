
pub use mono_proto_messages::contacts::*;

#[uniffi::remote(Record)]
pub struct ContactImportSpec {
    pub client_id: i64,
    pub phone: String,
    pub first_name: String,
    pub last_name: String,
}
#[uniffi::remote(Record)]
pub struct ContactImportMatch {
    pub client_id: i64,
    pub user_id: i64,
}
#[uniffi::remote(Record)]
pub struct ContactImportPopularity {
    pub client_id: i64,
    pub importers: i32,
}
#[uniffi::remote(Record)]
pub struct ContactImportResult {
    pub imported: Vec<ContactImportMatch>,
    pub retry_client_ids: Vec<i64>,
    pub popular: Vec<ContactImportPopularity>,
}

pub use mono_proto_client::contacts::import_contacts;
