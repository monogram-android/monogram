use tellers_mtproto::latest::api::{PeerNotifySettings, PeerNotifySettingsConstructor};

fn settings(mute_until: Option<i32>) -> PeerNotifySettings {
    PeerNotifySettings::PeerNotifySettings(PeerNotifySettingsConstructor {
        flags: 0,
        show_previews: None,
        silent: None,
        mute_until,
        ios_sound: None,
        android_sound: None,
        other_sound: None,
        stories_muted: None,
        stories_hide_sender: None,
        stories_ios_sound: None,
        stories_android_sound: None,
        stories_other_sound: None,
    })
}

fn now() -> i32 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i32)
        .unwrap_or(0)
}

#[test]
fn future_mute_is_muted_and_is_an_override() {
    let s = settings(Some(now() + 3600));
    assert!(super::is_muted(&s));
    assert!(super::is_mute_override(&s));
}

#[test]
fn absent_mute_until_inherits_the_type_default() {
    let s = settings(None);
    assert!(!super::is_muted(&s));
    assert!(!super::is_mute_override(&s));
}

#[test]
fn expired_mute_is_unmuted_but_still_an_override() {
    let s = settings(Some(now() - 60));
    assert!(!super::is_muted(&s));
    assert!(super::is_mute_override(&s));
}

#[test]
fn forever_mute_is_muted() {
    // Telegram uses i32::MAX for "mute forever".
    let s = settings(Some(i32::MAX));
    assert!(super::is_muted(&s));
    assert!(super::is_mute_override(&s));
}
