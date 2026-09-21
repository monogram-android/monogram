use crate::{MessageDto, MtprotoError};

use super::*;

pub fn get_wallpapers(handle: u64, hash: i64) -> Result<crate::WallpaperCatalogDto, MtprotoError> {
    with_client_mut(handle, |state| {
        let catalog = call_with_migrate(state, |state| {
            crate::wallpaper_rpc::get_wallpapers(&mut state.snapshot, state.api_id, hash)
        })?;
        Ok(catalog)
    })
}

pub fn get_web_page(
    handle: u64,
    url: String,
    hash: i32,
) -> Result<crate::InstantViewDto, MtprotoError> {
    with_client_mut(handle, |state| {
        ensure_ready(state)?;
        let page = call_with_migrate(state, |state| {
            crate::instant_view_rpc::get_web_page(
                &mut state.snapshot,
                state.api_id,
                &mut state.peers,
                &mut state.media,
                url.clone(),
                hash,
            )
        })?;
        Ok(page)
    })
}

pub fn send_location(
    handle: u64,
    chat_id: i64,
    latitude: f64,
    longitude: f64,
    live_period: i32,
    heading: i32,
    reply_to_msg_id: i32,
) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                crate::extras_rpc::send_location(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    chat_id,
                    latitude,
                    longitude,
                    live_period,
                    heading,
                    reply_to_msg_id,
                )
            })
        })
    })
}

pub fn get_message_reactions_list(
    handle: u64,
    chat_id: i64,
    message_id: i32,
) -> Result<crate::extras_rpc::ReactionPeersDto, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                crate::extras_rpc::get_message_reactions_list(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    chat_id,
                    message_id,
                )
            })
        })
    })
}

pub fn get_poll_votes(
    handle: u64,
    chat_id: i64,
    message_id: i32,
) -> Result<crate::extras_rpc::PollVotersDto, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                crate::extras_rpc::get_poll_votes(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    chat_id,
                    message_id,
                )
            })
        })
    })
}

pub fn send_poll_vote(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    options: Vec<Vec<u8>>,
) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                crate::extras_rpc::send_poll_vote(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    chat_id,
                    message_id,
                    &options,
                )
            })
        })
    })
}

pub fn append_todo_items(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    first_id: i32,
    titles: Vec<String>,
) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                crate::extras_rpc::append_todo_items(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    chat_id,
                    message_id,
                    first_id,
                    &titles,
                )
            })
        })
    })
}

pub fn toggle_todo_completed(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    completed: Vec<i32>,
    incompleted: Vec<i32>,
) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                crate::extras_rpc::toggle_todo_completed(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    chat_id,
                    message_id,
                    &completed,
                    &incompleted,
                )
            })
        })?;
        Ok(())
    })
}

pub fn get_bot_callback_answer(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    data_hex: String,
) -> Result<crate::BotCallbackAnswerDto, MtprotoError> {
    with_client_mut(handle, |state| {
        let dto = call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                crate::extras_rpc::get_bot_callback_answer(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    chat_id,
                    message_id,
                    &data_hex,
                )
            })
        })?;
        Ok(dto)
    })
}

pub fn get_saved_gifs(handle: u64) -> Result<Vec<crate::SavedGifDto>, MtprotoError> {
    with_client_mut(handle, |state| {
        let (_hash, list) = call_with_migrate(state, |state| {
            crate::extras_rpc::get_saved_gifs(
                &mut state.snapshot,
                state.api_id,
                &mut state.media,
                0,
            )
        })?;
        Ok(list)
    })
}

pub fn resolve_username(
    handle: u64,
    username: String,
) -> Result<crate::ResolvedPeerDto, MtprotoError> {
    with_client_mut(handle, |state| {
        let dto = call_with_migrate(state, |state| {
            crate::inline_rpc::resolve_username(
                &mut state.snapshot,
                state.api_id,
                &mut state.peers,
                &mut state.media,
                &username,
            )
        })?;
        Ok(dto)
    })
}

pub fn get_inline_bot_results(
    handle: u64,
    chat_id: i64,
    bot_id: i64,
    query: String,
    offset: String,
) -> Result<crate::InlineBotResultsDto, MtprotoError> {
    with_client_mut(handle, |state| {
        let dto = call_with_migrate(state, |state| {
            crate::inline_rpc::get_inline_bot_results(
                &mut state.snapshot,
                state.api_id,
                &mut state.peers,
                &mut state.media,
                chat_id,
                bot_id,
                &query,
                &offset,
            )
        })?;
        state.last_inline = Some(LastInlineQuery {
            chat_id,
            bot_id,
            query: crate::CompactString::from(query),
            offset: crate::CompactString::from(offset),
            last_refresh: None,
        });
        Ok(dto)
    })
}

