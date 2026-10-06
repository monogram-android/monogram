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
