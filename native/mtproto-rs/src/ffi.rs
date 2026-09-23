use crate::client_mgr;
use crate::extras_rpc;
use crate::lottie;
use crate::perf;
use crate::push_rpc;
use crate::read_receipts_rpc;
use crate::request_control;
use crate::scheduler;
use crate::session_crypto;
use crate::session_file;
use crate::vpx;
use crate::{
    AuthCodeSent, AuthSignedIn, BotCallbackAnswerDto, ChatDto, ContactsSearchDto, DiscussionDto,
    FolderDto, ForumTopicsPageDto, GlobalMessageSearchDto, InlineBotResultsDto, InstantViewDto,
    LottieSize, MessageDto, MtprotoError, NotifyExceptionDto, NotifySettingsDto, ProfileDto,
    ReactionChoiceDto, ResolvedPeerDto, SavedGifDto, StickerCatalogDto, StickerListDto,
    StickerPackDto, UpdateEventDto, UpdatesStateDto, UploadItemDto, VpxFrame, WallpaperCatalogDto,
};

#[uniffi::export]
pub fn get_wallpapers(handle: u64, hash: i64) -> Result<WallpaperCatalogDto, MtprotoError> {
    client_mgr::get_wallpapers(handle, hash)
}

#[uniffi::export]
pub fn get_web_page(handle: u64, url: String, hash: i32) -> Result<InstantViewDto, MtprotoError> {
    client_mgr::get_web_page(handle, url, hash)
}

#[uniffi::export]
pub fn download_wallpaper(
    handle: u64,
    id: i64,
    access_hash: i64,
    dest_path: String,
) -> Result<String, MtprotoError> {
    client_mgr::download_wallpaper(handle, id, access_hash, dest_path)
}

#[uniffi::export]
pub fn create_request_control() -> u64 {
    request_control::create()
}
#[uniffi::export]
pub fn bind_request_control(id: u64) -> u64 {
    request_control::bind(id)
}
#[uniffi::export]
pub fn cancel_request_control(id: u64) {
    request_control::cancel(id);
}
#[uniffi::export]
pub fn release_request_control(id: u64) {
    request_control::release(id);
}

/// 0 interactive read, 1 background read, 2 interactive media, 3 background media,
/// 4 interactive write.
#[uniffi::export]
pub fn set_dispatch_class(class: i32) {
    let class = match class {
        0 => scheduler::RequestClass::InteractiveRead,
        1 => scheduler::RequestClass::BackgroundRead,
        2 => scheduler::RequestClass::InteractiveMedia,
        3 => scheduler::RequestClass::BackgroundMedia,
        4 => scheduler::RequestClass::InteractiveWrite,
        _ => scheduler::RequestClass::InteractiveRead,
    };
    scheduler::set_current_class(class);
}

/// "Faster downloads" setting: media lanes (1..=8) and parts in flight (1..=16).
#[uniffi::export]
pub fn set_download_concurrency(lanes: i32, parts: i32) {
    scheduler::set_active_media_lanes(lanes.max(1) as usize);
    client_mgr::set_pipeline_parts(parts.max(1) as usize);
}

#[uniffi::export]
pub fn set_file_part_kib(kib: i32) {
    crate::upload_rpc::set_file_part_kib(kib);
}

#[uniffi::export]
pub fn set_download_chunk_kib(kib: i32) {
    crate::media::set_chunk_size(kib * 1024);
}

#[uniffi::export]
pub fn download_chunk_kib() -> i32 {
    crate::media::chunk_size() / 1024
}

#[uniffi::export]
pub fn download_concurrency() -> Vec<i32> {
    vec![
        scheduler::active_media_lanes() as i32,
        client_mgr::pipeline_parts() as i32,
    ]
}

/// Netcode timing spans on/off; off by default.
#[uniffi::export]
pub fn perf_set_enabled(enabled: bool) {
    perf::set_enabled(enabled);
}

