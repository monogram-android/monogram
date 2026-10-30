pub type InstantViewDto = mono_proto_messages::instant_view::InstantViewDto;

pub type WallpaperDto = mono_proto_media::wallpaper::WallpaperDto;

#[uniffi::remote(Record)]
pub struct WallpaperDto {
    pub id: i64,
    pub access_hash: i64,
    pub slug: String,
    pub pattern: bool,
    pub dark: bool,
    pub mime_type: String,
    pub document_id: Option<i64>,
    pub colors: Vec<i32>,
    pub intensity: Option<i32>,
    pub rotation: i32,
    pub blur: bool,
    pub motion: bool,
}

pub type WallpaperCatalogDto = mono_proto_media::wallpaper::WallpaperCatalogDto;

#[uniffi::remote(Record)]
pub struct WallpaperCatalogDto {
    pub hash: i64,
    pub not_modified: bool,
    pub wallpapers: Vec<WallpaperDto>,
}
#[uniffi::remote(Record)]
pub struct InstantViewDto {
    pub url: String,
    pub display_url: String,
    pub title: Option<String>,
    pub site_name: Option<String>,
    pub description: Option<String>,
    pub webpage_type: Option<String>,
    pub hash: i32,
    pub has_instant_view: bool,
    pub part: bool,
    pub rtl: bool,
    pub v2: bool,
    pub not_modified: bool,
    pub blocks_json: String,
}

pub type AuthCodeSent = mono_proto_types::auth::AuthCodeSent;

#[uniffi::remote(Record)]
pub struct AuthCodeSent {
    pub phone: String,
    pub phone_code_hash: String,
    pub code_type: String,
    pub code_length: i32,
}

pub type AuthSignedIn = mono_proto_types::auth::AuthSignedIn;

#[uniffi::remote(Record)]
pub struct AuthSignedIn {
    pub user_id: i64,
    pub dc_id: i32,
}

pub type PasskeyCredentialDto = mono_proto_types::auth::PasskeyCredentialDto;

#[uniffi::remote(Record)]
pub struct PasskeyCredentialDto {
    pub id: String,
    pub raw_id: String,
    pub client_data: String,
    pub authenticator_data: Vec<u8>,
    pub signature: Vec<u8>,
    pub user_handle: String,
    pub attestation_data: Vec<u8>,
}

pub type PasskeyLoginOptionsDto = mono_proto_types::auth::PasskeyLoginOptionsDto;

#[uniffi::remote(Record)]
pub struct PasskeyLoginOptionsDto {
    pub options: String,
    pub dc_id: i32,
    pub auth_key_id: i64,
}

pub type PasskeyDto = mono_proto_types::auth::PasskeyDto;

#[uniffi::remote(Record)]
pub struct PasskeyDto {
    pub id: String,
    pub name: String,
    pub date: i32,
    pub software_emoji_id: Option<i64>,
    pub last_usage_date: Option<i32>,
}

pub type QrLoginDto = mono_proto_types::auth::QrLoginDto;

#[uniffi::remote(Enum)]
pub enum QrLoginDto {
    Token { token: Vec<u8>, expires: i32 },
    Authorized { session: AuthSignedIn },
}

pub type DeviceSessionDto = mono_proto_types::auth::DeviceSessionDto;

#[uniffi::remote(Record)]
pub struct DeviceSessionDto {
    pub hash: i64,
    pub current: bool,
    pub password_pending: bool,
    pub device_model: String,
    pub platform: String,
    pub system_version: String,
    pub app_name: String,
    pub app_version: String,
    pub date_active: i32,
    pub ip: String,
    pub country: String,
    pub region: String,
}

pub type InitConnectionInfo = mono_proto_types::init_connection::InitConnectionInfo;

/// Values sent in `initConnection` on a fresh transport.
/// https://core.telegram.org/api/invoking
#[uniffi::remote(Record)]
pub struct InitConnectionInfo {
    pub device_model: String,
    pub system_version: String,
    pub app_version: String,
    pub system_lang_code: String,
    pub lang_pack: String,
    pub lang_code: String,
}

pub type ChatDto = mono_proto_types::message::ChatDto;

