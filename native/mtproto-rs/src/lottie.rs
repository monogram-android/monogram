//! TGS / Lottie playback via tlottie.

use crate::{HashMap, HashMapExt};
use std::cell::RefCell;
use std::io::Read;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, LazyLock};

use flate2::read::GzDecoder;
use parking_lot::Mutex;
use tlottie::{CPURenderer, Composition, Limits, RenderOptions};

use crate::MtprotoError;

static NEXT: AtomicU64 = AtomicU64::new(1);
static INSTANCES: LazyLock<Mutex<HashMap<u64, LottieState>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

struct LottieState {
    composition: Arc<Composition>,
    frame_count: u32,
    frame_rate: f32,
    width: u32,
    height: u32,
}

fn missing_lottie() -> MtprotoError {
    MtprotoError::Message("unknown lottie handle".into())
}

fn gunzip_if_needed(bytes: &[u8]) -> Result<Vec<u8>, MtprotoError> {
    let trimmed = bytes
        .iter()
        .position(|b| !b.is_ascii_whitespace())
        .map(|i| &bytes[i..])
        .unwrap_or(bytes);
    if trimmed.first().is_some_and(|b| *b == b'{' || *b == b'[') {
        return Ok(bytes.to_vec());
    }
    let mut decoder = GzDecoder::new(bytes);
    let mut out = Vec::new();
    decoder
        .read_to_end(&mut out)
        .map_err(|e| MtprotoError::Message(format!("tgs gunzip failed: {e}")))?;
    Ok(out)
}

pub fn create_lottie(data: Vec<u8>) -> Result<u64, MtprotoError> {
    const MAX_INPUT_SIZE: usize = 10 * 1024 * 1024;
    if data.is_empty() || data.len() > MAX_INPUT_SIZE {
        return Err(MtprotoError::Message("lottie input is too large".into()));
    }
    let json = gunzip_if_needed(&data)?;
    if json.len() > MAX_INPUT_SIZE {
        return Err(MtprotoError::Message("lottie json is too large".into()));
    }
    let composition = Composition::parse(&json, &Limits::default())
        .map_err(|e| MtprotoError::Message(format!("lottie parse failed: {e}")))?;
    const MAX_FRAMES: u32 = 600;
    const MAX_FPS: f32 = 60.0;
    const MAX_DIMENSION: u32 = 2048;
    let frame_count = composition.frame_count();
    let frame_rate = composition.frame_rate;
    let width = composition.width.max(1);
    let height = composition.height.max(1);
    if frame_count == 0
        || frame_count > MAX_FRAMES
        || !frame_rate.is_finite()
        || frame_rate <= 0.0
        || frame_rate > MAX_FPS
        || width > MAX_DIMENSION
        || height > MAX_DIMENSION
    {
        return Err(MtprotoError::Message(
            "lottie animation exceeds limits".into(),
        ));
    }
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    INSTANCES.lock().insert(
        id,
        LottieState {
            composition: Arc::new(composition),
            frame_count,
            frame_rate,
            width,
            height,
        },
    );
    Ok(id)
}

pub fn destroy_lottie(handle: u64) {
    INSTANCES.lock().remove(&handle);
}

pub fn lottie_frame_count(handle: u64) -> Result<u32, MtprotoError> {
    INSTANCES
        .lock()
        .get(&handle)
        .map(|s| s.frame_count)
        .ok_or_else(missing_lottie)
}

pub fn lottie_frame_rate(handle: u64) -> Result<f32, MtprotoError> {
    INSTANCES
        .lock()
        .get(&handle)
        .map(|s| s.frame_rate)
        .ok_or_else(missing_lottie)
}

pub fn lottie_size(handle: u64) -> Result<(u32, u32), MtprotoError> {
    INSTANCES
        .lock()
        .get(&handle)
        .map(|s| (s.width, s.height))
        .ok_or_else(missing_lottie)
}

thread_local! {
    static LOTTIE_SCRATCH: RefCell<Vec<u32>> = RefCell::new(Vec::new());
}

/// Renders one frame into a caller-owned RGBA8888 buffer.
pub fn render_lottie_frame_into(
    handle: u64,
    frame: f32,
    width: u32,
    height: u32,
    out: &mut [u8],
) -> Result<(), MtprotoError> {
    let composition = {
        let instances = INSTANCES.lock();
        let state = instances.get(&handle).ok_or_else(missing_lottie)?;
        Arc::clone(&state.composition)
    };
    let mut renderer = CPURenderer::from_shared(composition);
    const MAX_RENDER_DIMENSION: u32 = 512;
    let w = width.max(1).min(MAX_RENDER_DIMENSION);
    let h = height.max(1).min(MAX_RENDER_DIMENSION);
    let pixel_count = (w as usize)
        .checked_mul(h as usize)
        .ok_or_else(|| MtprotoError::Message("lottie frame is too large".into()))?;
    let byte_count = pixel_count
        .checked_mul(4)
        .ok_or_else(|| MtprotoError::Message("lottie frame is too large".into()))?;
    if out.len() < byte_count {
        return Err(MtprotoError::Message("lottie buffer is too small".into()));
    }
    LOTTIE_SCRATCH.with(|scratch| {
        let mut pixels = scratch.borrow_mut();
        pixels.resize(pixel_count, 0);
        renderer
            .render(frame, &mut pixels, w, h, RenderOptions::default())
            .map_err(|e| MtprotoError::Message(format!("lottie render failed: {e}")))?;
        for (index, px) in pixels.iter().enumerate() {
            let offset = index * 4;
            out[offset] = (px & 0xFF) as u8;
            out[offset + 1] = ((px >> 8) & 0xFF) as u8;
            out[offset + 2] = ((px >> 16) & 0xFF) as u8;
            out[offset + 3] = ((px >> 24) & 0xFF) as u8;
        }
        Ok(())
    })
}

/// Renders one frame into packed RGBA8888 bytes.
pub fn render_lottie_frame(
    handle: u64,
    frame: f32,
    width: u32,
    height: u32,
) -> Result<Vec<u8>, MtprotoError> {
    const MAX_RENDER_DIMENSION: u32 = 512;
    let w = width.max(1).min(MAX_RENDER_DIMENSION);
    let h = height.max(1).min(MAX_RENDER_DIMENSION);
    let byte_count = (w as usize)
        .checked_mul(h as usize)
        .and_then(|pixels| pixels.checked_mul(4))
        .ok_or_else(|| MtprotoError::Message("lottie frame is too large".into()))?;
    let mut out = vec![0u8; byte_count];
    render_lottie_frame_into(handle, frame, width, height, &mut out)?;
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn render_into_rejects_a_short_buffer_before_writing() {
        let mut out = [0u8; 4];
        let err = render_lottie_frame_into(0, 0.0, 2, 2, &mut out).unwrap_err();
        let message = match err {
            MtprotoError::Message(text) => text,
            other => panic!("unexpected error: {other:?}"),
        };
        assert!(
            message.contains("unknown lottie") || message.contains("too small"),
            "{message}"
        );
    }
}