/// JSON timing snapshot; `reset` clears the window.
#[uniffi::export]
pub fn perf_snapshot(reset: bool) -> String {
    perf::snapshot_json(reset)
}
#[uniffi::export]
pub fn library_version() -> String {
    format!(
        "monogram-mtproto/{}; tellers-mtproto-impl; layer {}",
        env!("CARGO_PKG_VERSION"),
        tellers_mtproto::LATEST_API_LAYER
    )
}

#[uniffi::export]
pub fn create_client(api_id: i32, api_hash: String, session_path: String) -> u64 {
    client_mgr::create_client(api_id, api_hash, session_path)
}

#[uniffi::export]
pub fn create_encrypted_client(
    api_id: i32,
    api_hash: String,
    session_path: String,
    key: Vec<u8>,
) -> Result<u64, MtprotoError> {
    let key = zeroize::Zeroizing::new(key);
    let guard = session_crypto::register(std::path::Path::new(&session_path), &key)
        .map_err(|_| MtprotoError::Message("session key unavailable".into()))?;
    session_file::FileSessionStore::new(&session_path)
        .load()
        .map_err(|_| MtprotoError::Message("session restore failed".into()))?;
    let handle = client_mgr::create_client(api_id, api_hash, session_path);
    drop(guard);
    Ok(handle)
}

#[uniffi::export]
pub fn connect(handle: u64) -> Result<(), MtprotoError> {
    perf::span("connect").with(|| client_mgr::connect(handle))
}

#[uniffi::export]
pub fn is_authorized(handle: u64) -> Result<bool, MtprotoError> {
    client_mgr::is_authorized(handle)
}

#[uniffi::export]
pub fn destroy_client(handle: u64) {
    client_mgr::destroy_client(handle)
}

#[uniffi::export]
pub fn client_exists(handle: u64) -> bool {
    client_mgr::client_exists(handle)
}

#[uniffi::export]
pub fn client_api_id(handle: u64) -> i32 {
    client_mgr::client_api_id(handle)
}

#[uniffi::export]
pub fn set_client_test_dc(handle: u64, test: bool) -> Result<(), MtprotoError> {
    client_mgr::set_client_test_dc(handle, test)
}

#[uniffi::export]
pub fn send_auth_code(handle: u64, phone: String) -> Result<AuthCodeSent, MtprotoError> {
    client_mgr::send_auth_code(handle, phone)
}

#[uniffi::export]
pub fn resend_auth_code(
    handle: u64,
    phone: String,
    phone_code_hash: String,
) -> Result<AuthCodeSent, MtprotoError> {
    client_mgr::resend_auth_code(handle, phone, phone_code_hash)
}

#[uniffi::export]
pub fn sign_in(
    handle: u64,
    phone: String,
    phone_code_hash: String,
    phone_code: String,
) -> Result<AuthSignedIn, MtprotoError> {
    client_mgr::sign_in(handle, phone, phone_code_hash, phone_code)
}

#[uniffi::export]
pub fn check_password(handle: u64, password: String) -> Result<AuthSignedIn, MtprotoError> {
    client_mgr::check_password(handle, password)
}

#[uniffi::export]
pub fn logout(handle: u64) -> Result<(), MtprotoError> {
    client_mgr::logout(handle)
}

#[uniffi::export]
pub fn get_chats(handle: u64) -> Result<Vec<ChatDto>, MtprotoError> {
    client_mgr::get_chats(handle)
}

#[uniffi::export]
pub fn get_folders(handle: u64) -> Result<Vec<FolderDto>, MtprotoError> {
    client_mgr::get_folders(handle)
}

#[uniffi::export]
pub fn update_folder(handle: u64, folder: FolderDto) -> Result<(), MtprotoError> {
    client_mgr::update_folder(handle, folder)
}

#[uniffi::export]
pub fn delete_folder(handle: u64, id: i32) -> Result<(), MtprotoError> {
    client_mgr::delete_folder(handle, id)
}