#[uniffi::remote(Record)]
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
    pub can_send_polls: bool,
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
    /// Creator or `manage_topics` admin right.
    pub can_manage_topics: bool,
}

pub type FolderDto = mono_proto_types::dto::FolderDto;

#[uniffi::remote(Record)]
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

pub type ProfileDto = mono_proto_types::dto::ProfileDto;

#[uniffi::remote(Record)]
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

pub type MessageDto = mono_proto_types::message::MessageDto;

#[uniffi::remote(Record)]
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
    /// Compact JSON list of `{kind,offset,length,url?}` entities for `reply_quote`.
    pub reply_quote_entities_json: Option<String>,
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
    pub supports_streaming: bool,
    pub reactions_json: Option<String>,
    pub replies_count: i32,
    pub discussion_peer_id: Option<i64>,
    /// Compact JSON for reply/inline keyboards. Never log.
    pub reply_markup_json: Option<String>,
    pub forum_topic: bool,
    /// Avatar already indexed at `(sender_id, 0)` when its key starts with `avatar:`.
    pub sender_photo_cache_key: Option<String>,
    /// `None` means the cached record predates media privacy metadata.
    #[uniffi(default = None)]
    pub media_preview_restricted: Option<bool>,
}

pub type MessagesPageDto = mono_proto_types::dto::MessagesPageDto;

#[uniffi::remote(Record)]
pub struct MessagesPageDto {
    pub messages: Vec<MessageDto>,
    pub total_count: i32,
}

pub type SavedGifDto = mono_proto_types::dto::SavedGifDto;

#[uniffi::remote(Record)]
pub struct SavedGifDto {
    pub document_id: i64,
    pub cache_key: String,
    pub thumb_cache_key: Option<String>,
    pub width: Option<i32>,
    pub height: Option<i32>,
}

pub type ForumTopicDto = mono_proto_types::dto::ForumTopicDto;

#[uniffi::remote(Record)]
pub struct ForumTopicDto {
    pub id: i32,
    pub title: String,
    pub icon_color: i32,
    pub icon_emoji_id: Option<i64>,
    pub top_message: i32,
    pub date: i32,
    pub unread_count: i32,
    pub unread_mentions_count: i32,
    pub unread_reactions_count: i32,
    pub read_inbox_max_id: i32,
    pub pinned: bool,
    pub closed: bool,
    pub hidden: bool,
    pub short: bool,
    pub deleted: bool,
    pub last_message_preview: Option<String>,
}

pub type ForumTopicsPageDto = mono_proto_types::dto::ForumTopicsPageDto;

#[uniffi::remote(Record)]
pub struct ForumTopicsPageDto {
    pub count: i32,
    pub topics: Vec<ForumTopicDto>,
}

pub type DiscussionDto = mono_proto_types::dto::DiscussionDto;

#[uniffi::remote(Record)]
pub struct DiscussionDto {
    pub chat_id: i64,
    pub message_id: i32,
}

pub type ReactionChoiceDto = mono_proto_types::dto::ReactionChoiceDto;

#[uniffi::remote(Record)]
pub struct ReactionChoiceDto {
    pub emoticon: String,
    pub document_id: i64,
}

pub type ScheduledMessageDto = mono_proto_types::events::ScheduledMessageDto;

#[uniffi::remote(Record)]
pub struct ScheduledMessageDto {
    pub message: MessageDto,
    pub has_unsupported_content: bool,
}

pub type ContactImportSpec = mono_proto_messages::contacts::ContactImportSpec;
#[uniffi::remote(Record)]
pub struct ContactImportSpec {
    pub client_id: i64,
    pub phone: String,
    pub first_name: String,
    pub last_name: String,
}

pub type ContactImportMatch = mono_proto_messages::contacts::ContactImportMatch;
#[uniffi::remote(Record)]
pub struct ContactImportMatch {
    pub client_id: i64,
    pub user_id: i64,
}

pub type ContactImportPopularity = mono_proto_messages::contacts::ContactImportPopularity;
#[uniffi::remote(Record)]
pub struct ContactImportPopularity {
    pub client_id: i64,
    pub importers: i32,
}

