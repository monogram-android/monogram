//! Authenticated session storage; Android supplies a Keystore-wrapped data key.
use aes_gcm::{
    Aes256Gcm, KeyInit, Nonce,
    aead::{Aead, Payload},
};
use parking_lot::Mutex;
use std::{
    path::{Path, PathBuf},
    sync::{Arc, LazyLock, Weak},
};

use crate::{HashMap, HashMapExt};
use tellers_mtproto_session::Error;
use zeroize::Zeroizing;

/// Encrypted session v1 tag. Non-ASCII so plaintext JSON never matches.
pub(crate) const MAGIC: &[u8] = &[0xC3, 0xA7, 0x5E, 0x01];
pub(crate) const LEGACY_MAGIC: &[u8] = b"MONORE_SESSION\x01";
const BRANDED_MAGIC: &[u8] = b"MONOGRAM_SESSION\x01";
const SALT_LEN: usize = 16;
const NONCE_LEN: usize = 12;
const GCM_TAG_LEN: usize = 16;
pub(crate) type SessionKey = Zeroizing<[u8; 32]>;
static KEYS: LazyLock<Mutex<HashMap<PathBuf, Weak<SessionKey>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

fn error() -> Error {
    Error::Persistence("session encryption validation failed".into())
}

pub(crate) fn register(path: &Path, bytes: &[u8]) -> Result<Arc<SessionKey>, Error> {
    let key = Zeroizing::new(<[u8; 32]>::try_from(bytes).map_err(|_| error())?);
    let mut keys = KEYS.lock();
    keys.retain(|_, key| key.strong_count() != 0);
    if let Some(existing) = keys.get(path).and_then(Weak::upgrade) {
        if existing.as_ref().as_ref() != key.as_ref() {
            return Err(error());
        }
        return Ok(existing);
    }
    let key = Arc::new(key);
    keys.insert(path.to_owned(), Arc::downgrade(&key));
    Ok(key)
}

pub(crate) fn key_for(path: &Path) -> Option<Arc<SessionKey>> {
    KEYS.lock().get(path).and_then(Weak::upgrade)
}

pub(crate) fn is_encrypted(bytes: &[u8]) -> bool {
    bytes.starts_with(MAGIC) || bytes.starts_with(BRANDED_MAGIC) || bytes.starts_with(LEGACY_MAGIC)
}

pub(crate) fn header(bytes: &[u8]) -> Option<usize> {
    if bytes.starts_with(MAGIC) {
        let aad_len = MAGIC.len() + SALT_LEN;
        (bytes.len() >= aad_len + NONCE_LEN + GCM_TAG_LEN).then_some(aad_len)
    } else if bytes.starts_with(BRANDED_MAGIC) {
        (bytes.len() >= BRANDED_MAGIC.len() + NONCE_LEN + GCM_TAG_LEN)
            .then_some(BRANDED_MAGIC.len())
    } else if bytes.starts_with(LEGACY_MAGIC) {
        (bytes.len() >= LEGACY_MAGIC.len() + NONCE_LEN + GCM_TAG_LEN).then_some(LEGACY_MAGIC.len())
    } else {
        None
    }
}

pub(crate) fn encrypt(bytes: &[u8], key: &SessionKey) -> Result<Vec<u8>, Error> {
    let mut salt = [0u8; SALT_LEN];
    tellers_mtproto_crypto::fill_random(&mut salt).map_err(|_| error())?;
    let mut aad = Vec::with_capacity(MAGIC.len() + SALT_LEN);
    aad.extend_from_slice(MAGIC);
    aad.extend_from_slice(&salt);
    encrypt_with(bytes, key, &aad)
}

#[cfg(test)]
pub(crate) fn encrypt_legacy(bytes: &[u8], key: &SessionKey) -> Result<Vec<u8>, Error> {
    encrypt_with(bytes, key, LEGACY_MAGIC)
}

fn encrypt_with(bytes: &[u8], key: &SessionKey, aad: &[u8]) -> Result<Vec<u8>, Error> {
    let cipher = Aes256Gcm::new_from_slice(key.as_ref()).map_err(|_| error())?;
    let mut nonce = [0u8; NONCE_LEN];
    tellers_mtproto_crypto::fill_random(&mut nonce).map_err(|_| error())?;
    let nonce = Nonce::try_from(nonce.as_slice()).map_err(|_| error())?;
    let ciphertext = cipher
        .encrypt(&nonce, Payload { msg: bytes, aad })
        .map_err(|_| error())?;
    let mut result = Vec::with_capacity(aad.len() + nonce.len() + ciphertext.len());
    result.extend_from_slice(aad);
    result.extend_from_slice(&nonce);
    result.extend_from_slice(&ciphertext);
    Ok(result)
}

pub(crate) fn decrypt(bytes: &[u8], key: &SessionKey) -> Result<Zeroizing<Vec<u8>>, Error> {
    let aad_len = header(bytes).ok_or_else(error)?;
    let cipher = Aes256Gcm::new_from_slice(key.as_ref()).map_err(|_| error())?;
    let nonce = Nonce::try_from(&bytes[aad_len..aad_len + NONCE_LEN]).map_err(|_| error())?;
    cipher
        .decrypt(
            &nonce,
            Payload {
                msg: &bytes[aad_len + NONCE_LEN..],
                aad: &bytes[..aad_len],
            },
        )
        .map(Zeroizing::new)
        .map_err(|_| error())
}