#[uniffi::export]
pub fn update_folder_order(handle: u64, order: Vec<i32>) -> Result<(), MtprotoError> {
    client_mgr::update_folder_order(handle, order)
}

#[uniffi::export]
pub fn get_history(handle: u64, chat_id: i64, limit: i32) -> Result<Vec<MessageDto>, MtprotoError> {
    client_mgr::get_history(handle, chat_id, limit, 0, 0, 0)
}

#[uniffi::export]
pub fn get_history_page(
    handle: u64,
    chat_id: i64,
    limit: i32,
    offset_id: i32,
    offset_date: i32,
    add_offset: i32,
) -> Result<Vec<MessageDto>, MtprotoError> {
    client_mgr::get_history(handle, chat_id, limit, offset_id, offset_date, add_offset)
}

#[uniffi::export]
pub fn get_replies(
    handle: u64,
    chat_id: i64,
    msg_id: i32,
    limit: i32,
    offset_id: i32,
    add_offset: i32,
) -> Result<Vec<MessageDto>, MtprotoError> {
    client_mgr::get_replies(handle, chat_id, msg_id, limit, offset_id, add_offset)
}

#[uniffi::export]
pub fn get_forum_topics(
    handle: u64,
    chat_id: i64,
    offset_date: i32,
    offset_id: i32,
    offset_topic: i32,
    limit: i32,
) -> Result<ForumTopicsPageDto, MtprotoError> {
    client_mgr::get_forum_topics(handle, chat_id, offset_date, offset_id, offset_topic, limit)
}

#[uniffi::export]
pub fn get_forum_topics_by_id(
    handle: u64,
    chat_id: i64,
    topic_ids: Vec<i32>,
) -> Result<ForumTopicsPageDto, MtprotoError> {
    client_mgr::get_forum_topics_by_id(handle, chat_id, topic_ids)
}

#[uniffi::export]
pub fn load_more_chats(
    handle: u64,
    offset_date: i32,
    offset_id: i32,
    offset_peer_id: i64,
    folder_id: i32,
) -> Result<Vec<ChatDto>, MtprotoError> {
    client_mgr::load_more_chats(handle, offset_date, offset_id, offset_peer_id, folder_id)
}

#[uniffi::export]
pub fn search_messages(
    handle: u64,
    chat_id: i64,
    query: String,
    limit: i32,
) -> Result<Vec<MessageDto>, MtprotoError> {
    client_mgr::search_messages(handle, chat_id, query, limit)
}

#[uniffi::export]
pub fn search_messages_filtered(
    handle: u64,
    chat_id: i64,
    query: String,
    filter: String,
    offset_id: i32,
    add_offset: i32,
    limit: i32,
) -> Result<Vec<MessageDto>, MtprotoError> {
    client_mgr::search_messages_filtered(
        handle, chat_id, query, filter, offset_id, add_offset, limit,
    )
}

#[uniffi::export]
pub fn contacts_search(
    handle: u64,
    query: String,
    limit: i32,
) -> Result<ContactsSearchDto, MtprotoError> {
    client_mgr::contacts_search(handle, query, limit)
}

#[uniffi::export]
pub fn search_global(
    handle: u64,
    query: String,
    offset_rate: i32,
    offset_peer_id: i64,
    offset_id: i32,
    limit: i32,
) -> Result<GlobalMessageSearchDto, MtprotoError> {
    client_mgr::search_global(handle, query, offset_rate, offset_peer_id, offset_id, limit)
}

#[uniffi::export]
pub fn get_pinned_messages(
    handle: u64,
    chat_id: i64,
    limit: i32,
) -> Result<Vec<MessageDto>, MtprotoError> {
    client_mgr::get_pinned_messages(handle, chat_id, limit)
}

