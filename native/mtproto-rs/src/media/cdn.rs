//! https://core.telegram.org/cdn
//! https://core.telegram.org/api/files
//! https://core.telegram.org/method/upload.getCdnFile

use aes::Aes256;
use cipher::{KeyIvInit, StreamCipher};
use ctr::Ctr128BE;
use sha2::{Digest, Sha256};
use std::cell::{Cell, RefCell};
use tellers_mtproto::latest::api::{
    FileHash, HelpGetCdnConfigRequest, True, TrueConstructor, UploadCdnFile,
    UploadFileCdnRedirectConstructor, UploadGetCdnFileRequest, UploadReuploadCdnFileRequest,
};

use crate::MtprotoError;

type Aes256Ctr = Ctr128BE<Aes256>;

thread_local! {
    static CDN_SUPPORTED: Cell<bool> = const { Cell::new(true) };
    static CDN_REDIRECT: RefCell<Option<CdnRedirect>> = const { RefCell::new(None) };
}

#[derive(Clone, Debug)]
pub struct CdnRedirect {
    pub dc_id: i32,
    pub file_token: Vec<u8>,
    pub encryption_key: Vec<u8>,
    pub encryption_iv: Vec<u8>,
    pub offset: i64,
}

pub fn cdn_supported() -> bool {
    CDN_SUPPORTED.with(Cell::get)
}

pub fn with_cdn_supported<T>(on: bool, body: impl FnOnce() -> T) -> T {
    CDN_SUPPORTED.with(|flag| {
        let previous = flag.replace(on);
        let result = body();
        flag.set(previous);
        result
    })
}

pub fn getfile_cdn_fields() -> (u32, Option<Box<True>>) {
    if cdn_supported() {
        (2, Some(Box::new(True::True(TrueConstructor {}))))
    } else {
        (0, None)
    }
}

pub fn store_cdn_redirect(redirect: &UploadFileCdnRedirectConstructor, offset: i64) {
    CDN_REDIRECT.with(|slot| {
        *slot.borrow_mut() = Some(CdnRedirect {
            dc_id: redirect.dc_id,
            file_token: redirect.file_token.clone(),
            encryption_key: redirect.encryption_key.clone(),
            encryption_iv: redirect.encryption_iv.clone(),
            offset,
        });
    });
}

pub fn take_cdn_redirect() -> Option<CdnRedirect> {
    CDN_REDIRECT.with(|slot| slot.borrow_mut().take())
}

pub fn is_cdn_redirect(err: &MtprotoError) -> bool {
    matches!(err, MtprotoError::Message(message) if message == "CDN_REDIRECT")
}

pub fn cdn_redirect_error() -> MtprotoError {
    MtprotoError::Message("CDN_REDIRECT".into())
}

/// AES-256-CTR used by Telegram Android `FileLoadOperation` for CDN parts.
pub fn decrypt_cdn_part(
    key: &[u8],
    encryption_iv: &[u8],
    offset: i64,
    data: &mut [u8],
) -> Result<(), MtprotoError> {
    if key.len() != 32 {
        return Err(MtprotoError::Message("cdn encryption_key".into()));
    }
    if encryption_iv.len() < 16 {
        return Err(MtprotoError::Message("cdn encryption_iv".into()));
    }
    if offset < 0 || offset % 16 != 0 {
        return Err(MtprotoError::Message("cdn offset".into()));
    }
    let key: [u8; 32] = key
        .try_into()
        .map_err(|_| MtprotoError::Message("cdn encryption_key".into()))?;
    let mut iv = [0u8; 16];
    iv.copy_from_slice(&encryption_iv[..16]);
    let block = (offset / 16) as u32;
    iv[12] = (block >> 24) as u8;
    iv[13] = (block >> 16) as u8;
    iv[14] = (block >> 8) as u8;
    iv[15] = block as u8;
    let mut cipher = Aes256Ctr::new(&key.into(), &iv.into());
    cipher.apply_keystream(data);
    Ok(())
}

pub fn verify_cdn_hash(data: &[u8], expected: &[u8]) -> bool {
    let digest = Sha256::digest(data);
    expected == digest.as_slice()
}

pub fn get_cdn_file_request(
    file_token: Vec<u8>,
    offset: i64,
    limit: i32,
) -> UploadGetCdnFileRequest {
    UploadGetCdnFileRequest {
        file_token,
        offset,
        limit,
    }
}

pub fn reupload_cdn_file_request(
    file_token: Vec<u8>,
    request_token: Vec<u8>,
) -> UploadReuploadCdnFileRequest {
    UploadReuploadCdnFileRequest {
        file_token,
        request_token,
    }
}

pub fn get_cdn_config_request() -> HelpGetCdnConfigRequest {
    HelpGetCdnConfigRequest {}
}

pub fn cdn_file_bytes(file: UploadCdnFile) -> Result<Result<Vec<u8>, Vec<u8>>, MtprotoError> {
    match file {
        UploadCdnFile::UploadCdnFile(body) => Ok(Ok(body.bytes)),
        UploadCdnFile::UploadCdnFileReuploadNeeded(body) => Ok(Err(body.request_token)),
        _ => Err(MtprotoError::Message("unexpected upload.cdnFile".into())),
    }
}

pub fn hash_matches(file_hash: &FileHash, offset: i64, data: &[u8]) -> bool {
    let FileHash::FileHash(hash) = file_hash else {
        return false;
    };
    if hash.offset != offset {
        return false;
    }
    let take = hash.limit.max(0) as usize;
    verify_cdn_hash(&data[..data.len().min(take)], &hash.hash)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decrypt_is_self_inverse() {
        let key = [7u8; 32];
        let iv = [9u8; 32];
        let mut data = b"cdn-part-fixture!!!!".to_vec();
        let original = data.clone();
        decrypt_cdn_part(&key, &iv, 0, &mut data).unwrap();
        assert_ne!(data, original);
        decrypt_cdn_part(&key, &iv, 0, &mut data).unwrap();
        assert_eq!(data, original);
    }
}
