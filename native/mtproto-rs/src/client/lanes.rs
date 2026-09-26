use parking_lot::Mutex;
use tellers_mtproto_session::Snapshot;

use crate::MtprotoError;
use crate::scheduler;

use super::*;

/// In-session pipelining depth. Speed-up uses 12 x 512 KiB.
pub(crate) const DEFAULT_PIPELINE_PARTS: usize = 12;

pub(crate) const MAX_PIPELINE_PARTS: usize = 16;

pub(crate) static PIPELINE_PARTS: std::sync::atomic::AtomicUsize =
    std::sync::atomic::AtomicUsize::new(DEFAULT_PIPELINE_PARTS);

pub(crate) fn pipeline_parts() -> usize {
    PIPELINE_PARTS
        .load(std::sync::atomic::Ordering::Relaxed)
        .clamp(1, MAX_PIPELINE_PARTS)
}

pub fn set_pipeline_parts(parts: usize) {
    PIPELINE_PARTS.store(
        parts.clamp(1, MAX_PIPELINE_PARTS),
        std::sync::atomic::Ordering::Relaxed,
    );
}

/// Main RPCs and updates share one TCP and one sequence-number space.
/// Additional main sessions require explicit server permission (tmp_sessions).
pub(crate) struct SessionIo {
    pub(crate) pending_push: crate::update_buffer::PushBuffer,
    pub(crate) last_difference: Option<std::time::Instant>,
    pub(crate) snapshot: Snapshot,
    pub(crate) transport: Option<crate::rpc::LiveTransport>,
}

/// A session plus the gate that decides which request class gets it next.
pub(crate) struct Lane {
    pub(crate) gate: crate::scheduler::LaneGate,
    pub(crate) io: Mutex<SessionIo>,
}

pub(crate) struct LaneLease<'a> {
    pub(crate) _gate: crate::scheduler::LaneGuard<'a>,
    pub(crate) io: parking_lot::MutexGuard<'a, SessionIo>,
}

impl std::ops::Deref for LaneLease<'_> {
    type Target = SessionIo;
    fn deref(&self) -> &SessionIo {
        &self.io
    }
}

impl std::ops::DerefMut for LaneLease<'_> {
    fn deref_mut(&mut self) -> &mut SessionIo {
        &mut self.io
    }
}

impl Lane {
    pub(crate) fn new(io: SessionIo) -> Self {
        Self {
            gate: crate::scheduler::LaneGate::new(),
            io: Mutex::new(io),
        }
    }

    pub(crate) fn try_lease(&self, class: crate::scheduler::RequestClass) -> Option<LaneLease<'_>> {
        let gate = self.gate.try_acquire(class)?;
        Some(LaneLease {
            _gate: gate,
            io: self.io.lock(),
        })
    }

    pub(crate) fn lease(
        &self,
        class: crate::scheduler::RequestClass,
    ) -> Result<LaneLease<'_>, MtprotoError> {
        let gate = self.gate.acquire(class)?;
        Ok(LaneLease {
            _gate: gate,
            io: self.io.lock(),
        })
    }
}

pub(crate) fn lock_request_lane<'a>(
    lane: &'a Mutex<SessionIo>,
    op: &'static str,
) -> Result<parking_lot::MutexGuard<'a, SessionIo>, MtprotoError> {
    let wait = crate::perf::span(op);
    loop {
        crate::request_control::check().map_err(|e| MtprotoError::Message(e.to_string()))?;
        if let Some(guard) = lane.try_lock_for(std::time::Duration::from_millis(50)) {
            crate::request_control::check().map_err(|e| MtprotoError::Message(e.to_string()))?;
            drop(wait);
            return Ok(guard);
        }
    }
}

/// Read-lane lease; the class is resolved through `scheduler::family`.
pub(crate) fn acquire_read_lane(client: &Client) -> Result<LaneLease<'_>, MtprotoError> {
    let class = scheduler::current_class();
    debug_assert_eq!(scheduler::family(class), scheduler::LaneFamily::Read);
    match scheduler::family(class) {
        scheduler::LaneFamily::Read => acquire_lane_family(
            scheduler::read_lane_order(),
            class,
            "lane_wait.read",
            "read_lane_wait",
            |index| &client.rpc[index],
        ),
        scheduler::LaneFamily::Media => Err(MtprotoError::Message(
            "media class on the read family".into(),
        )),
    }
}

pub(crate) fn lock_media_lane(client: &Client) -> Result<LaneLease<'_>, MtprotoError> {
    let class = scheduler::current_class();
    match scheduler::family(class) {
        scheduler::LaneFamily::Media => acquire_lane_family(
            scheduler::media_lane_order(),
            class,
            "lane_wait.media",
            "media_lane_wait",
            |index| &client.media[index],
        ),
        scheduler::LaneFamily::Read => Err(MtprotoError::Message(
            "read class on the media family".into(),
        )),
    }
}

/// Second media-DC TCP if a lane is free. The held first lease makes that gate
/// busy, so this never returns the same lane. Home-DC downloads must not call this.
#[allow(dead_code)]
pub(crate) fn try_lock_second_media_lane(client: &Client) -> Option<LaneLease<'_>> {
    let class = scheduler::current_class();
    if scheduler::family(class) != scheduler::LaneFamily::Media {
        return None;
    }
    for index in scheduler::media_lane_order() {
        if let Some(lease) = client
            .media
            .get(index)
            .and_then(|lane| lane.try_lease(class))
        {
            return Some(lease);
        }
    }
    None
}

pub(crate) fn acquire_lane_family<'a>(
    order: impl Iterator<Item = usize> + Clone,
    class: scheduler::RequestClass,
    span: &'static str,
    wait_counter: &'static str,
    lane_at: impl Fn(usize) -> &'a Lane,
) -> Result<LaneLease<'a>, MtprotoError> {
    let wait = crate::perf::span(span);
    let mut counted = false;
    loop {
        crate::request_control::check().map_err(|e| MtprotoError::Message(e.to_string()))?;
        for index in order.clone() {
            if let Some(lease) = lane_at(index).try_lease(class) {
                drop(wait);
                return Ok(lease);
            }
        }
        if !counted {
            counted = true;
            crate::perf::count(wait_counter);
        }
        let index = order.clone().last().unwrap_or(0);
        let lease = lane_at(index).lease(class)?;
        drop(wait);
        return Ok(lease);
    }
}
