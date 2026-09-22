//! Durable client session: Snapshot, user id, peer cache, updates cursor.

use crate::{HashMap, HashMapExt, HashSet, HashSetExt};
use std::fs;
use std::io::{ErrorKind, Write};
use std::path::PathBuf;

use serde::{Deserialize, Serialize};
use tellers_mtproto_session::{Error as SessionError, Snapshot};

use crate::UpdatesStateDto;
use crate::media::{MediaIndex, MediaRef};
use crate::peers::CachedPeer;
use crate::rpc::NewSessionMetadata;

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub(crate) struct ChannelRecovery {
    pub chat_id: i64,
    pub due_at: u64,
    /// Completed open-channel subscription; drop when the user leaves it.
    pub watching: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ClientSession {
    pub snapshot: Snapshot,
    #[serde(default)]
    pub user_id: Option<i64>,
    #[serde(default)]
    pub peers: HashMap<i64, CachedPeer>,
    #[serde(default)]
    pub updates: Option<UpdatesStateDto>,
    /// Media download metadata keyed as "chatId:messageId".
    #[serde(default)]
    pub media: HashMap<String, MediaRef>,
    /// Last applied per-channel pts, keyed by chat id.
    #[serde(default)]
    pub channel_pts: HashMap<i64, i32>,
    #[serde(default)]
    pub(crate) channel_recovery: std::collections::VecDeque<ChannelRecovery>,
    /// Deduplication keys for update events.
    #[serde(default)]
    pub seen_messages: HashSet<(i64, i32)>,
    /// Home auth key was rejected (401 AUTH_KEY_* / SESSION_* / USER_DEACTIVATED).
    #[serde(default)]
    pub session_dead: bool,
    /// Bounded future auth tokens returned by `auth.logOut`.
    #[serde(default)]
    pub logout_tokens: Vec<Vec<u8>>,
    /// Last `new_session_created` metadata observed on any lane.
    #[serde(default)]
    pub new_session: Option<NewSessionMetadata>,
    /// Connected to Telegram test DCs (`99966XYYYY`).
    #[serde(default)]
    pub test_dc: bool,
}

pub fn media_key(chat_id: i64, message_id: i32) -> String {
    format!("{chat_id}:{message_id}")
}

pub fn media_from_index(index: &MediaIndex) -> HashMap<String, MediaRef> {
    index
        .iter()
        .map(|((chat, msg), r)| (media_key(*chat, *msg), r.clone()))
        .collect()
}

pub fn media_to_index(map: &HashMap<String, MediaRef>) -> MediaIndex {
    let mut index = MediaIndex::new();
    for (key, media) in map {
        let mut parts = key.splitn(2, ':');
        let chat = parts.next().and_then(|s| s.parse().ok());
        let msg = parts.next().and_then(|s| s.parse().ok());
        if let (Some(chat), Some(msg)) = (chat, msg) {
            index.insert((chat, msg), media.clone());
        }
    }
    index
}

pub struct FileSessionStore {
    path: PathBuf,
}

impl FileSessionStore {
    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self { path: path.into() }
    }

    pub fn load(&self) -> Result<Option<ClientSession>, SessionError> {
        let bytes = match fs::read(&self.path) {
            Ok(bytes) => bytes,
            Err(error) if error.kind() == ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(SessionError::Persistence(error.to_string())),
        };
        if bytes.is_empty() {
            return Err(SessionError::Persistence("empty session file".into()));
        }
        let key = crate::session_crypto::key_for(&self.path);
        let encrypted = crate::session_crypto::is_encrypted(&bytes);
        let bytes = if encrypted {
            crate::session_crypto::decrypt(
                &bytes,
                key.as_deref()
                    .ok_or_else(|| SessionError::Persistence("session key unavailable".into()))?,
            )?
        } else {
            zeroize::Zeroizing::new(bytes)
        };
        if let Ok(session) = serde_json::from_slice::<ClientSession>(&bytes) {
            session.snapshot.validate()?;
            if !encrypted && key.is_some() {
                self.save(&session)?;
            }
            return Ok(Some(session));
        }
        let snapshot: Snapshot =
            serde_json::from_slice(&bytes).map_err(|e| SessionError::Persistence(e.to_string()))?;
        snapshot.validate()?;
        let session = ClientSession {
            snapshot,
            user_id: None,
            peers: HashMap::new(),
            updates: None,
            media: HashMap::new(),
            channel_pts: HashMap::new(),
            channel_recovery: Default::default(),
            seen_messages: HashSet::new(),
            session_dead: false,
            logout_tokens: Vec::new(),
            new_session: None,
            test_dc: false,
        };
        if !encrypted && key.is_some() {
            self.save(&session)?;
        }
        Ok(Some(session))
    }

    pub fn save(&self, session: &ClientSession) -> Result<(), SessionError> {
        crate::tcp::while_client_open(|| self.save_open(session))
            .map_err(|_| SessionError::Persistence("client closed".into()))?
    }

    fn save_open(&self, session: &ClientSession) -> Result<(), SessionError> {
        session.snapshot.validate()?;
        if let Some(parent) = self.path.parent().filter(|p| !p.as_os_str().is_empty()) {
            fs::create_dir_all(parent).map_err(|e| SessionError::Persistence(e.to_string()))?;
        }
        let plaintext = {
            let _span = crate::perf::span("session_save.encode");
            zeroize::Zeroizing::new(
                serde_json::to_vec(session)
                    .map_err(|_| SessionError::Persistence("session encoding failed".into()))?,
            )
        };
        let bytes = match crate::session_crypto::key_for(&self.path) {
            Some(key) => {
                let _span = crate::perf::span("session_save.encrypt");
                zeroize::Zeroizing::new(crate::session_crypto::encrypt(&plaintext, &key)?)
            },
            None => plaintext,
        };
        // Each writer owns its temporary file. Never truncate another writer's
        // snapshot, and flush its contents before the atomic replacement.
        let mut nonce = [0_u8; 16];
        tellers_mtproto_crypto::fill_random(&mut nonce)
            .map_err(|e| SessionError::Persistence(e.to_string()))?;
        let suffix = u128::from_le_bytes(nonce);
        let tmp = self.path.with_extension(format!("{suffix:032x}.tmp"));
        let mut options = fs::OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = options
            .open(&tmp)
            .map_err(|e| SessionError::Persistence(e.to_string()))?;
        let result = (|| {
            {
                let _span = crate::perf::span("session_save.write");
                file.write_all(&bytes)?;
            }
            {
                let _span = crate::perf::span("session_save.fsync");
                file.sync_all()?;
            }
            drop(file);
            fs::rename(&tmp, &self.path)?;
            #[cfg(unix)]
            {
                let parent = self
                    .path
                    .parent()
                    .filter(|p| !p.as_os_str().is_empty())
                    .unwrap_or_else(|| std::path::Path::new("."));
                let _span = crate::perf::span("session_save.directory_sync");
                fs::File::open(parent)?.sync_all()?;
            }
            Ok::<_, std::io::Error>(())
        })();
        if result.is_err() {
            let _ = fs::remove_file(&tmp);
        }
        result.map_err(|e| SessionError::Persistence(e.to_string()))
    }

    pub fn clear(&self) -> Result<(), SessionError> {
        match fs::remove_file(&self.path) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == ErrorKind::NotFound => Ok(()),
            Err(error) => Err(SessionError::Persistence(error.to_string())),
        }
    }
}

#[cfg(test)]
#[path = "session_file_tests.rs"]
mod tests;