#[uniffi::export]
pub fn send_text_message(
    handle: u64,
    chat_id: i64,
    text: String,
    reply_to_msg_id: i32,
    entities_json: Option<String>,
    top_msg_id: i32,
) -> Result<MessageDto, MtprotoError> {
    client_mgr::send_text_message(
        handle,
        chat_id,
        text,
        reply_to_msg_id,
        entities_json,
        top_msg_id,
    )
}

#[uniffi::export]
pub fn send_photo_message(
    handle: u64,
    chat_id: i64,
    path: String,
    caption: String,
    reply_to_msg_id: i32,
    top_msg_id: i32,
    entities_json: Option<String>,
) -> Result<MessageDto, MtprotoError> {
    client_mgr::send_photo_message(
        handle,
        chat_id,
        path,
        caption,
        reply_to_msg_id,
        top_msg_id,
        entities_json,
    )
}

#[uniffi::export]
pub fn send_uploaded_media(
    handle: u64,
    chat_id: i64,
    item: UploadItemDto,
    reply_to_msg_id: i32,
    top_msg_id: i32,
    entities_json: Option<String>,
) -> Result<MessageDto, MtprotoError> {
    client_mgr::send_uploaded_media(
        handle,
        chat_id,
        item,
        reply_to_msg_id,
        top_msg_id,
        entities_json,
    )
}

#[uniffi::export]
pub fn send_uploaded_album(
    handle: u64,
    chat_id: i64,
    items: Vec<UploadItemDto>,
    reply_to_msg_id: i32,
    top_msg_id: i32,
) -> Result<Vec<MessageDto>, MtprotoError> {
    client_mgr::send_uploaded_album(handle, chat_id, items, reply_to_msg_id, top_msg_id)
}

#[uniffi::export]
pub fn edit_text_message(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    text: String,
    entities_json: Option<String>,
) -> Result<MessageDto, MtprotoError> {
    client_mgr::edit_text_message(handle, chat_id, message_id, text, entities_json)
}

#[uniffi::export]
pub fn delete_message(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    revoke: bool,
) -> Result<(), MtprotoError> {
    client_mgr::delete_message(handle, chat_id, message_id, revoke)
}

#[uniffi::export]
pub fn forward_messages(
    handle: u64,
    from_chat_id: i64,
    message_ids: Vec<i32>,
    to_chat_id: i64,
    drop_author: bool,
) -> Result<Vec<MessageDto>, MtprotoError> {
    client_mgr::forward_messages(handle, from_chat_id, message_ids, to_chat_id, drop_author)
}

#[uniffi::export]
pub fn read_history(handle: u64, chat_id: i64, max_id: i32) -> Result<(), MtprotoError> {
    client_mgr::read_history(handle, chat_id, max_id)
}

/// https://core.telegram.org/method/messages.readMessageContents
/// https://core.telegram.org/method/channels.readMessageContents
#[uniffi::export]
pub fn read_message_contents(
    handle: u64,
    chat_id: i64,
    message_ids: Vec<i32>,
) -> Result<(), MtprotoError> {
    client_mgr::read_message_contents(handle, chat_id, message_ids)
}

/// https://core.telegram.org/method/messages.markDialogUnread
#[uniffi::export]
pub fn mark_dialog_unread(handle: u64, chat_id: i64, unread: bool) -> Result<(), MtprotoError> {
    client_mgr::mark_dialog_unread(handle, chat_id, unread)
}

/// https://core.telegram.org/method/messages.getUnreadMentions
#[uniffi::export]
pub fn get_unread_mentions(
    handle: u64,
    chat_id: i64,
    offset_id: i32,
    add_offset: i32,
    limit: i32,
    top_msg_id: i32,
) -> Result<Vec<MessageDto>, MtprotoError> {
    client_mgr::get_unread_mentions(handle, chat_id, offset_id, add_offset, limit, top_msg_id)
}

