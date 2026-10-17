//! VP9 video-sticker decode via slim libvpx.
#![allow(unsafe_code)]

use crate::{HashMap, HashMapExt};
use std::os::raw::{c_char, c_int, c_long, c_uint, c_void};
#[cfg(has_libvpx)]
use std::ptr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, LazyLock};

use parking_lot::Mutex;

use crate::MtprotoError;

const VPX_DECODER_ABI_VERSION: c_int = 12;
const VPX_DECODER_THREADS: c_uint = 1;
const VPX_IMG_FMT_I420: c_int = 0x100 | 2;
const VPX_IMG_FMT_YV12: c_int = 0x100 | 0x200 | 1;

#[repr(C)]
struct VpxCodecDecCfg {
    threads: c_uint,
    w: c_uint,
    h: c_uint,
}

#[repr(C)]
struct VpxCodecCtx {
    name: *const c_char,
    iface: *mut c_void,
    err: c_int,
    err_detail: *const c_char,
    init_flags: c_long,
    config: *const c_void,
    priv_data: *mut c_void,
}

#[repr(C)]
struct VpxImage {
    fmt: c_int,
    cs: c_int,
    range: c_int,
    w: c_uint,
    h: c_uint,
    bit_depth: c_uint,
    d_w: c_uint,
    d_h: c_uint,
    r_w: c_uint,
    r_h: c_uint,
    x_chroma_shift: c_uint,
    y_chroma_shift: c_uint,
    planes: [*mut u8; 4],
    stride: [c_int; 4],
    bps: c_int,
    user_priv: *mut c_void,
    img_data: *mut u8,
    img_data_owner: c_int,
    self_allocd: c_int,
}

#[cfg(has_libvpx)]
unsafe extern "C" {
    fn vpx_codec_vp9_dx() -> *mut c_void;
    fn vpx_codec_dec_init_ver(
        ctx: *mut VpxCodecCtx,
        iface: *mut c_void,
        cfg: *const VpxCodecDecCfg,
        flags: c_long,
        ver: c_int,
    ) -> c_int;
    fn vpx_codec_decode(
        ctx: *mut VpxCodecCtx,
        data: *const u8,
        data_sz: c_uint,
        user_priv: *mut c_void,
        deadline: c_long,
    ) -> c_int;
    fn vpx_codec_get_frame(ctx: *mut VpxCodecCtx, iter: *mut *const c_void) -> *mut VpxImage;
    fn vpx_codec_destroy(ctx: *mut VpxCodecCtx) -> c_int;
}

struct DecoderInner {
    ctx: VpxCodecCtx,
    alive: bool,
}

struct Decoder {
    inner: Mutex<DecoderInner>,
}

unsafe impl Send for DecoderInner {}

static NEXT: AtomicU64 = AtomicU64::new(1);
static INSTANCES: LazyLock<Mutex<HashMap<u64, Arc<Decoder>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

fn lookup_arc<T>(map: &Mutex<HashMap<u64, Arc<T>>>, handle: u64) -> Option<Arc<T>> {
    let guard = map.lock();
    guard.get(&handle).map(Arc::clone)
}

fn with_decoder<T>(
    handle: u64,
    body: impl FnOnce(&mut VpxCodecCtx) -> Result<T, MtprotoError>,
) -> Result<T, MtprotoError> {
    let decoder = lookup_arc(&INSTANCES, handle).ok_or_else(missing)?;
    let mut inner = decoder.inner.lock();
    if !inner.alive {
        return Err(missing());
    }
    body(&mut inner.ctx)
}

fn missing() -> MtprotoError {
    MtprotoError::Message("unknown vpx handle".into())
}

pub fn create_vpx_decoder() -> Result<u64, MtprotoError> {
    #[cfg(not(has_libvpx))]
    {
        return Err(MtprotoError::Message("libvpx not linked".into()));
    }
    #[cfg(has_libvpx)]
    unsafe {
        let mut ctx = VpxCodecCtx {
            name: ptr::null(),
            iface: ptr::null_mut(),
            err: 0,
            err_detail: ptr::null(),
            init_flags: 0,
            config: ptr::null(),
            priv_data: ptr::null_mut(),
        };
        let cfg = VpxCodecDecCfg {
            threads: VPX_DECODER_THREADS,
            w: 0,
            h: 0,
        };
        let iface = vpx_codec_vp9_dx();
        if iface.is_null() {
            return Err(MtprotoError::Message("vp9 decoder missing".into()));
        }
        let err = vpx_codec_dec_init_ver(&mut ctx, iface, &cfg, 0, VPX_DECODER_ABI_VERSION);
        if err != 0 {
            return Err(MtprotoError::Message(format!("vpx init failed: {err}")));
        }
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        INSTANCES.lock().insert(
            id,
            Arc::new(Decoder {
                inner: Mutex::new(DecoderInner { ctx, alive: true }),
            }),
        );
        Ok(id)
    }
}

pub fn destroy_vpx_decoder(handle: u64) {
    let Some(decoder) = INSTANCES.lock().remove(&handle) else {
        return;
    };
    let mut inner = decoder.inner.lock();
    inner.alive = false;
    #[cfg(has_libvpx)]
    unsafe {
        let _ = vpx_codec_destroy(&mut inner.ctx);
    }
}

