pub use mono_proto_media::wallpaper::*;

#[uniffi::remote(Record)]
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

#[uniffi::remote(Record)]
pub struct WallpaperCatalogDto {
    pub hash: i64,
    pub not_modified: bool,
    pub wallpapers: Vec<WallpaperDto>,
}
