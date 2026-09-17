//! VP9 video-sticker decode via slim libvpx.
#![allow(unsafe_code)]

use crate::{HashMap, HashMapExt};
use std::os::raw::{c_char, c_int, c_long, c_uint, c_void};
#[cfg(has_libvpx)]
use std::ptr;
use std::sync::LazyLock;
use std::sync::atomic::{AtomicU64, Ordering};

use parking_lot::Mutex;

use crate::MtprotoError;

const VPX_DECODER_ABI_VERSION: c_int = 12;
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

struct Decoder {
    ctx: VpxCodecCtx,
}

unsafe impl Send for Decoder {}
unsafe impl Sync for Decoder {}

static NEXT: AtomicU64 = AtomicU64::new(1);
static INSTANCES: LazyLock<Mutex<HashMap<u64, Decoder>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

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
            threads: 1,
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
        INSTANCES.lock().insert(id, Decoder { ctx });
        Ok(id)
    }
}

pub fn destroy_vpx_decoder(handle: u64) {
    let Some(mut decoder) = INSTANCES.lock().remove(&handle) else {
        return;
    };
    #[cfg(has_libvpx)]
    unsafe {
        let _ = vpx_codec_destroy(&mut decoder.ctx);
    }
    let _ = decoder;
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
    unsafe {
        let mut instances = INSTANCES.lock();
        let decoder = instances.get_mut(&handle).ok_or_else(missing)?;
        let err = vpx_codec_decode(
            &mut decoder.ctx,
            data.as_ptr(),
            data.len() as c_uint,
            ptr::null_mut(),
            0,
        );
        if err != 0 {
            return Err(MtprotoError::Message(format!("vpx decode failed: {err}")));
        }
        let mut iter: *const c_void = ptr::null();
        let image = vpx_codec_get_frame(&mut decoder.ctx, &mut iter);
        if image.is_null() {
            return Ok(None);
        }
        Ok(Some(image_to_rgba(&*image)?))
    }
}

fn image_to_rgba(image: &VpxImage) -> Result<crate::VpxFrame, MtprotoError> {
    let width = image.d_w.max(1);
    let height = image.d_h.max(1);
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
    let y_stride = image.stride[0] as usize;
    let u_stride = image.stride[1] as usize;
    let v_stride = image.stride[2] as usize;
    let w = width as usize;
    let h = height as usize;
    let mut rgba = vec![0u8; w.saturating_mul(h).saturating_mul(4)];
    unsafe {
        for row in 0..h {
            let y_row = y_plane.add(row * y_stride);
            let uv_row = row / 2;
            let u_row = u_plane.add(uv_row * u_stride);
            let v_row = v_plane.add(uv_row * v_stride);
            for col in 0..w {
                let y = (*y_row.add(col) as i32) - 16;
                let u = (*u_row.add(col / 2) as i32) - 128;
                let v = (*v_row.add(col / 2) as i32) - 128;
                let y1192 = 1192 * y.max(0);
                let r = clip((y1192 + 1634 * v) >> 10);
                let g = clip((y1192 - 833 * v - 400 * u) >> 10);
                let b = clip((y1192 + 2066 * u) >> 10);
                let o = (row * w + col) * 4;
                rgba[o] = r;
                rgba[o + 1] = g;
                rgba[o + 2] = b;
                rgba[o + 3] = 255;
            }
        }
    }
    Ok(crate::VpxFrame {
        width,
        height,
        rgba,
    })
}

fn clip(value: i32) -> u8 {
    value.clamp(0, 255) as u8
}
