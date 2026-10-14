//! Replayable journal of update batches. A batch lives in the encrypted session
//! file next to the cursor it advanced, and stays there until the application
//! acknowledges that it committed the batch.
use std::collections::VecDeque;

use serde::{Deserialize, Serialize};

use crate::{UpdateEventDto, UpdatesStateDto};

/// Admission closes at either bound; recovery work stays in the journal.
pub(crate) const MAX_UNACKED_BATCHES: usize = 256;
pub(crate) const MAX_UNACKED_BYTES: usize = 8 * 1024 * 1024;

#[derive(Clone, Debug, Serialize, Deserialize)]
pub(crate) struct DurableBatch {
    pub batch_id: i64,
    pub events: Vec<UpdateEventDto>,
    pub cursor_after: Option<UpdatesStateDto>,
    pub channel_pts_after: Vec<(i64, i32)>,
    #[serde(default)]
    pub approx_bytes: usize,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub(crate) struct UpdateJournal {
    /// Random per account so a recreated session file cannot collide with receipts
    /// the application stored for an earlier journal.
    pub account_generation: i64,
    pub next_batch_id: i64,
    pub batches: VecDeque<DurableBatch>,
    /// Leading batches already handed to the application in this process.
    #[serde(skip)]
    delivered: usize,
}

fn random_generation() -> i64 {
    let mut bytes = [0_u8; 8];
    if tellers_mtproto_crypto::fill_random(&mut bytes).is_err() {
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos() as u64;
        bytes = nanos.to_le_bytes();
    }
    let value = (u64::from_le_bytes(bytes) >> 1) as i64;
    value.max(1)
}

impl Default for UpdateJournal {
    fn default() -> Self {
        Self {
            account_generation: random_generation(),
            next_batch_id: 1,
            batches: VecDeque::new(),
            delivered: 0,
        }
    }
}

/// Typing, presence and sync edges are recomputed from live traffic; everything
/// that mutates stored application state must be replayable.
pub(crate) fn is_durable(event: &UpdateEventDto) -> bool {
    !matches!(
        event,
        UpdateEventDto::PeerTyping { .. }
            | UpdateEventDto::PeerStatus { .. }
            | UpdateEventDto::PeerEmojiStatus { .. }
            | UpdateEventDto::SyncState { .. }
            | UpdateEventDto::SavedGifsChanged
    ) && !matches!(event, UpdateEventDto::Ignored { kind } if !is_durable_ignored(kind))
}

/// `Ignored` carries a few state mutations encoded in `kind`; the rest are noise.
fn is_durable_ignored(kind: &str) -> bool {
    [
        "dialog_unread_mark:",
        "notify_settings:",
        "unread_mentions_delta:",
        "unread_reactions_delta:",
    ]
    .iter()
    .any(|prefix| kind.starts_with(prefix))
}

impl UpdateJournal {
    /// Drops every batch: they belong to an account that is gone.
    pub(crate) fn header_only(&self) -> Self {
        Self {
            account_generation: self.account_generation,
            next_batch_id: self.next_batch_id,
            batches: VecDeque::new(),
            delivered: 0,
        }
    }

    pub(crate) fn reset_account(&mut self) {
        let previous = self.account_generation;
        *self = Self::default();
        if self.account_generation == previous {
            self.account_generation = previous.wrapping_add(1).max(1);
        }
    }

    fn unacked_bytes(&self) -> usize {
        self.batches.iter().map(|b| b.approx_bytes).sum()
    }

    pub(crate) fn admission_open(&self) -> bool {
        self.batches.len() < MAX_UNACKED_BATCHES && self.unacked_bytes() < MAX_UNACKED_BYTES
    }

    pub(crate) fn has_undelivered(&self) -> bool {
        self.delivered < self.batches.len()
    }

    pub(crate) fn push(
        &mut self,
        events: Vec<UpdateEventDto>,
        cursor_after: Option<UpdatesStateDto>,
        channel_pts_after: Vec<(i64, i32)>,
    ) -> DurableBatch {
        let approx_bytes = serde_json::to_vec(&events).map(|v| v.len()).unwrap_or(0);
        let batch = DurableBatch {
            batch_id: self.next_batch_id,
            events,
            cursor_after,
            channel_pts_after,
            approx_bytes,
        };
        self.next_batch_id = self.next_batch_id.saturating_add(1);
        self.batches.push_back(batch.clone());
        batch
    }

    pub(crate) fn mark_delivered(&mut self, batch_id: i64) {
        if let Some(index) = self.batches.iter().position(|b| b.batch_id == batch_id) {
            self.delivered = self.delivered.max(index + 1);
        }
    }

    pub(crate) fn mark_all_delivered(&mut self) {
        self.delivered = self.batches.len();
    }

    pub(crate) fn pending(&self) -> Vec<DurableBatch> {
        self.batches.iter().cloned().collect()
    }

    /// Returns true when a batch was removed.
    pub(crate) fn acknowledge(&mut self, account_generation: i64, batch_id: i64) -> bool {
        if account_generation != self.account_generation {
            return false;
        }
        let Some(index) = self.batches.iter().position(|b| b.batch_id == batch_id) else {
            return false;
        };
        self.batches.remove(index);
        if index < self.delivered {
            self.delivered -= 1;
        }
        true
    }
}

#[cfg(test)]
#[path = "../tests/unit/update_journal_tests.rs"]
mod tests;