pub fn decode_vpx_packet(
    handle: u64,
    data: Vec<u8>,
) -> Result<Option<crate::VpxFrame>, MtprotoError> {
    #[cfg(not(has_libvpx))]
    {
        let _ = (handle, data);
        return Err(MtprotoError::Message("libvpx not linked".into()));
    }
    #[cfg(has_libvpx)]
    with_decoder(handle, |ctx| unsafe {
        const MAX_PACKET_SIZE: usize = 4 * 1024 * 1024;
        if data.is_empty() || data.len() > MAX_PACKET_SIZE {
            return Err(MtprotoError::Message("invalid vpx packet size".into()));
        }
        let packet_size = c_uint::try_from(data.len())
            .map_err(|_| MtprotoError::Message("vpx packet is too large".into()))?;
        let err = vpx_codec_decode(ctx, data.as_ptr(), packet_size, ptr::null_mut(), 0);
        if err != 0 {
            return Err(MtprotoError::Message(format!("vpx decode failed: {err}")));
        }
        let mut iter: *const c_void = ptr::null();
        let image = vpx_codec_get_frame(ctx, &mut iter);
        if image.is_null() {
            return Ok(None);
        }
        Ok(Some(image_to_rgba(&*image)?))
    })
}

pub fn decode_vpx_alpha_packet(
    handle: u64,
    data: Vec<u8>,
) -> Result<Option<crate::VpxAlphaFrame>, MtprotoError> {
    #[cfg(not(has_libvpx))]
    {
        let _ = (handle, data);
        return Err(MtprotoError::Message("libvpx not linked".into()));
    }
    #[cfg(has_libvpx)]
    with_decoder(handle, |ctx| unsafe {
        const MAX_PACKET_SIZE: usize = 4 * 1024 * 1024;
        if data.is_empty() || data.len() > MAX_PACKET_SIZE {
            return Err(MtprotoError::Message("invalid vpx packet size".into()));
        }
        let err = vpx_codec_decode(
            ctx,
            data.as_ptr(),
            c_uint::try_from(data.len())
                .map_err(|_| MtprotoError::Message("vpx packet is too large".into()))?,
            ptr::null_mut(),
            0,
        );
        if err != 0 {
            return Err(MtprotoError::Message(format!("vpx decode failed: {err}")));
        }
        let mut iter: *const c_void = ptr::null();
        let image = vpx_codec_get_frame(ctx, &mut iter);
        if image.is_null() {
            return Ok(None);
        }
        let image = &*image;
        let width = image.d_w;
        let height = image.d_h;
        const MAX_DIMENSION: u32 = 2048;
        if width == 0
            || height == 0
            || width > MAX_DIMENSION
            || height > MAX_DIMENSION
            || image.w < width
            || image.h < height
        {
            return Err(MtprotoError::Message("invalid vpx alpha dimensions".into()));
        }
        let (out_w, out_h) = fitted_output(width, height);
        let w = width as usize;
        let h = height as usize;
        let stride = usize::try_from(image.stride[0])
            .map_err(|_| MtprotoError::Message("invalid vpx alpha stride".into()))?;
        if image.planes[0].is_null() || stride < w {
            return Err(MtprotoError::Message("invalid vpx alpha plane".into()));
        }
        let dst_w = out_w as usize;
        let dst_h = out_h as usize;
        let mut alpha =
            vec![
                0u8;
                dst_w
                    .checked_mul(dst_h)
                    .ok_or_else(|| MtprotoError::Message("vpx alpha frame is too large".into()))?
            ];
        for row in 0..dst_h {
            let src_row = row * h / dst_h;
            for col in 0..dst_w {
                let src_col = col * w / dst_w;
                alpha[row * dst_w + col] = *image.planes[0].add(src_row * stride + src_col);
            }
        }
        Ok(Some(crate::VpxAlphaFrame {
            width: out_w,
            height: out_h,
            alpha,
        }))
    })
}
fn image_to_rgba(image: &VpxImage) -> Result<crate::VpxFrame, MtprotoError> {
    const MAX_DIMENSION: u32 = 2048;
    let width = image.d_w;
    let height = image.d_h;
    if width == 0 || height == 0 || width > MAX_DIMENSION || height > MAX_DIMENSION {
        return Err(MtprotoError::Message("invalid vpx frame dimensions".into()));
    }
    let (out_w, out_h) = fitted_output(width, height);
    let w = width as usize;
    let h = height as usize;
    let rgba_len = (out_w as usize)
        .checked_mul(out_h as usize)
        .and_then(|pixels| pixels.checked_mul(4))
        .ok_or_else(|| MtprotoError::Message("vpx frame is too large".into()))?;
    if image.w < width || image.h < height || image.w > MAX_DIMENSION || image.h > MAX_DIMENSION {
        return Err(MtprotoError::Message(
            "invalid vpx storage dimensions".into(),
        ));
    }

    let y_plane = image.planes[0];
    let mut u_plane = image.planes[1];
    let mut v_plane = image.planes[2];
    if y_plane.is_null() || u_plane.is_null() || v_plane.is_null() {
        return Err(MtprotoError::Message("vpx frame missing planes".into()));
    }
    if image.fmt == VPX_IMG_FMT_YV12 {
        std::mem::swap(&mut u_plane, &mut v_plane);
    } else if image.fmt != VPX_IMG_FMT_I420 {
        return Err(MtprotoError::Message(format!(
            "unsupported vpx format {}",
            image.fmt
        )));
    }
    let y_stride = usize::try_from(image.stride[0])
        .map_err(|_| MtprotoError::Message("invalid vpx luma stride".into()))?;
    let u_stride = usize::try_from(image.stride[1])
        .map_err(|_| MtprotoError::Message("invalid vpx chroma stride".into()))?;
    let v_stride = usize::try_from(image.stride[2])
        .map_err(|_| MtprotoError::Message("invalid vpx chroma stride".into()))?;
    let chroma_width = (w + 1) / 2;
    let chroma_height = (h + 1) / 2;
    if y_stride < w || u_stride < chroma_width || v_stride < chroma_width {
        return Err(MtprotoError::Message("invalid vpx plane stride".into()));
    }

    let _ = y_stride
        .checked_mul(h.saturating_sub(1))
        .and_then(|offset| offset.checked_add(w))
        .ok_or_else(|| MtprotoError::Message("invalid vpx luma plane".into()))?;
    let _ = u_stride
        .checked_mul(chroma_height.saturating_sub(1))
        .and_then(|offset| offset.checked_add(chroma_width))
        .ok_or_else(|| MtprotoError::Message("invalid vpx chroma plane".into()))?;
    let _ = v_stride
        .checked_mul(chroma_height.saturating_sub(1))
        .and_then(|offset| offset.checked_add(chroma_width))
        .ok_or_else(|| MtprotoError::Message("invalid vpx chroma plane".into()))?;
    let mut rgba = vec![0u8; rgba_len];
    let dst_w = out_w as usize;
    let dst_h = out_h as usize;
    unsafe {
        for row in 0..dst_h {
            let src_row = row * h / dst_h;
            let y_row = y_plane.add(src_row * y_stride);
            let uv_row = src_row / 2;
            let u_row = u_plane.add(uv_row * u_stride);
            let v_row = v_plane.add(uv_row * v_stride);
            for col in 0..dst_w {
                let src_col = col * w / dst_w;
                let y = (*y_row.add(src_col) as i32) - 16;
                let u = (*u_row.add(src_col / 2) as i32) - 128;
                let v = (*v_row.add(src_col / 2) as i32) - 128;
                let y1192 = 1192 * y.max(0);
                let r = clip((y1192 + 1634 * v) >> 10);
                let g = clip((y1192 - 833 * v - 400 * u) >> 10);
                let b = clip((y1192 + 2066 * u) >> 10);
                let o = (row * dst_w + col) * 4;
                rgba[o] = r;
                rgba[o + 1] = g;
                rgba[o + 2] = b;
                rgba[o + 3] = 255;
            }
        }
    }
    Ok(crate::VpxFrame {
        width: out_w,
        height: out_h,
        rgba,
    })
}

