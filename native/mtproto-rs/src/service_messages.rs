//! Chat service messages (`messageService` + `MessageAction`).
//! https://core.telegram.org/type/MessageAction

use std::collections::HashMap;

use tellers_mtproto::latest::api::{ChatTheme, MessageAction};

use crate::peers::vector_items;

const UNIT: char = '\u{1f}';

pub fn encode_service(kind: &str, actor: &str, extra: &str) -> String {
    format!("{kind}{UNIT}{actor}{UNIT}{extra}")
}

pub fn service_action_text(
    action: &MessageAction,
    actor_id: Option<i64>,
    actor: &str,
    titles: &HashMap<i64, String>,
) -> String {
    let (kind, extra) = action_parts(action, actor_id, titles);
    encode_service(kind, actor, &extra)
}

fn name_of(id: i64, titles: &HashMap<i64, String>) -> String {
    titles
        .get(&id)
        .cloned()
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| "User".into())
}

fn names_of(ids: &[i64], titles: &HashMap<i64, String>) -> String {
    ids.iter()
        .map(|id| name_of(*id, titles))
        .collect::<Vec<_>>()
        .join(", ")
}

fn action_parts(
    action: &MessageAction,
    actor_id: Option<i64>,
    titles: &HashMap<i64, String>,
) -> (&'static str, String) {
    match action {
        MessageAction::MessageActionEmpty(_) => ("empty", String::new()),
        MessageAction::MessageActionChatCreate(value) => ("create", value.title.clone()),
        MessageAction::MessageActionChatEditTitle(value) => ("title", value.title.clone()),
        MessageAction::MessageActionChatEditPhoto(_) => ("photo", String::new()),
        MessageAction::MessageActionChatDeletePhoto(_) => ("photo_remove", String::new()),
        MessageAction::MessageActionChatAddUser(value) => {
            ("add", names_of(vector_items(value.users.as_ref()), titles))
        }
        MessageAction::MessageActionChatDeleteUser(value) => {
            let kind = if actor_id == Some(value.user_id) {
                "leave"
            } else {
                "kick"
            };
            (kind, name_of(value.user_id, titles))
        }
        MessageAction::MessageActionChatJoinedByLink(_) => ("join_link", String::new()),
        MessageAction::MessageActionChannelCreate(value) => ("channel_create", value.title.clone()),
        MessageAction::MessageActionChatMigrateTo(_) => ("migrate_to", String::new()),
        MessageAction::MessageActionChannelMigrateFrom(value) => {
            ("migrate_from", value.title.clone())
        }
        MessageAction::MessageActionPinMessage(_) => ("pin", String::new()),
        MessageAction::MessageActionHistoryClear(_) => ("history_clear", String::new()),
        MessageAction::MessageActionGameScore(value) => ("game_score", value.score.to_string()),
        MessageAction::MessageActionPaymentSentMe(_)
        | MessageAction::MessageActionPaymentSent(_) => ("payment", String::new()),
        MessageAction::MessageActionPhoneCall(_) => ("call", String::new()),
        MessageAction::MessageActionScreenshotTaken(_) => ("screenshot", String::new()),
        MessageAction::MessageActionCustomAction(value) => ("custom", value.message.clone()),
        MessageAction::MessageActionBotAllowed(_) => ("bot_allowed", String::new()),
        MessageAction::MessageActionSecureValuesSentMe(_)
        | MessageAction::MessageActionSecureValuesSent(_) => ("passport", String::new()),
        MessageAction::MessageActionContactSignUp(_) => ("contact_signup", String::new()),
        MessageAction::MessageActionGeoProximityReached(_) => ("proximity", String::new()),
        MessageAction::MessageActionGroupCall(_) => ("group_call", String::new()),
        MessageAction::MessageActionInviteToGroupCall(value) => (
            "invite_call",
            names_of(vector_items(value.users.as_ref()), titles),
        ),
        MessageAction::MessageActionSetMessagesTtl(value) => ("ttl", value.period.to_string()),
        MessageAction::MessageActionGroupCallScheduled(_) => ("call_scheduled", String::new()),
        MessageAction::MessageActionSetChatTheme(value) => {
            let theme = match value.theme.as_ref() {
                ChatTheme::ChatTheme(theme) => theme.emoticon.clone(),
                _ => String::new(),
            };
            ("theme", theme)
        }
        MessageAction::MessageActionChatJoinedByRequest(_) => ("joined_request", String::new()),
        MessageAction::MessageActionWebViewDataSentMe(value) => ("webview", value.text.clone()),
        MessageAction::MessageActionWebViewDataSent(value) => ("webview", value.text.clone()),
        MessageAction::MessageActionGiftPremium(value) => ("gift_premium", value.days.to_string()),
        MessageAction::MessageActionTopicCreate(value) => ("topic_create", value.title.clone()),
        MessageAction::MessageActionTopicEdit(_) => ("topic_edit", String::new()),
        MessageAction::MessageActionSuggestProfilePhoto(_) => ("suggest_photo", String::new()),
        MessageAction::MessageActionRequestedPeer(_)
        | MessageAction::MessageActionRequestedPeerSentMe(_) => ("requested_peer", String::new()),
        MessageAction::MessageActionSetChatWallPaper(_) => ("wallpaper", String::new()),
        MessageAction::MessageActionGiftCode(_) => ("gift_code", String::new()),
        MessageAction::MessageActionGiveawayLaunch(_) => ("giveaway", String::new()),
        MessageAction::MessageActionGiveawayResults(value) => {
            ("giveaway_results", value.winners_count.to_string())
        }
        MessageAction::MessageActionBoostApply(value) => ("boost", value.boosts.to_string()),
        MessageAction::MessageActionPaymentRefunded(_) => ("payment_refund", String::new()),
        MessageAction::MessageActionGiftStars(value) => ("gift_stars", value.stars.to_string()),
        MessageAction::MessageActionPrizeStars(value) => ("prize_stars", value.stars.to_string()),
        MessageAction::MessageActionStarGift(_) => ("star_gift", String::new()),
        MessageAction::MessageActionStarGiftUnique(_) => ("star_gift_unique", String::new()),
        MessageAction::MessageActionPaidMessagesRefunded(_) => ("paid_refund", String::new()),
        MessageAction::MessageActionPaidMessagesPrice(value) => {
            ("paid_price", value.stars.to_string())
        }
        MessageAction::MessageActionConferenceCall(_) => ("conference_call", String::new()),
        MessageAction::MessageActionTodoCompletions(_) => ("todo", String::new()),
        MessageAction::MessageActionTodoAppendTasks(_) => ("todo_add", String::new()),
        MessageAction::MessageActionSuggestedPostApproval(_) => ("suggested_post", String::new()),
        MessageAction::MessageActionSuggestedPostSuccess(_) => ("suggested_post_ok", String::new()),
        MessageAction::MessageActionSuggestedPostRefund(_) => {
            ("suggested_post_refund", String::new())
        }
        MessageAction::MessageActionGiftTon(_) => ("gift_ton", String::new()),
        MessageAction::MessageActionSuggestBirthday(_) => ("suggest_birthday", String::new()),
        _ => ("unknown", String::new()),
    }
}

#[cfg(test)]
mod tests {
    use super::encode_service;

    #[test]
    fn encode_uses_unit_separator() {
        let raw = encode_service("pin", "Ada", "");
        assert_eq!(raw, "pin\u{1f}Ada\u{1f}");
        let add = encode_service("add", "Ada", "Bob, Carol");
        assert_eq!(add, "add\u{1f}Ada\u{1f}Bob, Carol");
    }
}
