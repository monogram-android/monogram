//! https://core.telegram.org/api/folders
//! https://core.telegram.org/method/messages.getDialogFilters
//! https://core.telegram.org/method/messages.updateDialogFilter

use std::collections::HashMap;

use tellers_mtproto::latest::api::{
    Bool, DialogFilter, DialogFilterConstructor, InputPeer, MessagesDialogFilters,
    MessagesGetDialogFiltersRequest, MessagesUpdateDialogFilterRequest,
    MessagesUpdateDialogFiltersOrderRequest, TextWithEntities, TextWithEntitiesConstructor, True,
    TrueConstructor, Vector, VectorConstructor,
};
use tellers_mtproto_session::Snapshot;

use crate::api_invoke;
use crate::peers::{self, input_peer_from_cached, vector_boxed_items, CachedPeer};
use crate::{FolderDto, MtprotoError};

pub(crate) fn folder_title(title: &TextWithEntities) -> String {
    match title {
        TextWithEntities::TextWithEntities(t) => t.text.clone(),
        _ => "Folder".into(),
    }
}

pub(crate) fn collect_input_peer_ids(peers: impl Iterator<Item = InputPeer>) -> Vec<i64> {
    peers
        .filter_map(|peer| peers::chat_id_from_input_peer(&peer))
        .collect()
}

pub(crate) fn flag(value: &Option<Box<True>>) -> bool {
    value.is_some()
}

pub fn get_folders(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
) -> Result<Vec<FolderDto>, MtprotoError> {
    let response: MessagesDialogFilters =
        api_invoke::invoke_api(snapshot, api_id, MessagesGetDialogFiltersRequest {})?;
    let MessagesDialogFilters::MessagesDialogFilters(filters) = response else {
        return Err(MtprotoError::Message(
            "unexpected messages.dialogFilters".into(),
        ));
    };
    let mut out = Vec::new();
    for filter in vector_boxed_items(&filters.filters) {
        match filter {
            DialogFilter::DialogFilter(f) => {
                let _ = peers;
                let pinned_chat_ids =
                    collect_input_peer_ids(vector_boxed_items(&f.pinned_peers).cloned());
                let include = collect_input_peer_ids(vector_boxed_items(&f.include_peers).cloned());
                let exclude_chat_ids =
                    collect_input_peer_ids(vector_boxed_items(&f.exclude_peers).cloned());
                out.push(FolderDto {
                    id: f.id,
                    title: folder_title(&f.title),
                    chat_ids: merge_folder_chat_ids(&pinned_chat_ids, &include),
                    exclude_chat_ids,
                    include_contacts: flag(&f.contacts),
                    include_non_contacts: flag(&f.non_contacts),
                    include_groups: flag(&f.groups),
                    include_channels: flag(&f.broadcasts),
                    include_bots: flag(&f.bots),
                    exclude_muted: flag(&f.exclude_muted),
                    exclude_read: flag(&f.exclude_read),
                    exclude_archived: flag(&f.exclude_archived),
                    emoticon: f.emoticon.clone().unwrap_or_default(),
                    pinned_chat_ids,
                });
            }
            DialogFilter::DialogFilterDefault(_) => {
                out.push(FolderDto {
                    id: 0,
                    title: "All chats".into(),
                    chat_ids: Vec::new(),
                    exclude_chat_ids: Vec::new(),
                    include_contacts: true,
                    include_non_contacts: true,
                    include_groups: true,
                    include_channels: true,
                    include_bots: true,
                    exclude_muted: false,
                    exclude_read: false,
                    exclude_archived: true,
                    emoticon: String::new(),
                    pinned_chat_ids: Vec::new(),
                });
            }
            DialogFilter::DialogFilterChatlist(f) => {
                let pinned_chat_ids =
                    collect_input_peer_ids(vector_boxed_items(&f.pinned_peers).cloned());
                let include = collect_input_peer_ids(vector_boxed_items(&f.include_peers).cloned());
                out.push(FolderDto {
                    id: f.id,
                    title: folder_title(&f.title),
                    chat_ids: merge_folder_chat_ids(&pinned_chat_ids, &include),
                    exclude_chat_ids: Vec::new(),
                    include_contacts: false,
                    include_non_contacts: false,
                    include_groups: false,
                    include_channels: false,
                    include_bots: false,
                    exclude_muted: false,
                    exclude_read: false,
                    exclude_archived: false,
                    emoticon: f.emoticon.clone().unwrap_or_default(),
                    pinned_chat_ids,
                });
            }
            _ => {}
        }
    }
    Ok(out)
}

pub(crate) fn merge_folder_chat_ids(pinned: &[i64], include: &[i64]) -> Vec<i64> {
    let mut chat_ids = pinned.to_vec();
    for id in include {
        if !chat_ids.contains(id) {
            chat_ids.push(*id);
        }
    }
    chat_ids
}

