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
mod tests {
    use super::*;
    #[test]
    fn missing_packet_closes_gap_without_difference() {
        let mut buffer = PushBuffer::default();
        let now = Instant::now();
        let mut cursor = 0;
        buffer.append(vec![vec![2]]);
        let mut apply = |body: &[u8]| -> Result<bool, ()> {
            if body[0] > cursor + 1 {
                return Ok(false);
            }
            cursor = cursor.max(body[0]);
            Ok(true)
        };
        assert!(!buffer.resolve(now, &mut apply).unwrap());
        buffer.append(vec![vec![1]]);
        assert!(
            !buffer
                .resolve(now + Duration::from_millis(400), &mut apply)
                .unwrap()
        );
        assert!(buffer.is_empty());
        assert_eq!(cursor, 2);
    }

    #[test]
    fn unresolved_gap_expires_once_without_extending_on_more_packets() {
        let mut buffer = PushBuffer::default();
        let now = Instant::now();
        buffer.append(vec![vec![2]]);
        assert!(!buffer.resolve(now, |_| Ok::<_, ()>(false)).unwrap());
        buffer.append(vec![vec![3]]);
        assert!(
            buffer
                .resolve(now + Duration::from_millis(500), |_| Ok::<_, ()>(false))
                .unwrap()
        );
        assert!(buffer.is_empty());
        assert!(
            !buffer
                .resolve(now + Duration::from_secs(1), |_| Ok::<_, ()>(false))
                .unwrap()
        );
    }

    #[test]
    fn queue_overflow_requires_recovery_without_applying_partial_queue() {
        let mut buffer = PushBuffer::default();
        buffer.append(vec![vec![0]; 257]);
        assert!(
            buffer
                .resolve(Instant::now(), |_| -> Result<bool, ()> {
                    panic!("overflow applied")
                })
                .unwrap()
        );
        assert!(buffer.is_empty());
    }
}
