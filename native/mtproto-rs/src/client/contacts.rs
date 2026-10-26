use super::*;
use tellers_mtproto::latest::api::*;

#[derive(Clone, Debug, uniffi::Record)]
pub struct ContactImportSpec {
    pub client_id: i64,
    pub phone: String,
    pub first_name: String,
    pub last_name: String,
}
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct ContactImportMatch {
    pub client_id: i64,
    pub user_id: i64,
}
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct ContactImportPopularity {
    pub client_id: i64,
    pub importers: i32,
}
#[derive(Clone, Debug, uniffi::Record)]
pub struct ContactImportResult {
    pub imported: Vec<ContactImportMatch>,
    pub retry_client_ids: Vec<i64>,
    pub popular: Vec<ContactImportPopularity>,
}

fn import_request(
    contacts: &[ContactImportSpec],
) -> Result<ContactsImportContactsRequest, MtprotoError> {
    if !(1..=100).contains(&contacts.len()) {
        return Err(MtprotoError::Message("contact batch size invalid".into()));
    }
    let mut ids = std::collections::HashSet::new();
    let mut phones = std::collections::HashSet::new();
    let mut rows = Vec::with_capacity(contacts.len());
    for contact in contacts {
        crate::messages::validate_contact(&contact.phone, &contact.first_name, &contact.last_name)?;
        if contact.client_id == 0
            || !ids.insert(contact.client_id)
            || !phones.insert(contact.phone.trim_start_matches('+'))
        {
            return Err(MtprotoError::Message(
                "contact batch identity invalid".into(),
            ));
        }
        rows.push(Box::new(InputContact::InputPhoneContact(
            InputPhoneContactConstructor {
                flags: 0,
                client_id: contact.client_id,
                phone: contact.phone.clone(),
                first_name: contact.first_name.clone(),
                last_name: contact.last_name.clone(),
                note: None,
            },
        )));
    }
    Ok(ContactsImportContactsRequest {
        contacts: Box::new(Vector::Vector(VectorConstructor {
            field_0: rows.len() as u32,
            field_1: rows,
        })),
    })
}

fn import_result(
    contacts: &[ContactImportSpec],
    body: &ContactsImportedContactsConstructor,
) -> Result<ContactImportResult, MtprotoError> {
    let requested = contacts
        .iter()
        .map(|c| c.client_id)
        .collect::<std::collections::HashSet<_>>();
    let mut imported = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for row in crate::peers::vector_boxed_items(&body.imported) {
        let ImportedContact::ImportedContact(row) = row;
        if row.user_id <= 0 || !requested.contains(&row.client_id) || !seen.insert(row.client_id) {
            return Err(MtprotoError::Message(
                "contact import response identity invalid".into(),
            ));
        }
        imported.push(ContactImportMatch {
            client_id: row.client_id,
            user_id: row.user_id,
        });
    }
    let Vector::Vector(retries) = body.retry_contacts.as_ref();
    for id in &retries.field_1 {
        if !requested.contains(id) || !seen.insert(*id) {
            return Err(MtprotoError::Message(
                "contact import retry identity invalid".into(),
            ));
        }
    }
    let mut popular = Vec::new();
    let mut popular_ids = std::collections::HashSet::new();
    for row in crate::peers::vector_boxed_items(&body.popular_invites) {
        let PopularContact::PopularContact(row) = row;
        if !requested.contains(&row.client_id)
            || !popular_ids.insert(row.client_id)
            || row.importers < 0
        {
            return Err(MtprotoError::Message(
                "contact popularity response invalid".into(),
            ));
        }
        popular.push(ContactImportPopularity {
            client_id: row.client_id,
            importers: row.importers,
        });
    }
    Ok(ContactImportResult {
        imported,
        retry_client_ids: retries.field_1.clone(),
        popular,
    })
}

pub fn import_contacts(
    handle: u64,
    contacts: Vec<ContactImportSpec>,
) -> Result<ContactImportResult, MtprotoError> {
    let request = import_request(&contacts)?;
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let response: ContactsImportedContacts =
                crate::api_invoke::invoke_api(&mut state.snapshot, state.api_id, request.clone())?;
            let ContactsImportedContacts::ContactsImportedContacts(body) = response;
            let result = import_result(&contacts, &body)?;
            crate::dialogs::cache_from_users_chats(
                &mut state.peers,
                &mut state.media,
                crate::peers::vector_boxed_items(&body.users).cloned(),
                std::iter::empty(),
            );
            Ok(result)
        })
    })
}

#[cfg(test)]
#[path = "../../tests/unit/client_contact_import_tests.rs"]
mod tests;
