use crate::{HashMap, HashMapExt, MtprotoError};
use parking_lot::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::LazyLock;

const BINS: usize = 64;
const MAX_SAMPLES: u64 = 128 * 1024 * 1024;

struct Accumulator {
    duration_us: u64,
    peaks: [f32; BINS],
    samples: u64,
}

static NEXT: AtomicU64 = AtomicU64::new(1);
static ACCUMULATORS: LazyLock<Mutex<HashMap<u64, Accumulator>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

pub fn create(duration_us: u64) -> Result<u64, MtprotoError> {
    if duration_us == 0 || duration_us > 30 * 60 * 1_000_000 {
        return Err(MtprotoError::Message("invalid waveform duration".into()));
    }
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    ACCUMULATORS.lock().insert(id, Accumulator { duration_us, peaks: [0.0; BINS], samples: 0 });
    Ok(id)
}

pub fn add_pcm(handle: u64, samples: Vec<i16>, sample_rate: u32, channels: u32, presentation_time_us: u64) -> Result<(), MtprotoError> {
    if sample_rate == 0 || sample_rate > 384_000 || channels == 0 || channels > 8 {
        return Err(MtprotoError::Message("invalid waveform PCM format".into()));
    }
    if samples.len() as u64 > MAX_SAMPLES {
        return Err(MtprotoError::Message("waveform PCM chunk is too large".into()));
    }
    let mut accumulators = ACCUMULATORS.lock();
    let accumulator = accumulators.get_mut(&handle).ok_or_else(|| MtprotoError::Message("unknown waveform handle".into()))?;
    let frames = samples.len() / channels as usize;
    for frame in 0..frames {
        let mut peak = 0.0f32;
        for channel in 0..channels as usize {
            peak = peak.max((samples[frame * channels as usize + channel] as f32 / 32768.0).abs());
        }
        let time_us = presentation_time_us.saturating_add(frame as u64 * 1_000_000 / sample_rate as u64);
        let bin = ((time_us as u128 * BINS as u128) / accumulator.duration_us as u128).min((BINS - 1) as u128) as usize;
        accumulator.peaks[bin] = accumulator.peaks[bin].max(peak.min(1.0));
    }
    accumulator.samples = accumulator.samples.saturating_add(samples.len() as u64);
    if accumulator.samples > MAX_SAMPLES * channels as u64 {
        return Err(MtprotoError::Message("waveform PCM exceeds limit".into()));
    }
    Ok(())
}

pub fn finish(handle: u64) -> Result<Vec<f32>, MtprotoError> {
    let accumulator = ACCUMULATORS.lock().remove(&handle).ok_or_else(|| MtprotoError::Message("unknown waveform handle".into()))?;
    if accumulator.samples == 0 { return Ok(Vec::new()); }
    let max = accumulator.peaks.iter().copied().fold(0.0f32, f32::max);
    if max <= 0.0 { return Ok(accumulator.peaks.to_vec()); }
    Ok(accumulator.peaks.iter().map(|value| (value / max).clamp(0.0, 1.0)).collect())
}

pub fn destroy(handle: u64) { ACCUMULATORS.lock().remove(&handle); }
