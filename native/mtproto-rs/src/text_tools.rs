use crate::{
    AiTextResultDto, AiTextToneDto, TextToolTextDto, TextToolsAvailabilityDto, TextToolsConfigDto,
};
use mono_proto_client::text_tools as proto;
use mono_proto_types::error::MtprotoError;

#[uniffi::export]
pub fn translate_message_texts(
    handle: u64,
    chat_id: i64,
    message_ids: Vec<i32>,
    to_lang: String,
) -> Result<Vec<TextToolTextDto>, MtprotoError> {
    proto::translate_message_texts(handle, chat_id, &message_ids, &to_lang)
}

#[uniffi::export]
pub fn translate_texts(
    handle: u64,
    texts: Vec<TextToolTextDto>,
    to_lang: String,
) -> Result<Vec<TextToolTextDto>, MtprotoError> {
    proto::translate_texts(handle, &texts, &to_lang)
}

#[uniffi::export]
pub fn summarize_message_text(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    to_lang: Option<String>,
    tone: Option<String>,
) -> Result<TextToolTextDto, MtprotoError> {
    proto::summarize_message_text(
        handle,
        chat_id,
        message_id,
        to_lang.as_deref(),
        tone.as_deref(),
    )
}

#[uniffi::export]
pub fn compose_text_with_ai(
    handle: u64,
    text: TextToolTextDto,
    proofread: bool,
    emojify: bool,
    to_lang: Option<String>,
    tone: Option<String>,
) -> Result<AiTextResultDto, MtprotoError> {
    proto::compose_text_with_ai(
        handle,
        &text,
        proofread,
        emojify,
        to_lang.as_deref(),
        tone.as_deref(),
    )
}

#[uniffi::export]
pub fn get_ai_text_tones(handle: u64) -> Result<Vec<AiTextToneDto>, MtprotoError> {
    proto::get_ai_text_tones(handle)
}

#[uniffi::export]
pub fn toggle_peer_text_translations(
    handle: u64,
    chat_id: i64,
    disabled: bool,
) -> Result<(), MtprotoError> {
    proto::toggle_peer_text_translations(handle, chat_id, disabled)
}

#[uniffi::export]
pub fn create_ai_text_tone(
    handle: u64,
    title: String,
    prompt: String,
    emoji_id: i64,
    display_author: bool,
) -> Result<AiTextToneDto, MtprotoError> {
    proto::create_ai_text_tone(handle, &title, &prompt, emoji_id, display_author)
}

#[uniffi::export]
pub fn update_ai_text_tone(
    handle: u64,
    key: String,
    title: String,
    prompt: String,
    emoji_id: i64,
    display_author: bool,
) -> Result<AiTextToneDto, MtprotoError> {
    proto::update_ai_text_tone(handle, &key, &title, &prompt, emoji_id, display_author)
}

#[uniffi::export]
pub fn save_ai_text_tone(handle: u64, key: String, unsave: bool) -> Result<(), MtprotoError> {
    proto::save_ai_text_tone(handle, &key, unsave)
}

#[uniffi::export]
pub fn delete_ai_text_tone(handle: u64, key: String) -> Result<(), MtprotoError> {
    proto::delete_ai_text_tone(handle, &key)
}

#[uniffi::export]
pub fn get_ai_text_tone(handle: u64, key: String) -> Result<AiTextToneDto, MtprotoError> {
    proto::get_ai_text_tone(handle, &key)
}

#[uniffi::export]
pub fn get_ai_text_tone_example(handle: u64, key: String) -> Result<TextToolTextDto, MtprotoError> {
    proto::get_ai_text_tone_example(handle, &key)
}

#[uniffi::export]
pub fn get_text_tools_availability(
    handle: u64,
    chat_id: i64,
) -> Result<TextToolsAvailabilityDto, MtprotoError> {
    proto::get_text_tools_availability(handle, chat_id)
}

/// `translations_manual_enabled` / `translations_auto_enabled` normalized to
/// `enabled|alternative|system|disabled`.
#[uniffi::export]
pub fn get_text_tools_config(handle: u64) -> Result<TextToolsConfigDto, MtprotoError> {
    proto::get_text_tools_config(handle)
}