pub(crate) fn folder_flag(on: bool) -> Option<Box<True>> {
    on.then(|| Box::new(True::True(TrueConstructor {})))
}

pub(crate) fn folder_input_peers(
    peers: &HashMap<i64, CachedPeer>,
    ids: &[i64],
) -> Vector<Box<InputPeer>> {
    let items: Vec<Box<InputPeer>> = ids
        .iter()
        .filter_map(|id| {
            peers
                .get(id)
                .map(|cached| Box::new(input_peer_from_cached(cached)))
        })
        .collect();
    Vector::Vector(VectorConstructor {
        field_0: items.len() as u32,
        field_1: items,
    })
}

pub fn update_folder(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    folder: &FolderDto,
) -> Result<(), MtprotoError> {
    if folder.id <= 1 {
        return Err(MtprotoError::Message("folder id required".into()));
    }
    let title = folder.title.trim();
    if title.is_empty() {
        return Err(MtprotoError::Message("folder title required".into()));
    }
    let mut flags = 0u32;
    if folder.include_contacts {
        flags |= DialogFilterConstructor::CONTACTS_FLAG;
    }
    if folder.include_non_contacts {
        flags |= DialogFilterConstructor::NON_CONTACTS_FLAG;
    }
    if folder.include_groups {
        flags |= DialogFilterConstructor::GROUPS_FLAG;
    }
    if folder.include_channels {
        flags |= DialogFilterConstructor::BROADCASTS_FLAG;
    }
    if folder.include_bots {
        flags |= DialogFilterConstructor::BOTS_FLAG;
    }
    if folder.exclude_muted {
        flags |= DialogFilterConstructor::EXCLUDE_MUTED_FLAG;
    }
    if folder.exclude_read {
        flags |= DialogFilterConstructor::EXCLUDE_READ_FLAG;
    }
    if folder.exclude_archived {
        flags |= DialogFilterConstructor::EXCLUDE_ARCHIVED_FLAG;
    }
    let emoticon = folder.emoticon.trim();
    if !emoticon.is_empty() {
        flags |= DialogFilterConstructor::EMOTICON_FLAG;
    }
    let include_ids: Vec<i64> = folder
        .chat_ids
        .iter()
        .copied()
        .filter(|id| !folder.pinned_chat_ids.contains(id))
        .collect();
    let filter = DialogFilter::DialogFilter(DialogFilterConstructor {
        flags,
        contacts: folder_flag(folder.include_contacts),
        non_contacts: folder_flag(folder.include_non_contacts),
        groups: folder_flag(folder.include_groups),
        broadcasts: folder_flag(folder.include_channels),
        bots: folder_flag(folder.include_bots),
        exclude_muted: folder_flag(folder.exclude_muted),
        exclude_read: folder_flag(folder.exclude_read),
        exclude_archived: folder_flag(folder.exclude_archived),
        title_noanimate: None,
        id: folder.id,
        title: Box::new(TextWithEntities::TextWithEntities(
            TextWithEntitiesConstructor {
                text: title.to_string(),
                entities: Box::new(Vector::Vector(VectorConstructor {
                    field_0: 0,
                    field_1: Vec::new(),
                })),
            },
        )),
        emoticon: (!emoticon.is_empty()).then(|| emoticon.to_string()),
        color: None,
        pinned_peers: Box::new(folder_input_peers(peers, &folder.pinned_chat_ids)),
        include_peers: Box::new(folder_input_peers(peers, &include_ids)),
        exclude_peers: Box::new(folder_input_peers(peers, &folder.exclude_chat_ids)),
    });
    let _: Bool = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesUpdateDialogFilterRequest {
            flags: MessagesUpdateDialogFilterRequest::FILTER_FLAG,
            id: folder.id,
            filter: Some(Box::new(filter)),
        },
    )?;
    Ok(())
}

pub fn delete_folder(snapshot: &mut Snapshot, api_id: i32, id: i32) -> Result<(), MtprotoError> {
    if id <= 1 {
        return Err(MtprotoError::Message("folder id required".into()));
    }
    let _: Bool = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesUpdateDialogFilterRequest {
            flags: 0,
            id,
            filter: None,
        },
    )?;
    Ok(())
}

pub fn update_folder_order(
    snapshot: &mut Snapshot,
    api_id: i32,
    order: &[i32],
) -> Result<(), MtprotoError> {
    let items = order.to_vec();
    let _: Bool = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesUpdateDialogFiltersOrderRequest {
            order: Box::new(Vector::Vector(VectorConstructor {
                field_0: items.len() as u32,
                field_1: items,
            })),
        },
    )?;
    Ok(())
}
