#[derive(Debug, Clone, uniffi::Record)]
pub struct AuthCodeSent {
    pub phone: String,
    pub phone_code_hash: String,
    pub code_type: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct AuthSignedIn {
    pub user_id: i64,
    pub dc_id: i32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ChatDto {
    pub id: i64,
    pub title: String,
    pub is_channel: bool,
    pub is_group: bool,
    pub is_forum: bool,
    /// True if the current user is not a participant of this dialog.
    pub left: bool,
    pub unread_count: i32,
    pub last_message_preview: Option<String>,
    pub last_message_date: Option<i64>,
    pub archived: bool,
    pub muted: bool,
    pub is_contact: bool,
    pub is_bot: bool,
    pub is_verified: bool,
    pub photo_cache_key: Option<String>,
    pub pinned: bool,
    pub read_inbox_max_id: i32,
    pub read_outbox_max_id: i32,
    pub peer_status: Option<String>,
    pub peer_status_at: Option<i64>,
    pub last_media_thumb_cache_key: Option<String>,
    pub last_message_id: i32,
    pub can_view: bool,
    pub can_send_plain: bool,
    pub can_send_photos: bool,
    pub can_forward: bool,
    pub can_delete_others: bool,
    /// Custom emoji / collectible document for the peer name.
    pub emoji_status_document_id: Option<i64>,
    /// Whether the last dialog message was outgoing.
    pub last_message_outgoing: bool,
    /// Whether the dialog carries its own mute setting instead of inheriting the type default.
    pub mute_override: bool,
    /// `dialog.unread_mark`: the user manually marked the dialog unread.
    pub unread_mark: bool,
    /// `dialog.unread_mentions_count`.
    pub unread_mentions_count: i32,
    /// `dialog.unread_reactions_count`.
    pub unread_reactions_count: i32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FolderDto {
    pub id: i32,
    pub title: String,
    pub chat_ids: Vec<i64>,
    pub exclude_chat_ids: Vec<i64>,
    pub include_contacts: bool,
    pub include_non_contacts: bool,
    pub include_groups: bool,
    pub include_channels: bool,
    pub include_bots: bool,
    pub exclude_muted: bool,
    pub exclude_read: bool,
    pub exclude_archived: bool,
    pub emoticon: String,
    /// `dialogFilter.pinned_peers`, distinct from include peers in [chat_ids].
    pub pinned_chat_ids: Vec<i64>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ProfileDto {
    pub id: i64,
    pub kind: String,
    pub title: String,
    pub username: Option<String>,
    pub about: Option<String>,
    pub avatar_cache_key: Option<String>,
    pub is_self: bool,
    pub status: Option<String>,
    pub status_at: Option<i64>,
    /// Compact JSON of extra profile facts (members, phone, badges). Never log.
    pub extra_json: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct MessageDto {
    pub chat_id: i64,
    pub id: i32,
    pub sender_id: Option<i64>,
    pub text: Option<String>,
    pub date: i64,
    pub edit_date: Option<i64>,
    pub outgoing: bool,
    pub media_kind: Option<String>,
    pub media_cache_key: Option<String>,
    pub thumb_cache_key: Option<String>,
    pub media_duration: Option<i32>,
    pub media_width: Option<i32>,
    pub media_height: Option<i32>,
    pub reply_quote: Option<String>,
    /// Compact JSON list of `{kind,offset,length,url?}` message entities.
    pub entities_json: Option<String>,
    pub noforwards: bool,
    pub reply_to_msg_id: Option<i32>,
    pub reply_to_top_id: Option<i32>,
    pub fwd_from: Option<String>,
    pub fwd_from_id: Option<i64>,
    pub fwd_date: Option<i64>,
    pub via_bot: Option<String>,
    pub sender_name: Option<String>,
    pub sender_emoji_status_document_id: Option<i64>,
    pub grouped_id: Option<i64>,
    pub file_name: Option<String>,
    pub file_size: Option<i64>, // album / document meta
    pub reactions_json: Option<String>,
    pub replies_count: i32,
    pub discussion_peer_id: Option<i64>,
    /// Compact JSON for reply/inline keyboards. Never log.
    pub reply_markup_json: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SavedGifDto {
    pub document_id: i64,
    pub cache_key: String,
    pub thumb_cache_key: Option<String>,
    pub width: Option<i32>,
    pub height: Option<i32>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ForumTopicDto {
    pub id: i32,
    pub title: String,
    pub icon_color: i32,
    pub icon_emoji_id: Option<i64>,
    pub top_message: i32,
    pub date: i32,
    pub unread_count: i32,
    pub unread_mentions_count: i32,
    pub read_inbox_max_id: i32,
    pub pinned: bool,
    pub closed: bool,
    pub hidden: bool,
    pub short: bool,
    pub deleted: bool,
    pub last_message_preview: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ForumTopicsPageDto {
    pub count: i32,
    pub topics: Vec<ForumTopicDto>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct DiscussionDto {
    pub chat_id: i64,
    pub message_id: i32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ReactionChoiceDto {
    pub emoticon: String,
    pub document_id: i64,
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum UpdateEventDto {
    ChatsChanged,
    FoldersChanged,
    NewMessage {
        message: MessageDto,
    },
    MessageEdited {
        message: MessageDto,
    },
    MessagesDeleted {
        chat_id: Option<i64>,
        message_ids: Vec<i32>,
    },
    PeerTyping {
        chat_id: i64,
        user_id: i64,
        typing: bool,
        action: String,
    },
    PeerStatus {
        user_id: i64,
        status: Option<String>,
        status_at: Option<i64>,
    },
    PeerEmojiStatus {
        user_id: i64,
        document_id: Option<i64>,
    },
    ReadInbox {
        chat_id: i64,
        max_id: i32,
        still_unread: i32,
    },
    ReadOutbox {
        chat_id: i64,
        max_id: i32,
    },
    SavedGifsChanged,
    MessageReactions {
        chat_id: i64,
        message_id: i32,
        reactions_json: String,
    },
    DiscussionInbox {
        channel_id: i64,
        top_message_id: i32,
        read_max_id: i32,
    },
    Ignored {
        kind: String,
    },
}

#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct NotifySettingsDto {
    pub show_previews: bool,
    pub silent: bool,
    pub mute_until: i32,
    pub stories_muted: bool,
    pub stories_hide_sender: bool,
    pub sound: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct NotifyExceptionDto {
    pub peer_kind: String,
    pub chat_id: i64,
    pub show_previews: bool,
    pub silent: bool,
    pub mute_until: i32,
    pub stories_muted: bool,
    pub stories_hide_sender: bool,
    pub sound: String,
}

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct UpdatesStateDto {
    pub pts: i32,
    pub qts: i32,
    pub date: i32,
    pub seq: i32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct StickerPackDto {
    pub id: i64,
    pub access_hash: i64,
    pub title: String,
    pub short_name: String,
    pub count: i32,
    pub is_emoji: bool,
    pub preview_document_ids: Vec<i64>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct StickerCatalogDto {
    pub hash: i64,
    pub not_modified: bool,
    pub sets: Vec<StickerPackDto>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct StickerListDto {
    pub hash: i64,
    pub not_modified: bool,
    pub document_ids: Vec<i64>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ResolvedPeerDto {
    pub peer_id: i64,
    pub username: Option<String>,
    pub title: String,
    pub is_bot: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct InlineBotResultDto {
    pub id: String,
    pub kind: String,
    pub title: Option<String>,
    pub description: Option<String>,
    pub url: Option<String>,
    pub document_id: Option<i64>,
    pub thumb_cache_key: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct InlineBotResultsDto {
    pub query_id: i64,
    pub gallery: bool,
    pub next_offset: Option<String>,
    pub cache_time: i32,
    pub results: Vec<InlineBotResultDto>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SearchPeerDto {
    pub peer_id: i64,
    pub title: String,
    pub username: Option<String>,
    pub kind: String,
    pub is_bot: bool,
    pub is_group: bool,
    pub is_channel: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ContactsSearchDto {
    pub people: Vec<SearchPeerDto>,
    pub chats: Vec<SearchPeerDto>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GlobalMessageSearchDto {
    pub messages: Vec<MessageDto>,
    pub next_rate: i32,
    pub next_peer_id: i64,
    pub next_offset_id: i32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct BotCallbackAnswerDto {
    pub alert: bool,
    pub message: Option<String>,
    pub url: Option<String>,
    pub cache_time: i32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct UploadItemDto {
    pub path: String,
    pub kind: String,
    pub mime_type: String,
    pub file_name: String,
    pub caption: String,
    pub duration: i32,
    pub width: i32,
    pub height: i32,
    pub random_id: i64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct LottieSize {
    pub width: u32,
    pub height: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct VpxFrame {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}
