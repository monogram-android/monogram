//! Bounded reordering window. A restart recovers from the persisted cursor.
use std::time::{Duration, Instant};

#[derive(Default)]
pub(crate) struct PushBuffer {
    packets: Vec<Vec<u8>>,
    bytes: usize,
    gap_since: Option<Instant>,
    overflow: bool,
}

impl PushBuffer {
    pub(crate) fn is_empty(&self) -> bool {
        self.packets.is_empty() && !self.overflow
    }

    pub(crate) fn append(&mut self, packets: Vec<Vec<u8>>) {
        for packet in packets {
            if self.packets.len() >= 256
                || self.bytes.saturating_add(packet.len()) > 8 * 1024 * 1024
            {
                self.overflow = true;
                break;
            }
            self.bytes += packet.len();
            self.packets.push(packet);
        }
    }

    /// `apply` returns false only for a gap; success includes discarded duplicates.
    /// Revisit blocked packets after any progress, preserving arrival order among
    /// independent streams. Returns true when authoritative recovery is required.
    pub(crate) fn resolve<E>(
        &mut self,
        now: Instant,
        mut apply: impl FnMut(&[u8]) -> Result<bool, E>,
    ) -> Result<bool, E> {
        if self.overflow {
            *self = Self::default();
            return Ok(true);
        }
        loop {
            let mut progress = false;
            let mut index = 0;
            while index < self.packets.len() {
                if apply(&self.packets[index])? {
                    self.bytes -= self.packets.remove(index).len();
                    progress = true;
                } else {
                    index += 1;
                }
            }
            if !progress {
                break;
            }
        }
        if self.packets.is_empty() {
            self.gap_since = None;
            return Ok(false);
        }
        let started = self.gap_since.get_or_insert(now);
        if now.saturating_duration_since(*started) >= Duration::from_millis(500) {
            *self = Self::default();
            return Ok(true);
        }
        Ok(false)
    }
}

#[cfg(test)]
#[path = "../tests/unit/update_buffer_tests.rs"]
mod tests;
