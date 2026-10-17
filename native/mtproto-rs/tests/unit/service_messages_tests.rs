use super::encode_service;

#[test]
fn encode_uses_unit_separator() {
    let raw = encode_service("pin", "Ada", "");
    assert_eq!(raw, "pin\u{1f}Ada\u{1f}");
    let add = encode_service("add", "Ada", "Bob, Carol");
    assert_eq!(add, "add\u{1f}Ada\u{1f}Bob, Carol");
}
