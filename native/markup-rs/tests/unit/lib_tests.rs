use super::*;

#[test]
fn parse_export_roundtrip() {
    let styled = parse_telegram_markdown("**hi**".into());
    assert_eq!(styled.text, "hi");
    assert_eq!(styled.entities[0].kind, "bold");
}
