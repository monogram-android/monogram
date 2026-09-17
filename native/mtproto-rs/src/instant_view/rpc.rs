use crate::HashMap;

use serde_json::Value;
use tellers_mtproto::latest::api::{MessagesGetWebPageRequest, MessagesWebPage, Page, WebPage};
use tellers_mtproto_session::Snapshot;

use super::blocks::map_block;
use super::index::{PageContext, document_id, index_map_previews, index_page_media, photo_id};
use crate::MtprotoError;
use crate::api_invoke::invoke_api;
use crate::dialogs;
use crate::media::MediaIndex;
use crate::peers::{CachedPeer, vector_boxed_items};

#[derive(Clone, Debug, uniffi::Record)]
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

pub fn get_web_page(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &mut HashMap<i64, CachedPeer>,
    media: &mut MediaIndex,
    url: String,
    hash: i32,
) -> Result<InstantViewDto, MtprotoError> {
    let response: MessagesWebPage =
        invoke_api(snapshot, api_id, MessagesGetWebPageRequest { url, hash })?;
    let MessagesWebPage::MessagesWebPage(body) = response;
    dialogs::cache_from_users_chats(
        peers,
        media,
        vector_boxed_items(&body.users).cloned(),
        vector_boxed_items(&body.chats).cloned(),
    );
    let dto = map_messages_web_page(body.webpage.as_ref(), media, hash);
    if let WebPage::WebPage(page) = body.webpage.as_ref() {
        if let Some(Page::Page(cached)) = page.cached_page.as_ref().map(|p| p.as_ref()) {
            index_map_previews(
                snapshot,
                api_id,
                media,
                &page.url,
                vector_boxed_items(&cached.blocks),
            );
        }
    }
    Ok(dto)
}

pub fn map_messages_web_page(
    webpage: &WebPage,
    media: &mut MediaIndex,
    requested_hash: i32,
) -> InstantViewDto {
    match webpage {
        WebPage::WebPageNotModified(_) => InstantViewDto {
            url: String::new(),
            display_url: String::new(),
            title: None,
            site_name: None,
            description: None,
            webpage_type: None,
            hash: requested_hash,
            has_instant_view: false,
            part: false,
            rtl: false,
            v2: false,
            not_modified: true,
            blocks_json: "[]".into(),
        },
        WebPage::WebPageEmpty(_) | WebPage::WebPagePending(_) => InstantViewDto {
            url: String::new(),
            display_url: String::new(),
            title: None,
            site_name: None,
            description: None,
            webpage_type: None,
            hash: 0,
            has_instant_view: false,
            part: false,
            rtl: false,
            v2: false,
            not_modified: false,
            blocks_json: "[]".into(),
        },
        WebPage::WebPage(page) => map_web_page(page, media),
        _ => InstantViewDto {
            url: String::new(),
            display_url: String::new(),
            title: None,
            site_name: None,
            description: None,
            webpage_type: None,
            hash: 0,
            has_instant_view: false,
            part: false,
            rtl: false,
            v2: false,
            not_modified: false,
            blocks_json: "[]".into(),
        },
    }
}

pub(crate) fn map_web_page(
    page: &tellers_mtproto::latest::api::WebPageConstructor,
    media: &mut MediaIndex,
) -> InstantViewDto {
    let cached = page.cached_page.as_ref().map(|p| p.as_ref());
    let (part, rtl, v2, blocks, mut photos, mut documents) = match cached {
        Some(Page::Page(p)) => {
            index_page_media(
                media,
                &page.url,
                vector_boxed_items(&p.photos),
                vector_boxed_items(&p.documents),
            );
            (
                p.part.is_some(),
                p.rtl.is_some(),
                p.v2.is_some(),
                vector_boxed_items(&p.blocks).cloned().collect::<Vec<_>>(),
                vector_boxed_items(&p.photos).cloned().collect::<Vec<_>>(),
                vector_boxed_items(&p.documents)
                    .cloned()
                    .collect::<Vec<_>>(),
            )
        }
        _ => (false, false, false, Vec::new(), Vec::new(), Vec::new()),
    };
    if let Some(photo) = page.photo.as_deref() {
        index_page_media(media, &page.url, std::iter::once(photo), std::iter::empty());
    }
    if let Some(document) = page.document.as_deref() {
        index_page_media(
            media,
            &page.url,
            std::iter::empty(),
            std::iter::once(document),
        );
    }
    if let Some(cover) = page.photo.as_deref() {
        let cover_id = photo_id(cover);
        if !photos.iter().any(|existing| photo_id(existing) == cover_id) {
            photos.push(cover.clone());
        }
    }
    if let Some(cover) = page.document.as_deref() {
        let cover_id = document_id(cover);
        if !documents
            .iter()
            .any(|existing| document_id(existing) == cover_id)
        {
            documents.push(cover.clone());
        }
    }
    let ctx = PageContext {
        photos: photos
            .iter()
            .filter_map(|p| photo_id(p).map(|id| (id, p)))
            .collect(),
        documents: documents
            .iter()
            .filter_map(|d| document_id(d).map(|id| (id, d)))
            .collect(),
    };
    let blocks_json = Value::Array(blocks.iter().map(|b| map_block(b, &ctx)).collect()).to_string();
    InstantViewDto {
        url: page.url.clone(),
        display_url: page.display_url.clone(),
        title: page.title.clone(),
        site_name: page.site_name.clone(),
        description: page.description.clone(),
        webpage_type: page.type_.clone(),
        hash: page.hash,
        has_instant_view: cached.is_some(),
        part,
        rtl,
        v2,
        not_modified: false,
        blocks_json,
    }
}