pub type ContactImportResult = mono_proto_messages::contacts::ContactImportResult;
#[uniffi::remote(Record)]
pub struct ContactImportResult {
    pub imported: Vec<ContactImportMatch>,
    pub retry_client_ids: Vec<i64>,
    pub popular: Vec<ContactImportPopularity>,
}

pub type CloudDraft = mono_proto_types::events::CloudDraft;

#[uniffi::remote(Record)]
pub struct CloudDraft {
    pub chat_id: i64,
    pub thread_id: i32,
    pub text: String,
    pub entities_json: Option<String>,
    pub reply_to_message_id: i32,
    pub date: i32,
    pub no_webpage: bool,
    pub has_unsupported_content: bool,
}

pub type ChecklistTextEditDto = mono_proto_client::checklists::ChecklistTextEditDto;

#[uniffi::remote(Record)]
pub struct ChecklistTextEditDto {
    pub item_id: Option<i32>,
    pub text: String,
    pub entities_json: Option<String>,
    pub expected_text: String,
    pub expected_entities_json: Option<String>,
}

pub type UpdateEventDto = mono_proto_types::events::UpdateEventDto;

#[uniffi::remote(Enum)]
pub enum UpdateEventDto {
    PollChanged {
        poll_id: i64,
    },
    ChatsChanged,
    FoldersChanged,
    DraftChanged {
        draft: crate::dto::CloudDraft,
    },
    OutgoingMessageAcknowledged {
        random_id: i64,
        result: crate::OutgoingMessageResult,
    },
    ScheduledMessageChanged {
        message: crate::dto::ScheduledMessageDto,
    },
    ScheduledMessagesDeleted {
        chat_id: i64,
        message_ids: Vec<i32>,
        sent_message_ids: Option<Vec<i32>>,
    },
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
    /// Dialog rows from a lazy `messages.getPeerDialogs` batch. Not a full chat refresh.
    DialogsPatched {
        chats: Vec<ChatDto>,
    },
    /// Edge-triggered catch-up signal. True while difference or channel recovery is in flight.
    SyncState {
        is_syncing: bool,
    },
    Ignored {
        kind: String,
    },
}

pub type ChannelCursorDto = mono_proto_types::dto::ChannelCursorDto;

#[uniffi::remote(Record)]
pub struct ChannelCursorDto {
    pub chat_id: i64,
    pub pts: i32,
}

pub type UpdateBatchDto = mono_proto_types::dto::UpdateBatchDto;

#[uniffi::remote(Record)]
pub struct UpdateBatchDto {
    pub account_generation: i64,
    pub batch_id: i64,
    pub events: Vec<UpdateEventDto>,
    pub cursor_after: Option<UpdatesStateDto>,
    pub channel_pts_after: Vec<ChannelCursorDto>,
}

pub type UpdateDrainDto = mono_proto_types::dto::UpdateDrainDto;

#[uniffi::remote(Record)]
pub struct UpdateDrainDto {
    pub batches: Vec<UpdateBatchDto>,
    pub live_events: Vec<UpdateEventDto>,
    pub replay_required: bool,
}

pub type NotifySettingsDto = mono_proto_types::dto::NotifySettingsDto;

#[uniffi::remote(Record)]
pub struct NotifySettingsDto {
    pub show_previews: Option<bool>,
    pub silent: bool,
    pub mute_until: i32,
    pub stories_muted: bool,
    pub stories_hide_sender: bool,
    pub sound: String,
}

pub type NotifyExceptionDto = mono_proto_types::dto::NotifyExceptionDto;

#[uniffi::remote(Record)]
pub struct NotifyExceptionDto {
    pub peer_kind: String,
    pub chat_id: i64,
    pub topic_id: Option<i32>,
    pub show_previews: Option<bool>,
    pub silent: bool,
    pub mute_until: i32,
    pub stories_muted: bool,
    pub stories_hide_sender: bool,
    pub sound: String,
}

pub type UpdatesStateDto = mono_proto_types::updates_state::UpdatesStateDto;

