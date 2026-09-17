use tellers_mtproto::latest::api::PhotoSize;

pub(crate) fn photo_size_type(
    size: &PhotoSize,
) -> Option<(String, i32, Option<Vec<u8>>, i32, i32)> {
    match size {
        PhotoSize::PhotoSize(s) => Some((s.type_.clone(), s.w.saturating_mul(s.h), None, s.w, s.h)),
        PhotoSize::PhotoSizeProgressive(s) => {
            Some((s.type_.clone(), s.w.saturating_mul(s.h), None, s.w, s.h))
        }
        PhotoSize::PhotoCachedSize(s) => {
            if s.bytes.is_empty() {
                return None;
            }
            Some((
                s.type_.clone(),
                s.w.saturating_mul(s.h),
                Some(s.bytes.clone()),
                s.w,
                s.h,
            ))
        }
        PhotoSize::PhotoStrippedSize(s) => {
            let jpeg = crate::stripped_jpeg::expand_stripped_jpeg(&s.bytes)?;
            let h = i32::from(*s.bytes.get(1).unwrap_or(&40));
            let w = i32::from(*s.bytes.get(2).unwrap_or(&40));
            Some((
                s.type_.clone(),
                w.saturating_mul(h).max(1),
                Some(jpeg),
                w,
                h,
            ))
        }
        PhotoSize::PhotoPathSize(_) | PhotoSize::PhotoSizeEmpty(_) => None,
        _ => None,
    }
}

pub(crate) fn pick_photo_size(sizes: &[PhotoSize]) -> Option<(String, Option<Vec<u8>>)> {
    let mut best: Option<(String, i32, Option<Vec<u8>>)> = None;
    for size in sizes {
        let Some((ty, area, bytes, _, _)) = photo_size_type(size) else {
            continue;
        };
        match &best {
            None => best = Some((ty, area, bytes)),
            Some((_, best_area, _)) if area >= *best_area => best = Some((ty, area, bytes)),
            _ => {}
        }
    }
    best.map(|(ty, _, bytes)| (ty, bytes))
}

/// Sizes `upload.getFile` can fetch. Skip stripped/path (`i`/`j`) and video
/// streaming letters (`u`/`v`) — those return `FILE_ID_INVALID`.
pub(crate) fn is_getfile_thumb_size(ty: &str) -> bool {
    matches!(ty, "s" | "m" | "x" | "y" | "w" | "a" | "b" | "c")
}

pub(crate) fn thumb_has_inline_bytes(bytes: &Option<Vec<u8>>) -> bool {
    bytes.as_ref().is_some_and(|b| !b.is_empty())
}

pub(crate) fn is_usable_thumb(ty: &str, bytes: &Option<Vec<u8>>) -> bool {
    thumb_has_inline_bytes(bytes) || is_getfile_thumb_size(ty)
}

/// Prefer inline stripped/cached JPEG so chat thumbs paint without getFile.
/// Fall back to Telegram's `m`/`x`/`s` network thumbs.
pub(crate) fn pick_thumb_candidate(
    sizes: &[(String, i32, Option<Vec<u8>>)],
) -> Option<(String, Option<Vec<u8>>)> {
    let usable: Vec<&(String, i32, Option<Vec<u8>>)> = sizes
        .iter()
        .filter(|(ty, _, bytes)| is_usable_thumb(ty, bytes))
        .collect();
    if let Some((ty, _, bytes)) = usable
        .iter()
        .filter(|(_, _, bytes)| thumb_has_inline_bytes(bytes))
        .max_by_key(|(_, area, _)| *area)
    {
        return Some((ty.clone(), bytes.clone()));
    }
    for wanted in ["m", "x", "s"] {
        if let Some((ty, _, bytes)) = usable.iter().find(|(ty, _, _)| ty == wanted) {
            return Some((ty.clone(), bytes.clone()));
        }
    }
    usable
        .iter()
        .min_by_key(|(_, area, _)| *area)
        .map(|(ty, _, bytes)| (ty.clone(), bytes.clone()))
}

/// Network preview used to sharpen a cell after an inline stripped thumb.
pub(crate) fn pick_getfile_preview(
    sizes: &[(String, i32, Option<Vec<u8>>)],
) -> Option<(String, Option<Vec<u8>>)> {
    let usable: Vec<&(String, i32, Option<Vec<u8>>)> = sizes
        .iter()
        .filter(|(ty, _, bytes)| is_getfile_thumb_size(ty) && !thumb_has_inline_bytes(bytes))
        .collect();
    for wanted in ["m", "s", "x", "a"] {
        if let Some((ty, _, bytes)) = usable.iter().find(|(ty, _, _)| ty == wanted) {
            return Some((ty.clone(), bytes.clone()));
        }
    }
    usable
        .iter()
        .min_by_key(|(_, area, _)| *area)
        .map(|(ty, _, bytes)| (ty.clone(), bytes.clone()))
}