const VPX_MAX_OUTPUT: u32 = 512;

fn fitted_output(width: u32, height: u32) -> (u32, u32) {
    let long_side = width.max(height).max(1);
    if long_side <= VPX_MAX_OUTPUT {
        return (width.max(1), height.max(1));
    }
    let w = (width as u64 * VPX_MAX_OUTPUT as u64 / long_side as u64).max(1) as u32;
    let h = (height as u64 * VPX_MAX_OUTPUT as u64 / long_side as u64).max(1) as u32;
    (w, h)
}

fn clip(value: i32) -> u8 {
    value.clamp(0, 255) as u8
}

#[cfg(test)]
mod tests {
    use super::{VPX_DECODER_THREADS, VPX_MAX_OUTPUT, fitted_output, lookup_arc};
    use crate::{HashMap, HashMapExt};
    use parking_lot::Mutex;
    use std::sync::Arc;

    #[test]
    fn lookup_releases_the_instance_map_before_decode() {
        let mut values = HashMap::new();
        values.insert(1u64, Arc::new(5u32));
        let map = Mutex::new(values);
        let value = lookup_arc(&map, 1).expect("present");
        assert!(
            map.try_lock().is_some(),
            "map lock must be free after lookup"
        );
        assert_eq!(*value, 5);
    }

    #[test]
    fn decoder_threads_stay_one() {
        assert_eq!(VPX_DECODER_THREADS, 1);
    }

    #[test]
    fn output_stays_inside_one_megabyte() {
        assert_eq!(fitted_output(512, 512), (512, 512));
        assert_eq!(
            fitted_output(2048, 1024),
            (VPX_MAX_OUTPUT, VPX_MAX_OUTPUT / 2)
        );
        let (w, h) = fitted_output(2048, 2048);
        assert!(w <= VPX_MAX_OUTPUT && h <= VPX_MAX_OUTPUT);
        assert!(w as usize * h as usize * 4 <= 1024 * 1024);
    }
}