/// https://core.telegram.org/method/messages.readMentions
#[uniffi::export]
pub fn read_mentions(handle: u64, chat_id: i64, top_msg_id: i32) -> Result<(), MtprotoError> {
    client_mgr::read_mentions(handle, chat_id, top_msg_id)
}

/// https://core.telegram.org/method/messages.getUnreadReactions
#[uniffi::export]
pub fn get_unread_reactions(
    handle: u64,
    chat_id: i64,
    offset_id: i32,
    add_offset: i32,
    limit: i32,
    top_msg_id: i32,
) -> Result<Vec<MessageDto>, MtprotoError> {
    client_mgr::get_unread_reactions(handle, chat_id, offset_id, add_offset, limit, top_msg_id)
}

/// https://core.telegram.org/method/messages.readReactions
#[uniffi::export]
pub fn read_reactions(handle: u64, chat_id: i64, top_msg_id: i32) -> Result<(), MtprotoError> {
    client_mgr::read_reactions(handle, chat_id, top_msg_id)
}

#[uniffi::export]
pub fn read_discussion(
    handle: u64,
    chat_id: i64,
    msg_id: i32,
    read_max_id: i32,
) -> Result<(), MtprotoError> {
    client_mgr::read_discussion(handle, chat_id, msg_id, read_max_id)
}

#[uniffi::export]
pub fn set_typing(handle: u64, chat_id: i64, typing: bool) -> Result<(), MtprotoError> {
    client_mgr::set_typing(handle, chat_id, typing)
}

#[uniffi::export]
pub fn update_status(handle: u64, offline: bool) -> Result<(), MtprotoError> {
    client_mgr::update_status(handle, offline)
}

#[uniffi::export]
pub fn register_device(
    handle: u64,
    token_type: i32,
    token: String,
    secret: Vec<u8>,
    no_muted: bool,
    app_sandbox: bool,
    other_uids: Vec<i64>,
) -> Result<(), MtprotoError> {
    client_mgr::register_device(
        handle,
        token_type,
        token,
        secret,
        no_muted,
        app_sandbox,
        other_uids,
    )
}

#[uniffi::export]
pub fn unregister_device(
    handle: u64,
    token_type: i32,
    token: String,
    other_uids: Vec<i64>,
) -> Result<(), MtprotoError> {
    client_mgr::unregister_device(handle, token_type, token, other_uids)
}

#[uniffi::export]
pub fn get_notify_settings(
    handle: u64,
    peer_kind: String,
    chat_id: i64,
) -> Result<NotifySettingsDto, MtprotoError> {
    client_mgr::get_notify_settings(handle, peer_kind, chat_id)
}

#[uniffi::export]
pub fn update_notify_settings(
    handle: u64,
    peer_kind: String,
    chat_id: i64,
    show_previews: bool,
    silent: bool,
    mute_until: i32,
    stories_muted: bool,
    sound: String,
) -> Result<(), MtprotoError> {
    client_mgr::update_notify_settings(
        handle,
        peer_kind,
        chat_id,
        show_previews,
        silent,
        mute_until,
        stories_muted,
        sound,
    )
}

#[uniffi::export]
pub fn reset_notify_settings(handle: u64) -> Result<(), MtprotoError> {
    client_mgr::reset_notify_settings(handle)
}

#[uniffi::export]
pub fn set_contact_joined_silent(handle: u64, silent: bool) -> Result<(), MtprotoError> {
    client_mgr::set_contact_joined_silent(handle, silent)
}

#[uniffi::export]
pub fn get_notify_exceptions(
    handle: u64,
    compare_sound: bool,
) -> Result<Vec<NotifyExceptionDto>, MtprotoError> {
    client_mgr::get_notify_exceptions(handle, compare_sound)
}

#[uniffi::export]
pub fn decrypt_push_payload(secret: Vec<u8>, payload: String) -> Result<String, MtprotoError> {
    push_rpc::decrypt_push_payload(secret, payload)
}