/// GIF/sticker/inline pickers: use cached/stripped JPEG immediately, else the
/// smallest getFile size (`s`/`a` before `m`). `m` often returns FILE_ID_INVALID
/// on inline photos.
pub(crate) fn pick_inline_or_thumb_candidate(
    sizes: &[(String, i32, Option<Vec<u8>>)],
) -> Option<(String, Option<Vec<u8>>)> {
    let usable: Vec<&(String, i32, Option<Vec<u8>>)> = sizes
        .iter()
        .filter(|(ty, _, bytes)| is_usable_thumb(ty, bytes))
        .collect();
    if let Some((ty, _, bytes)) = usable
        .iter()
        .filter(|(_, _, bytes)| thumb_has_inline_bytes(bytes))
        .max_by_key(|(_, area, _)| *area)
    {
        return Some((ty.clone(), bytes.clone()));
    }
    for wanted in ["s", "a", "b", "m", "x"] {
        if let Some((ty, _, bytes)) = usable.iter().find(|(ty, _, _)| ty == wanted) {
            return Some((ty.clone(), bytes.clone()));
        }
    }
    pick_thumb_candidate(sizes)
}

pub(crate) fn collect_photo_sizes(sizes: &[PhotoSize]) -> Vec<(String, i32, Option<Vec<u8>>)> {
    sizes
        .iter()
        .filter_map(|size| photo_size_type(size).map(|(ty, area, bytes, _, _)| (ty, area, bytes)))
        .collect()
}

pub(crate) fn pick_thumb_size(sizes: &[PhotoSize]) -> Option<(String, Option<Vec<u8>>)> {
    pick_thumb_candidate(&collect_photo_sizes(sizes))
}

pub(crate) fn pick_document_thumb_size(sizes: &[PhotoSize]) -> Option<(String, Option<Vec<u8>>)> {
    pick_inline_or_thumb_candidate(&collect_photo_sizes(sizes))
}

/// Chat-cell JPEG: `m` (~320) like Telegram Android bubbles. `x` only if `m` is already the thumb.
/// https://core.telegram.org/api/files
pub(crate) fn pick_display_candidate(
    sizes: &[(String, i32, Option<Vec<u8>>)],
    thumb_ty: Option<&str>,
    full_ty: &str,
) -> Option<(String, Option<Vec<u8>>)> {
    let usable: Vec<&(String, i32, Option<Vec<u8>>)> = sizes
        .iter()
        .filter(|(ty, _, bytes)| is_usable_thumb(ty, bytes))
        .collect();
    for wanted in ["m", "s", "x"] {
        if let Some((ty, _, bytes)) = usable.iter().find(|(ty, _, _)| ty == wanted) {
            if Some(ty.as_str()) != thumb_ty && ty != full_ty {
                return Some((ty.clone(), bytes.clone()));
            }
        }
    }
    None
}

pub(crate) fn pick_display_size(
    sizes: &[PhotoSize],
    thumb_ty: Option<&str>,
    full_ty: &str,
) -> Option<(String, Option<Vec<u8>>)> {
    let collected: Vec<(String, i32, Option<Vec<u8>>)> = sizes
        .iter()
        .filter_map(|size| photo_size_type(size).map(|(ty, area, bytes, _, _)| (ty, area, bytes)))
        .collect();
    pick_display_candidate(&collected, thumb_ty, full_ty)
}

pub(crate) fn distinct_thumb_key(full_key: &str, full_ty: &str, thumb_ty: &str) -> String {
    if full_ty == thumb_ty {
        full_key.to_string()
    } else {
        format!("{full_key}:thumb")
    }
}

pub fn next_getfile_thumb_sizes(current: &str) -> &'static [&'static str] {
    match current {
        "s" => &["a", "m", "x"],
        "a" | "b" => &["s", "m", "x"],
        "m" => &["s", "x", "a"],
        "x" => &["s", "m", "a"],
        _ => &["s", "m", "x"],
    }
}

/// Profile video sizes. `u` is the main MPEG4 avatar; `v` is the smaller one.
pub fn next_video_avatar_sizes(current: &str) -> &'static [&'static str] {
    match current {
        "u" => &["v"],
        "v" => &["u"],
        _ => &["u", "v"],
    }
}
