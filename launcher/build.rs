// embeds assets/icon.ico into the Windows exe (Explorer, taskbar, shortcuts, Settings > Apps)
fn main() {
    println!("cargo:rerun-if-changed=assets/icon.ico");
    println!("cargo:rerun-if-changed=build.rs");

    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        let mut res = winresource::WindowsResource::new();
        res.set_icon("assets/icon.ico");
        res.set("ProductName", "Strata");
        res.set("FileDescription", "Strata Launcher");
        res.compile().expect("could not embed the exe icon");
    }
}
