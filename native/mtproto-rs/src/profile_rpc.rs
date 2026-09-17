//! Read-mostly profile lookups.
//! https://core.telegram.org/method/users.getFullUser
//! https://core.telegram.org/method/messages.getFullChat
//! https://core.telegram.org/method/channels.getFullChannel

#[cfg(test)]
mod tests {
    use crate::profile::{custom_or_role, profile_extra_json};

    #[test]
    fn admin_tags_default_to_role_and_preserve_custom_rank() {
        assert_eq!(custom_or_role(&None, "admin"), "role:admin");
        assert_eq!(
            custom_or_role(&Some("Helper".into()), "admin"),
            "rank:Helper"
        );
    }

    #[test]
    fn extra_json_omits_empty_and_keeps_counts() {
        assert_eq!(
            profile_extra_json(
                None, None, None, None, false, false, false, None, false, None
            ),
            None
        );
        let raw = profile_extra_json(
            Some(12),
            Some(3),
            Some(4),
            Some("+1"),
            true,
            true,
            false,
            Some(9),
            true,
            Some(false),
        )
        .expect("json");
        assert!(raw.contains("\"members\":12"));
        assert!(raw.contains("\"online\":3"));
        assert!(raw.contains("\"common\":4"));
        assert!(raw.contains("\"bot\":true"));
        assert!(raw.contains("\"verified\":true"));
        assert!(raw.contains("\"phone\":\"+1\""));
        assert!(raw.contains("\"premium\":true"));
        assert!(raw.contains("\"participants\":false"));
    }
}