pub fn send_inline_bot_result(
    handle: u64,
    chat_id: i64,
    query_id: i64,
    result_id: String,
    reply_to_msg_id: i32,
    top_msg_id: i32,
) -> Result<MessageDto, MtprotoError> {
    with_client_mut(handle, |state| {
        let dto = call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                crate::inline_rpc::send_inline_bot_result(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    &mut state.media,
                    chat_id,
                    query_id,
                    &result_id,
                    reply_to_msg_id,
                    top_msg_id,
                )
            })
        })?;
        Ok(dto)
    })
}

pub fn send_saved_gif(
    handle: u64,
    chat_id: i64,
    document_id: i64,
    reply_to_msg_id: i32,
    top_msg_id: i32,
) -> Result<MessageDto, MtprotoError> {
    with_client_mut(handle, |state| {
        let dto = call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                crate::extras_rpc::send_saved_gif(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    &mut state.media,
                    chat_id,
                    document_id,
                    reply_to_msg_id,
                    top_msg_id,
                )
            })
        })?;
        Ok(dto)
    })
}

pub fn send_reaction(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    emoticon: String,
    document_id: i64,
) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                crate::extras_rpc::send_reaction(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    chat_id,
                    message_id,
                    emoticon.clone(),
                    document_id,
                )
            })
        })
    })
}

pub fn get_recent_reactions(handle: u64) -> Result<Vec<crate::ReactionChoiceDto>, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            crate::extras_rpc::get_recent_reactions(&mut state.snapshot, state.api_id)
        })
    })
}

pub fn animated_emoji_max(handle: u64) -> Result<i32, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            crate::extras_rpc::animated_emoji_max(&mut state.snapshot, state.api_id)
        })
    })
}

pub fn custom_emoji_is_free(handle: u64, document_id: i64) -> Result<bool, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            crate::extras_rpc::custom_emoji_is_free(&mut state.snapshot, state.api_id, document_id)
        })
    })
}

pub fn get_discussion_message(
    handle: u64,
    chat_id: i64,
    message_id: i32,
) -> Result<crate::DiscussionDto, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                crate::extras_rpc::get_discussion_message(
                    &mut state.snapshot,
                    state.api_id,
                    &mut state.peers,
                    &mut state.media,
                    chat_id,
                    message_id,
                )
            })
        })
    })
}

pub fn get_sticker_pack(
    handle: u64,
    document_id: i64,
) -> Result<crate::StickerPackDto, MtprotoError> {
    with_client_mut(handle, |state| {
        let dto = call_with_migrate(state, |state| {
            crate::sticker_rpc::get_sticker_pack(
                &mut state.snapshot,
                state.api_id,
                &mut state.media,
                document_id,
            )
        })?;
        Ok(dto)
    })
}

pub fn get_sticker_set(
    handle: u64,
    set_id: i64,
    access_hash: i64,
) -> Result<crate::StickerPackDto, MtprotoError> {
    with_client_mut(handle, |state| {
        let dto = call_with_migrate(state, |state| {
            crate::sticker_rpc::get_sticker_set(
                &mut state.snapshot,
                state.api_id,
                &mut state.media,
                set_id,
                access_hash,
            )
        })?;
        Ok(dto)
    })
}

pub fn get_all_stickers(handle: u64, hash: i64) -> Result<crate::StickerCatalogDto, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            crate::sticker_rpc::get_all_stickers(&mut state.snapshot, state.api_id, hash)
        })
    })
}

pub fn get_emoji_stickers(
    handle: u64,
    hash: i64,
) -> Result<crate::StickerCatalogDto, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            crate::sticker_rpc::get_emoji_stickers(&mut state.snapshot, state.api_id, hash)
        })
    })
}

pub fn get_stickers(
    handle: u64,
    emoticon: String,
    hash: i64,
) -> Result<crate::StickerListDto, MtprotoError> {
    with_client_mut(handle, |state| {
        let dto = call_with_migrate(state, |state| {
            crate::sticker_rpc::get_stickers(
                &mut state.snapshot,
                state.api_id,
                &mut state.media,
                &emoticon,
                hash,
            )
        })?;
        Ok(dto)
    })
}

pub fn get_message_read_participants(
    handle: u64,
    chat_id: i64,
    msg_id: i32,
) -> Result<crate::read_receipts_rpc::ReadParticipantsDto, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                crate::read_receipts_rpc::get_message_read_participants(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    chat_id,
                    msg_id,
                )
            })
        })
    })
}

pub fn get_outbox_read_date(
    handle: u64,
    chat_id: i64,
    msg_id: i32,
) -> Result<crate::read_receipts_rpc::OutboxReadDto, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                crate::read_receipts_rpc::get_outbox_read_date(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    chat_id,
                    msg_id,
                )
            })
        })
    })
}

pub fn get_read_receipt_config(
    handle: u64,
) -> Result<crate::read_receipts_rpc::ReadReceiptConfigDto, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            crate::read_receipts_rpc::fetch_read_receipt_config(&mut state.snapshot, state.api_id)
        })
    })
}
