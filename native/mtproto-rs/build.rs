fn main() {
    println!("cargo:rerun-if-changed=src/lib.rs");
    println!("cargo:rerun-if-changed=src/vpx.rs");
    println!("cargo:rustc-check-cfg=cfg(has_libvpx)");
    let target = std::env::var("TARGET").unwrap_or_default();
    if !target.contains("android") {
        return;
    }
    let abi = if target.contains("x86_64") {
        "x86_64"
    } else if target.contains("aarch64") {
        "arm64-v8a"
    } else if target.contains("arm") {
        "armeabi-v7a"
    } else {
        return;
    };
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../vpx/prebuilt")
        .join(abi)
        .join("lib");
    let lib = dir.join("libvpx.a");
    println!("cargo:rerun-if-changed={}", lib.display());
    if lib.is_file() {
        println!("cargo:rustc-link-search=native={}", dir.display());
        println!("cargo:rustc-link-lib=static=vpx");
        println!("cargo:rustc-cfg=has_libvpx");
    }
}