#[uniffi::export]
pub fn get_profile(handle: u64, peer_id: i64) -> Result<ProfileDto, MtprotoError> {
    client_mgr::get_profile(handle, peer_id)
}

#[uniffi::export]
pub fn get_group_admin_tags(handle: u64, chat_id: i64) -> Result<String, MtprotoError> {
    client_mgr::get_group_admin_tags(handle, chat_id)
}

/// `messages.getSearchCounters` counts per filter (photo_video/document/url/gif/voice/music/...).
#[uniffi::export]
pub fn get_search_counters(
    handle: u64,
    chat_id: i64,
    filters: Vec<String>,
) -> Result<String, MtprotoError> {
    client_mgr::get_search_counters(handle, chat_id, filters)
}

/// Participant page with roles for groups and channels, as compact JSON.
#[uniffi::export]
pub fn get_participants(
    handle: u64,
    chat_id: i64,
    filter: String,
    query: String,
    offset: i32,
    limit: i32,
) -> Result<String, MtprotoError> {
    client_mgr::get_participants(handle, chat_id, filter, query, offset, limit)
}

/// Groups and channels shared with a user, as compact JSON.
#[uniffi::export]
pub fn get_common_chats(
    handle: u64,
    user_id: i64,
    max_id: i64,
    limit: i32,
) -> Result<String, MtprotoError> {
    client_mgr::get_common_chats(handle, user_id, max_id, limit)
}

#[uniffi::export]
pub fn start_updates(handle: u64) -> Result<(), MtprotoError> {
    client_mgr::start_updates(handle)
}

#[uniffi::export]
pub fn clear_active_dialog(handle: u64) -> Result<(), MtprotoError> {
    client_mgr::clear_active_dialog(handle)
}

#[uniffi::export]
pub fn drain_updates(handle: u64) -> Result<Vec<UpdateEventDto>, MtprotoError> {
    client_mgr::drain_updates(handle)
}

#[uniffi::export]
pub fn animated_emoji_max(handle: u64) -> Result<i32, MtprotoError> {
    client_mgr::animated_emoji_max(handle)
}

#[uniffi::export]
pub fn custom_emoji_is_free(handle: u64, document_id: i64) -> Result<bool, MtprotoError> {
    client_mgr::custom_emoji_is_free(handle, document_id)
}

#[uniffi::export]
pub fn get_updates_state(handle: u64) -> Result<UpdatesStateDto, MtprotoError> {
    client_mgr::get_updates_state(handle)
}

#[uniffi::export]
pub fn download_message_media(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    dest_path: String,
) -> Result<String, MtprotoError> {
    client_mgr::download_message_media(
        handle,
        chat_id,
        message_id,
        dest_path,
        crate::media::MediaDownloadKind::Full,
    )
}

#[uniffi::export]
pub fn download_message_media_chunk(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    dest_path: String,
    offset: i64,
) -> Result<String, MtprotoError> {
    client_mgr::download_message_media_range(
        handle,
        chat_id,
        message_id,
        dest_path,
        crate::media::MediaDownloadKind::Full,
        Some(offset),
    )
}

#[uniffi::export]
pub fn download_message_thumb(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    dest_path: String,
) -> Result<String, MtprotoError> {
    client_mgr::download_message_media(
        handle,
        chat_id,
        message_id,
        dest_path,
        crate::media::MediaDownloadKind::Thumb,
    )
}

#[uniffi::export]
pub fn download_message_display(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    dest_path: String,
) -> Result<String, MtprotoError> {
    client_mgr::download_message_media(
        handle,
        chat_id,
        message_id,
        dest_path,
        crate::media::MediaDownloadKind::Display,
    )
}

#[uniffi::export]
pub fn download_custom_emoji(
    handle: u64,
    document_id: i64,
    dest_path: String,
) -> Result<String, MtprotoError> {
    client_mgr::download_custom_emoji(handle, document_id, dest_path)
}