#[uniffi::remote(Record)]
pub struct UpdatesStateDto {
    pub pts: i32,
    pub qts: i32,
    pub date: i32,
    pub seq: i32,
}

pub type StickerPackDto = mono_proto_types::dto::StickerPackDto;

#[uniffi::remote(Record)]
pub struct StickerPackDto {
    pub id: i64,
    pub access_hash: i64,
    pub title: String,
    pub short_name: String,
    pub count: i32,
    pub is_emoji: bool,
    pub preview_document_ids: Vec<i64>,
}

pub type StickerCatalogDto = mono_proto_types::dto::StickerCatalogDto;

#[uniffi::remote(Record)]
pub struct StickerCatalogDto {
    pub hash: i64,
    pub not_modified: bool,
    pub sets: Vec<StickerPackDto>,
}

pub type StickerListDto = mono_proto_types::dto::StickerListDto;

#[uniffi::remote(Record)]
pub struct StickerListDto {
    pub hash: i64,
    pub not_modified: bool,
    pub document_ids: Vec<i64>,
}

pub type ResolvedPeerDto = mono_proto_types::dto::ResolvedPeerDto;

#[uniffi::remote(Record)]
pub struct ResolvedPeerDto {
    pub peer_id: i64,
    pub username: Option<String>,
    pub title: String,
    pub is_bot: bool,
}

pub type InlineBotResultDto = mono_proto_types::dto::InlineBotResultDto;

#[uniffi::remote(Record)]
pub struct InlineBotResultDto {
    pub id: String,
    pub kind: String,
    pub title: Option<String>,
    pub description: Option<String>,
    pub url: Option<String>,
    pub document_id: Option<i64>,
    pub thumb_cache_key: Option<String>,
}

pub type InlineBotResultsDto = mono_proto_types::dto::InlineBotResultsDto;

#[uniffi::remote(Record)]
pub struct InlineBotResultsDto {
    pub query_id: i64,
    pub gallery: bool,
    pub next_offset: Option<String>,
    pub cache_time: i32,
    pub results: Vec<InlineBotResultDto>,
}

pub type SearchPeerDto = mono_proto_types::dto::SearchPeerDto;

#[uniffi::remote(Record)]
pub struct SearchPeerDto {
    pub peer_id: i64,
    pub title: String,
    pub username: Option<String>,
    pub kind: String,
    pub is_bot: bool,
    pub is_group: bool,
    pub is_channel: bool,
}

pub type ContactsSearchDto = mono_proto_types::dto::ContactsSearchDto;

#[uniffi::remote(Record)]
pub struct ContactsSearchDto {
    pub people: Vec<SearchPeerDto>,
    pub chats: Vec<SearchPeerDto>,
}

pub type GlobalMessageSearchDto = mono_proto_types::dto::GlobalMessageSearchDto;

#[uniffi::remote(Record)]
pub struct GlobalMessageSearchDto {
    pub messages: Vec<MessageDto>,
    pub next_rate: i32,
    pub next_peer_id: i64,
    pub next_offset_id: i32,
}

pub type BotCallbackAnswerDto = mono_proto_types::dto::BotCallbackAnswerDto;

#[uniffi::remote(Record)]
pub struct BotCallbackAnswerDto {
    pub alert: bool,
    pub message: Option<String>,
    pub url: Option<String>,
    pub cache_time: i32,
}

pub type UploadItemDto = mono_proto_types::dto::UploadItemDto;

#[uniffi::remote(Record)]
pub struct UploadItemDto {
    pub path: String,
    pub kind: String,
    pub mime_type: String,
    pub file_name: String,
    pub caption: String,
    pub entities_json: Option<String>,
    pub duration: i32,
    pub width: i32,
    pub height: i32,
    pub random_id: i64,
}

pub type JoinChatDto = mono_proto_types::dto::JoinChatDto;

#[uniffi::remote(Record)]
pub struct JoinChatDto {
    pub joined: bool,
    pub can_send_plain: bool,
    pub can_send_photos: bool,
    pub can_send_polls: bool,
}

pub type ReactionPeerDto = mono_proto_messages::extras_rpc::ReactionPeerDto;

