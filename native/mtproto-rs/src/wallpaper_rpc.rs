//! Server wallpaper catalog and document source refresh.
//! https://core.telegram.org/api/wallpapers

use tellers_mtproto::latest::api::{
    AccountGetWallPaperRequest, AccountGetWallPapersRequest, AccountWallPapers, Document,
    InputWallPaper, InputWallPaperConstructor, WallPaper, WallPaperSettings,
};
use tellers_mtproto_session::Snapshot;

use crate::MtprotoError;
use crate::api_invoke::invoke_api;
use crate::media::{self, MediaRef};
use crate::peers::vector_boxed_items;

#[derive(Clone, Debug, uniffi::Record)]
pub struct WallpaperDto {
    pub id: i64,
    pub access_hash: i64,
    pub slug: String,
    pub pattern: bool,
    pub dark: bool,
    pub mime_type: String,
    pub document_id: Option<i64>,
    pub colors: Vec<i32>,
    pub intensity: Option<i32>,
    pub rotation: i32,
    pub blur: bool,
    pub motion: bool,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct WallpaperCatalogDto {
    pub hash: i64,
    pub not_modified: bool,
    pub wallpapers: Vec<WallpaperDto>,
}

pub fn get_wallpapers(
    snapshot: &mut Snapshot,
    api_id: i32,
    hash: i64,
) -> Result<WallpaperCatalogDto, MtprotoError> {
    let response: AccountWallPapers =
        invoke_api(snapshot, api_id, AccountGetWallPapersRequest { hash })?;
    Ok(map_catalog(response, hash))
}

fn map_catalog(response: AccountWallPapers, previous_hash: i64) -> WallpaperCatalogDto {
    match response {
        AccountWallPapers::AccountWallPapers(result) => WallpaperCatalogDto {
            hash: result.hash,
            not_modified: false,
            wallpapers: vector_boxed_items(&result.wallpapers)
                .filter_map(map_wallpaper)
                .collect(),
        },
        AccountWallPapers::AccountWallPapersNotModified(_) => WallpaperCatalogDto {
            hash: previous_hash,
            not_modified: true,
            wallpapers: Vec::new(),
        },
    }
}

fn map_wallpaper(wallpaper: &WallPaper) -> Option<WallpaperDto> {
    let (id, access_hash, slug, pattern, dark, document, settings) = match wallpaper {
        WallPaper::WallPaper(w) => (
            w.id,
            w.access_hash,
            w.slug.clone(),
            w.pattern.is_some(),
            w.dark.is_some(),
            Some(w.document.as_ref()),
            w.settings.as_deref(),
        ),
        WallPaper::WallPaperNoFile(w) => (
            w.id,
            0,
            String::new(),
            false,
            w.dark.is_some(),
            None,
            w.settings.as_deref(),
        ),
    };
    let (document_id, mime_type) = match document {
        Some(Document::Document(d)) => (Some(d.id), d.mime_type.clone()),
        Some(Document::DocumentEmpty(_)) => return None,
        None => (None, String::new()),
    };
    let mut result = WallpaperDto {
        id,
        access_hash,
        slug,
        pattern,
        dark,
        mime_type,
        document_id,
        colors: Vec::new(),
        intensity: None,
        rotation: 0,
        blur: false,
        motion: false,
    };
    if let Some(WallPaperSettings::WallPaperSettings(s)) = settings {
        result.colors = [
            s.background_color,
            s.second_background_color,
            s.third_background_color,
            s.fourth_background_color,
        ]
        .into_iter()
        .flatten()
        .collect();
        result.intensity = s.intensity;
        result.rotation = s.rotation.unwrap_or(0);
        result.blur = s.blur.is_some();
        result.motion = s.motion.is_some();
    }
    Some(result)
}

pub fn wallpaper_media(
    snapshot: &mut Snapshot,
    api_id: i32,
    id: i64,
    access_hash: i64,
) -> Result<MediaRef, MtprotoError> {
    let wallpaper: WallPaper = invoke_api(
        snapshot,
        api_id,
        AccountGetWallPaperRequest {
            wallpaper: Box::new(InputWallPaper::InputWallPaper(InputWallPaperConstructor {
                id,
                access_hash,
            })),
        },
    )?;
    let WallPaper::WallPaper(wallpaper) = wallpaper else {
        return Err(MtprotoError::Message("wallpaper has no document".into()));
    };
    let Document::Document(document) = wallpaper.document.as_ref() else {
        return Err(MtprotoError::Message(
            "wallpaper document unavailable".into(),
        ));
    };
    // Bound wallpaper transfers separately from message attachments.
    if document.size <= 0 || document.size > 20 * 1024 * 1024 {
        return Err(MtprotoError::Message("wallpaper exceeds size limit".into()));
    }
    let mut media = media::media_ref_from_document(document);
    media.cache_key = format!("wallpaper:{}:{}", wallpaper.id, document.id);
    Ok(media)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tellers_mtproto::latest::api::{WallPaperNoFileConstructor, WallPaperSettingsConstructor};

    #[test]
    fn not_modified_preserves_requested_hash() {
        let result = map_catalog(
            AccountWallPapers::AccountWallPapersNotModified(
                tellers_mtproto::latest::api::AccountWallPapersNotModifiedConstructor {},
            ),
            42,
        );
        assert!(result.not_modified);
        assert_eq!(result.hash, 42);
        assert!(result.wallpapers.is_empty());
    }

    #[test]
    fn no_file_preserves_black_gradient_and_negative_intensity() {
        let wallpaper = WallPaper::WallPaperNoFile(WallPaperNoFileConstructor {
            id: 7,
            flags: 4,
            default: None,
            dark: None,
            settings: Some(Box::new(WallPaperSettings::WallPaperSettings(
                WallPaperSettingsConstructor {
                    flags: 25,
                    blur: None,
                    motion: None,
                    background_color: Some(0),
                    second_background_color: Some(0xffffff),
                    third_background_color: None,
                    fourth_background_color: None,
                    intensity: Some(-40),
                    rotation: Some(135),
                    emoticon: None,
                },
            ))),
        });
        let result = map_wallpaper(&wallpaper).unwrap();
        assert_eq!(result.colors, vec![0, 0xffffff]);
        assert_eq!(result.intensity, Some(-40));
        assert_eq!(result.rotation, 135);
        assert_eq!(result.document_id, None);
    }
}