#[uniffi::export]
pub fn get_sticker_pack(handle: u64, document_id: i64) -> Result<StickerPackDto, MtprotoError> {
    client_mgr::get_sticker_pack(handle, document_id)
}

#[uniffi::export]
pub fn get_sticker_set(
    handle: u64,
    set_id: i64,
    access_hash: i64,
) -> Result<StickerPackDto, MtprotoError> {
    client_mgr::get_sticker_set(handle, set_id, access_hash)
}

#[uniffi::export]
pub fn get_all_stickers(handle: u64, hash: i64) -> Result<StickerCatalogDto, MtprotoError> {
    client_mgr::get_all_stickers(handle, hash)
}

#[uniffi::export]
pub fn get_emoji_stickers(handle: u64, hash: i64) -> Result<StickerCatalogDto, MtprotoError> {
    client_mgr::get_emoji_stickers(handle, hash)
}

#[uniffi::export]
pub fn get_stickers(
    handle: u64,
    emoticon: String,
    hash: i64,
) -> Result<StickerListDto, MtprotoError> {
    client_mgr::get_stickers(handle, emoticon, hash)
}

#[uniffi::export]
pub fn resolve_username(handle: u64, username: String) -> Result<ResolvedPeerDto, MtprotoError> {
    client_mgr::resolve_username(handle, username)
}

#[uniffi::export]
pub fn get_inline_bot_results(
    handle: u64,
    chat_id: i64,
    bot_id: i64,
    query: String,
    offset: String,
) -> Result<InlineBotResultsDto, MtprotoError> {
    client_mgr::get_inline_bot_results(handle, chat_id, bot_id, query, offset)
}

#[uniffi::export]
pub fn send_inline_bot_result(
    handle: u64,
    chat_id: i64,
    query_id: i64,
    result_id: String,
    reply_to_msg_id: i32,
    top_msg_id: i32,
) -> Result<MessageDto, MtprotoError> {
    client_mgr::send_inline_bot_result(
        handle,
        chat_id,
        query_id,
        result_id,
        reply_to_msg_id,
        top_msg_id,
    )
}

#[uniffi::export]
pub fn get_saved_gifs(handle: u64) -> Result<Vec<SavedGifDto>, MtprotoError> {
    client_mgr::get_saved_gifs(handle)
}

#[uniffi::export]
pub fn send_location(
    handle: u64,
    chat_id: i64,
    latitude: f64,
    longitude: f64,
    live_period: i32,
    heading: i32,
    reply_to_msg_id: i32,
) -> Result<(), MtprotoError> {
    client_mgr::send_location(
        handle,
        chat_id,
        latitude,
        longitude,
        live_period,
        heading,
        reply_to_msg_id,
    )
}

#[uniffi::export]
pub fn get_message_reactions_list(
    handle: u64,
    chat_id: i64,
    message_id: i32,
) -> Result<extras_rpc::ReactionPeersDto, MtprotoError> {
    client_mgr::get_message_reactions_list(handle, chat_id, message_id)
}

#[uniffi::export]
pub fn get_poll_votes(
    handle: u64,
    chat_id: i64,
    message_id: i32,
) -> Result<extras_rpc::PollVotersDto, MtprotoError> {
    client_mgr::get_poll_votes(handle, chat_id, message_id)
}

#[uniffi::export]
pub fn send_poll_vote(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    options: Vec<Vec<u8>>,
) -> Result<(), MtprotoError> {
    client_mgr::send_poll_vote(handle, chat_id, message_id, options)
}

#[uniffi::export]
pub fn append_todo_items(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    first_id: i32,
    titles: Vec<String>,
) -> Result<(), MtprotoError> {
    client_mgr::append_todo_items(handle, chat_id, message_id, first_id, titles)
}

#[uniffi::export]
pub fn toggle_todo_completed(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    completed: Vec<i32>,
    incompleted: Vec<i32>,
) -> Result<(), MtprotoError> {
    client_mgr::toggle_todo_completed(handle, chat_id, message_id, completed, incompleted)
}

