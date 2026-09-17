fn main() {
    // Proc-macro scaffolding is emitted from lib.rs via uniffi::setup_scaffolding!.
    println!("cargo:rerun-if-changed=src/lib.rs");
    println!("cargo:rerun-if-changed=src/markdown.rs");
    println!("cargo:rerun-if-changed=src/highlight.rs");
    println!("cargo:rerun-if-changed=src/math.rs");
    println!("cargo:rerun-if-changed=src/utf16.rs");
}
