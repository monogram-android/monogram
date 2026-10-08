use parking_lot::Mutex;
use tellers_mtproto_session::Snapshot;
use tellers_mtproto_transport::Connection;

use crate::MtprotoError;
use crate::scheduler;

use super::*;

/// Maximum in-session file pipeline; thumbnails and background work use less.
pub(crate) const DEFAULT_PIPELINE_PARTS: usize = 4;

pub(crate) const MAX_PIPELINE_PARTS: usize = 8;

pub(crate) fn pipeline_parts() -> usize {
    crate::transfer_policy::current().pipeline_parts()
}

pub fn set_pipeline_parts(parts: usize) {
    let parts = parts.clamp(1, MAX_PIPELINE_PARTS);
    crate::transfer_policy::default_policy().assign_pipeline_parts(parts);
    crate::client::visit_policies(|policy| policy.follow_pipeline_parts(parts));
}

/// Main RPCs and updates share one TCP and one sequence-number space.
/// Additional main sessions require explicit server permission (tmp_sessions).
pub(crate) struct SessionIo {
    pub(crate) pending_push: crate::update_buffer::PushBuffer,
    pub(crate) last_difference: Option<std::time::Instant>,
    pub(crate) snapshot: Snapshot,
    pub(crate) transport: Option<crate::rpc::LiveTransport>,
    /// Unix time when this lane's temporary auth key expires. Zero means none.
    pub(crate) temp_expires_at: i32,
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
        scheduler::LaneFamily::Media => {
            let cap = client.policy.media_lanes();
            let waiter = client.media_gate.enqueue(class);
            loop {
                crate::request_control::check()
                    .map_err(|e| MtprotoError::Message(e.to_string()))?;
                if !waiter.is_next() {
                    waiter.wait();
                    continue;
                }
                let open = client.media_open.load(Ordering::Acquire).min(cap);
                for lane in client.media.iter().take(open) {
                    if let Some(lease) = lane.try_lease(class) {
                        return Ok(lease);
                    }
                }
                if open < cap {
                    let _ = client.media_open.compare_exchange(
                        open,
                        open + 1,
                        Ordering::AcqRel,
                        Ordering::Acquire,
                    );
                    continue;
                }
                waiter.wait();
            }
        }
        scheduler::LaneFamily::Read => Err(MtprotoError::Message(
            "read class on the media family".into(),
        )),
    }
}

pub(crate) fn close_idle_file_transports(client: &Client, now: std::time::Instant) -> bool {
    let mut pending = false;
    let mut close = |lane: &Mutex<SessionIo>| {
        let Some(mut io) = lane.try_lock() else {
            pending = true;
            return;
        };
        if let Some(transport) = io.transport.as_ref() {
            if now.saturating_duration_since(transport.last_io)
                >= crate::media::DOWNLOAD_SESSION_IDLE
            {
                if let Some(mut transport) = io.transport.take() {
                    let _ = transport.conn.close();
                }
            } else {
                pending = true;
            }
        }
    };
    for lane in &client.media {
        close(&lane.io);
    }
    for lane in &client.upload {
        close(&lane.io);
    }
    if !pending {
        client.media_open.store(1, Ordering::Release);
    }
    pending
}

/// Prefer a free temporary-key upload socket. Lane 0 is the permanent-key fallback.
pub(crate) fn lock_upload_lane(client: &Client) -> parking_lot::MutexGuard<'_, SessionIo> {
    let now = crate::auth::bind_temp::unix_now();
    for lane in client.upload.iter().skip(1) {
        if let Some(io) = lane.io.try_lock() {
            if io.temp_expires_at > now {
                return io;
            }
        }
    }
    client.upload[0].io.lock()
}

pub(crate) fn schedule_file_cleanup(client: &Arc<Client>) {
    if client.file_cleanup_running.swap(true, Ordering::AcqRel) {
        return;
    }
    let weak = Arc::downgrade(client);
    let spawned = std::thread::Builder::new()
        .name("mtproto-maintenance".into())
        .spawn(move || {
            let ack_due = std::time::Instant::now() + crate::rpc::ACK_DELAY;
            loop {
                std::thread::sleep(crate::media::DOWNLOAD_SESSION_IDLE);
                let Some(client) = weak.upgrade() else {
                    break;
                };
                let now = std::time::Instant::now();
                let files_pending = close_idle_file_transports(&client, now);
                if flush_idle_main_acks(&client, now, ack_due) || files_pending {
                    continue;
                }
                client.file_cleanup_running.store(false, Ordering::Release);
                // A transfer may have finished while the worker cleared its flag.
                if (close_idle_file_transports(&client, std::time::Instant::now())
                    || flush_idle_main_acks(&client, std::time::Instant::now(), ack_due))
                    && !client.file_cleanup_running.swap(true, Ordering::AcqRel)
                {
                    continue;
                }
                break;
            }
        });
    if spawned.is_err() {
        client.file_cleanup_running.store(false, Ordering::Release);
    }
}

pub(crate) fn flush_idle_main_acks(
    client: &Client,
    now: std::time::Instant,
    due: std::time::Instant,
) -> bool {
    let Some(_gate) = client
        .main_gate
        .try_acquire(scheduler::RequestClass::BackgroundRead)
    else {
        return true;
    };
    let Some(mut io) = client.main.try_lock() else {
        return true;
    };
    if io.snapshot.pending_acknowledgements.is_empty() || io.transport.is_none() {
        return false;
    }
    if now < due {
        return true;
    }
    let mut transport = io.transport.take().unwrap();
    if crate::tcp::with_connection_control(&client.connections, || {
        crate::rpc::flush_pending_acks(&mut io.snapshot, &mut transport)
    })
    .is_ok()
    {
        io.transport = Some(transport);
    }
    false
}

/// Second file TCP if a lane is free. The held first lease makes that gate
/// busy, so this never returns the same lane. Caller must bind a separate temp key.
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