#[uniffi::remote(Record)]
pub struct ReactionPeerDto {
    pub peer_id: i64,
    pub title: String,
    pub date: i32,
    pub emoticon: String,
    pub document_id: i64,
}

pub type ReactionPeersDto = mono_proto_messages::extras_rpc::ReactionPeersDto;

#[uniffi::remote(Record)]
pub struct ReactionPeersDto {
    pub next_offset: Option<String>,
    pub peers: Vec<ReactionPeerDto>,
    pub count: i32,
}

pub type PollVoterDto = mono_proto_messages::extras_rpc::PollVoterDto;

#[uniffi::remote(Record)]
pub struct PollVoterDto {
    pub peer_id: i64,
    pub title: String,
    pub date: i32,
    pub options: Vec<Vec<u8>>,
}

pub type PollVotersDto = mono_proto_messages::extras_rpc::PollVotersDto;

#[uniffi::remote(Record)]
pub struct PollVotersDto {
    pub next_offset: Option<String>,
    pub voters: Vec<PollVoterDto>,
    pub count: i32,
}

pub type ChecklistSendSpec = mono_proto_client::ChecklistSendSpec;
#[uniffi::remote(Record)]
pub struct ChecklistSendSpec {
    pub title: String,
    pub items: Vec<String>,
    pub others_can_append: bool,
    pub others_can_complete: bool,
}

pub type ContactSendSpec = mono_proto_client::ContactSendSpec;
#[uniffi::remote(Record)]
pub struct ContactSendSpec {
    pub phone: String,
    pub first_name: String,
    pub last_name: String,
    pub vcard: String,
}

pub type LocationSendSpec = mono_proto_client::LocationSendSpec;
#[uniffi::remote(Record)]
pub struct LocationSendSpec {
    pub latitude: f64,
    pub longitude: f64,
    pub live_period: i32,
    pub heading: Option<i32>,
}

pub type PollSendSpec = mono_proto_client::PollSendSpec;
#[uniffi::remote(Record)]
pub struct PollSendSpec {
    pub question: String,
    pub answers: Vec<String>,
    pub anonymous: bool,
    pub multiple_choice: bool,
    pub quiz: bool,
    pub correct_answer: Option<u32>,
    pub solution: Option<String>,
}

pub type SendOptions = mono_proto_runtime::send_options::SendOptions;
#[uniffi::remote(Record)]
pub struct SendOptions {
    pub silent: bool,
    pub no_webpage: bool,
    pub schedule_date: Option<i32>,
}

pub type OutgoingMessageResult = mono_proto_types::events::OutgoingMessageResult;
#[uniffi::remote(Enum)]
pub enum OutgoingMessageResult {
    Sent { message: MessageDto },
    Scheduled { message: ScheduledMessageDto },
}

pub type ReadParticipantDto = mono_proto_messages::messages::read_receipts_rpc::ReadParticipantDto;
#[uniffi::remote(Record)]
pub struct ReadParticipantDto {
    pub peer_id: i64,
    pub date: i32,
}

pub type ReadParticipantsDto =
    mono_proto_messages::messages::read_receipts_rpc::ReadParticipantsDto;
#[uniffi::remote(Record)]
pub struct ReadParticipantsDto {
    pub participants: Vec<ReadParticipantDto>,
    pub error: Option<String>,
}

pub type OutboxReadDto = mono_proto_messages::messages::read_receipts_rpc::OutboxReadDto;
#[uniffi::remote(Record)]
pub struct OutboxReadDto {
    pub date: i32,
    pub error: Option<String>,
}

pub type ReadReceiptConfigDto =
    mono_proto_messages::messages::read_receipts_rpc::ReadReceiptConfigDto;
#[uniffi::remote(Record)]
pub struct ReadReceiptConfigDto {
    pub chat_read_mark_size_threshold: i32,
    pub chat_read_mark_expire_period: i32,
    pub pm_read_date_expire_period: i32,
    pub from_server: bool,
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

#[derive(Debug, Clone, uniffi::Record)]
pub struct VpxAlphaFrame {
    pub width: u32,
    pub height: u32,
    pub alpha: Vec<u8>,
}
