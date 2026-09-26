//! Android UniFFI surface for Monogram (Tellers MTProto runtime).
//! https://core.telegram.org/mtproto
//! https://core.telegram.org/api/invoking
//! https://core.telegram.org/api/layers

#![deny(unsafe_code)]

#[global_allocator]
static GLOBAL: mimalloc::MiMalloc = mimalloc::MiMalloc;

mod api_invoke;
mod auth_key;
mod auth_rpc;
mod client;
mod client_mgr;
mod collections;
mod dialogs;
mod dialogs_rpc;
mod dns_txt;
mod dto;
mod emoji_status;
mod error;
mod extras_rpc;
mod ffi;
mod inline_rpc;
mod instant_view;
mod instant_view_rpc;
mod lottie;
pub mod media;
mod media_rpc;
mod messages;
mod messages_rpc;
mod peers;
mod perf;
mod presence;
mod profile;
mod profile_rpc;
mod push_rpc;
mod read_receipts_rpc;
mod reply_markup;
mod request_control;
mod rich_rpc;
mod rpc;
mod scheduler;
mod search_rpc;
mod service_messages;
mod session_crypto;
mod session_file;
mod srp;
mod sticker_rpc;
mod stripped_jpeg;
mod tcp;
mod update_buffer;
mod updates_rpc;
mod upload_rpc;
#[allow(unsafe_code)]
mod vpx;
mod wallpaper_rpc;

pub(crate) use collections::{
    CompactString, HashMap, HashMapExt, HashSet, HashSetExt, IndexMap, SmallVec,
};

pub use dto::*;
pub use error::*;
pub use ffi::*;
pub use instant_view_rpc::InstantViewDto;
pub use media::{ProgressCallback, notify_progress, set_progress_callback};
pub use wallpaper_rpc::{WallpaperCatalogDto, WallpaperDto};

uniffi::setup_scaffolding!();

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn create_and_destroy_client() {
        let path = std::env::temp_dir().join("monogram-tellers-test.session");
        let handle = create_client(12345, "hash".into(), path.display().to_string());
        assert!(client_exists(handle));
        assert_eq!(client_api_id(handle), 12345);
        destroy_client(handle);
        assert!(!client_exists(handle));
    }

    #[test]
    fn session_file_persists_user_id() {
        use crate::session_file::{ClientSession, FileSessionStore};
        use tellers_mtproto_session::{OsRandom, Snapshot};

        let path =
            std::env::temp_dir().join(format!("monogram-session-{}.json", std::process::id()));
        let _ = std::fs::remove_file(&path);
        let store = FileSessionStore::new(&path);
        let snapshot = Snapshot::new(2, &mut OsRandom).expect("snapshot");
        store
            .save(&ClientSession {
                channel_recovery: Default::default(),
                snapshot: snapshot.clone(),
                user_id: Some(42),
                peers: Default::default(),
                updates: None,
                media: Default::default(),
                channel_pts: Default::default(),
                seen_messages: Default::default(),
                logout_tokens: vec![vec![1, 2, 3]],
                new_session: None,
                session_dead: true,
                test_dc: false,
            })
            .expect("save");
        let raw = std::fs::read(&path).expect("read compact");
        assert!(!raw.contains(&b'\n'), "session JSON must be compact");
        let loaded = store.load().expect("load").expect("present");
        assert_eq!(loaded.user_id, Some(42));
        assert_eq!(loaded.snapshot.dc_id, 2);
        assert!(loaded.session_dead);
        assert_eq!(loaded.logout_tokens, vec![vec![1, 2, 3]]);
        store.clear().expect("clear");
        assert!(store.load().expect("load empty").is_none());
    }

    #[test]
    fn peer_chat_id_roundtrip_helpers() {
        use crate::peers::{
            channel_id_from_chat_id, chat_id_for_channel, chat_id_for_chat, chat_id_for_user,
            is_channel_chat_id,
        };
        assert_eq!(chat_id_for_user(42), 42);
        assert_eq!(chat_id_for_chat(7), -7);
        assert_eq!(chat_id_for_channel(123), -(1_000_000_000_000 + 123));
        assert!(is_channel_chat_id(chat_id_for_channel(123)));
        assert_eq!(channel_id_from_chat_id(chat_id_for_channel(123)), Some(123));
        assert!(!is_channel_chat_id(-7));
    }

    /// Live DC2 auth-key smoke. Ignored by default; run with
    /// `cargo test live_create_auth_key -- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn live_create_auth_key_dc2() {
        use crate::auth_key::create_auth_key;
        use tellers_mtproto_session::{OsRandom, Snapshot};
        use tellers_mtproto_transport::PaddedIntermediate;

        let mut snapshot = Snapshot::new(2, &mut OsRandom).expect("snapshot");
        let mut conn = crate::tcp::connect_obfuscated("149.154.167.51:443").expect("tcp");
        let mut framing = PaddedIntermediate::default();
        create_auth_key(&mut conn, &mut framing, &mut snapshot).expect("auth key");
        assert_eq!(snapshot.auth_key.as_ref().map(|k| k.len()), Some(256));
    }
}