#[uniffi::export]
pub fn get_bot_callback_answer(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    data_hex: String,
) -> Result<BotCallbackAnswerDto, MtprotoError> {
    client_mgr::get_bot_callback_answer(handle, chat_id, message_id, data_hex)
}

#[uniffi::export]
pub fn send_saved_gif(
    handle: u64,
    chat_id: i64,
    document_id: i64,
    reply_to_msg_id: i32,
    top_msg_id: i32,
) -> Result<MessageDto, MtprotoError> {
    client_mgr::send_saved_gif(handle, chat_id, document_id, reply_to_msg_id, top_msg_id)
}

#[uniffi::export]
pub fn send_reaction(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    emoticon: String,
    document_id: i64,
) -> Result<(), MtprotoError> {
    client_mgr::send_reaction(handle, chat_id, message_id, emoticon, document_id)
}

#[uniffi::export]
pub fn get_discussion_message(
    handle: u64,
    chat_id: i64,
    message_id: i32,
) -> Result<DiscussionDto, MtprotoError> {
    client_mgr::get_discussion_message(handle, chat_id, message_id)
}

#[uniffi::export]
pub fn get_recent_reactions(handle: u64) -> Result<Vec<ReactionChoiceDto>, MtprotoError> {
    client_mgr::get_recent_reactions(handle)
}

#[uniffi::export]
pub fn create_lottie(data: Vec<u8>) -> Result<u64, MtprotoError> {
    lottie::create_lottie(data)
}

#[uniffi::export]
pub fn destroy_lottie(handle: u64) {
    lottie::destroy_lottie(handle)
}

#[uniffi::export]
pub fn lottie_frame_count(handle: u64) -> Result<u32, MtprotoError> {
    lottie::lottie_frame_count(handle)
}

#[uniffi::export]
pub fn lottie_frame_rate(handle: u64) -> Result<f32, MtprotoError> {
    lottie::lottie_frame_rate(handle)
}

#[uniffi::export]
pub fn lottie_size(handle: u64) -> Result<LottieSize, MtprotoError> {
    let (width, height) = lottie::lottie_size(handle)?;
    Ok(LottieSize { width, height })
}

#[uniffi::export]
pub fn render_lottie_frame(
    handle: u64,
    frame: f32,
    width: u32,
    height: u32,
) -> Result<Vec<u8>, MtprotoError> {
    lottie::render_lottie_frame(handle, frame, width, height)
}

#[uniffi::export]
pub fn create_vpx_decoder() -> Result<u64, MtprotoError> {
    vpx::create_vpx_decoder()
}

#[uniffi::export]
pub fn destroy_vpx_decoder(handle: u64) {
    vpx::destroy_vpx_decoder(handle)
}

#[uniffi::export]
pub fn decode_vpx_packet(handle: u64, data: Vec<u8>) -> Result<Option<VpxFrame>, MtprotoError> {
    vpx::decode_vpx_packet(handle, data)
}

#[uniffi::export]
pub fn get_message_read_participants(
    handle: u64,
    chat_id: i64,
    msg_id: i32,
) -> Result<read_receipts_rpc::ReadParticipantsDto, MtprotoError> {
    client_mgr::get_message_read_participants(handle, chat_id, msg_id)
}

#[uniffi::export]
pub fn get_outbox_read_date(
    handle: u64,
    chat_id: i64,
    msg_id: i32,
) -> Result<read_receipts_rpc::OutboxReadDto, MtprotoError> {
    client_mgr::get_outbox_read_date(handle, chat_id, msg_id)
}

#[uniffi::export]
pub fn get_read_receipt_config(
    handle: u64,
) -> Result<read_receipts_rpc::ReadReceiptConfigDto, MtprotoError> {
    client_mgr::get_read_receipt_config(handle)
}
