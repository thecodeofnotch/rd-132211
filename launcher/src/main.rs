// no console window on Windows release builds
#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]

//! Strata installer / launcher / uninstaller in one small exe.
//!
//! - not installed  -> install screen (downloads the latest portable zip from GitHub Releases)
//! - installed      -> launch screen (Play, Update when a newer release exists, Uninstall)
//! - `--uninstall`  -> opens straight on the uninstall screen (Settings > Apps points here)
//! - the launch screen also edits the player's username (the `username` line of the game's options.txt)
//!
//! Layout on disk (default Windows location shown):
//!   %LOCALAPPDATA%\Programs\Strata\Strata\          the game (the zip's top folder)
//!   %LOCALAPPDATA%\Programs\Strata\StrataLauncher.exe   copy of this exe (shortcuts + uninstaller use it)
//!   %LOCALAPPDATA%\StrataLauncher\install.json      what is installed, and where
//! The game's own data (worlds, options) lives elsewhere (%APPDATA%\Strata) and survives updates.

use eframe::egui;
use serde::{Deserialize, Serialize};
use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::mpsc::{channel, Receiver, Sender};
use std::thread;
use std::time::{Duration, Instant};

// ── configuration ────────────────────────────────────────────────────────────

const REPO: &str = "noelledotjpg/Strata";
const GAME_FOLDER: &str = "Strata"; // top folder inside the portable zip
const PLATFORM: &str = if cfg!(windows) { "windows" } else { "linux" };
const LAUNCHER_NAME: &str = if cfg!(windows) { "StrataLauncher.exe" } else { "strata-launcher" };

// banners are 128x300 pixel art, drawn at an integer scale so the pixels stay even.
// window = banner + CONTENT_W wide, as tall as the banner
const BANNER_SCALE: f32 = 1.0;
const BANNER_W: f32 = 128.0 * BANNER_SCALE;
const BANNER_H: f32 = 300.0 * BANNER_SCALE;
const CONTENT_W: f32 = 372.0;
const FOOTER_H: f32 = 44.0;
const FADE_SECS: f32 = 0.7; // dithered banner transition

// the username lives in the game's options.txt, these mirror GameSettings.java
const DEFAULT_USERNAME: &str = "Hakumu";
const MAX_USERNAME_LEN: usize = 16;
const OPTIONS_FILE: &str = "options.txt";

type Res<T> = Result<T, Box<dyn std::error::Error>>;

// ── data ─────────────────────────────────────────────────────────────────────

#[derive(Clone)]
struct Release {
    tag: String,
    url: String,
    size: u64,
}

#[derive(Clone, Serialize, Deserialize)]
struct InstallInfo {
    version: String,
    path: String,
}

enum Msg {
    Remote(Result<Release, String>),
    Progress { stage: String, done: u64, total: u64 },
    InstallDone(Result<InstallInfo, String>),
    UninstallDone(Result<(), String>),
}

#[derive(Clone)]
struct Reporter {
    tx: Sender<Msg>,
    ctx: egui::Context,
}

impl Reporter {
    fn send(&self, m: Msg) {
        let _ = self.tx.send(m);
        self.ctx.request_repaint();
    }
    fn progress(&self, stage: &str, done: u64, total: u64) {
        self.send(Msg::Progress { stage: stage.to_string(), done, total });
    }
}

// ── paths and state ──────────────────────────────────────────────────────────

fn default_install_dir() -> PathBuf {
    let base = dirs::data_local_dir().unwrap_or_else(|| PathBuf::from("."));
    if cfg!(windows) {
        base.join("Programs").join("Strata")
    } else {
        base.join("strata-game") // not "strata": that is the game's own data folder on Linux
    }
}

/// where the game keeps worlds and options when it is not portable (mirrors GameDirectory.java)
fn game_data_dir() -> Option<PathBuf> {
    dirs::data_dir().map(|d| d.join(if cfg!(windows) { "Strata" } else { "strata" }))
}

fn state_path() -> Option<PathBuf> {
    dirs::data_local_dir().map(|d| d.join("StrataLauncher").join("install.json"))
}

fn read_state() -> Option<InstallInfo> {
    let text = fs::read_to_string(state_path()?).ok()?;
    let info: InstallInfo = serde_json::from_str(&text).ok()?;
    // game folder deleted by hand -> treat as not installed
    if Path::new(&info.path).join(GAME_FOLDER).is_dir() {
        Some(info)
    } else {
        None
    }
}

fn write_state(info: &InstallInfo) -> Res<()> {
    let path = state_path().ok_or("no local data folder")?;
    fs::create_dir_all(path.parent().unwrap())?;
    fs::write(path, serde_json::to_string_pretty(info)?)?;
    Ok(())
}

fn same_file(a: &Path, b: &Path) -> bool {
    matches!((fs::canonicalize(a), fs::canonicalize(b)), (Ok(x), Ok(y)) if x == y)
}

// ── network ──────────────────────────────────────────────────────────────────

fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .user_agent("strata-launcher")
        .timeout_connect(Duration::from_secs(15))
        .timeout_read(Duration::from_secs(30))
        .build()
}

/// Latest *published* release (GitHub's /latest skips drafts and pre-releases).
fn fetch_latest() -> Result<Release, String> {
    let url = format!("https://api.github.com/repos/{REPO}/releases/latest");
    let resp = agent()
        .get(&url)
        .set("Accept", "application/vnd.github+json")
        .call()
        .map_err(|e| format!("Could not reach GitHub: {e}"))?;
    let json: serde_json::Value = resp.into_json().map_err(|e| format!("Bad response from GitHub: {e}"))?;

    let tag = json["tag_name"].as_str().ok_or("The release has no tag")?.to_string();
    let suffix = format!("-{PLATFORM}-x64-portable.zip");
    let asset = json["assets"]
        .as_array()
        .and_then(|list| list.iter().find(|a| a["name"].as_str().map_or(false, |n| n.ends_with(&suffix))))
        .ok_or_else(|| format!("Release {tag} has no {PLATFORM} download"))?;

    Ok(Release {
        tag,
        url: asset["browser_download_url"].as_str().ok_or("Asset has no download link")?.to_string(),
        size: asset["size"].as_u64().unwrap_or(0),
    })
}

fn download(rep: &Reporter, url: &str, dest: &Path, size_hint: u64) -> Res<()> {
    let resp = agent().get(url).call().map_err(|e| format!("Download failed: {e}"))?;
    let total = resp
        .header("Content-Length")
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(size_hint);

    let mut reader = resp.into_reader();
    let mut out = File::create(dest)?;
    let mut buf = vec![0u8; 64 * 1024];
    let mut done = 0u64;
    loop {
        let n = reader.read(&mut buf).map_err(|e| format!("Download interrupted: {e}"))?;
        if n == 0 {
            break;
        }
        out.write_all(&buf[..n])?;
        done += n as u64;
        rep.progress("Downloading", done, total);
    }
    out.flush()?;
    Ok(())
}

// ── install / uninstall ──────────────────────────────────────────────────────

fn extract(rep: &Reporter, zip_path: &Path, dest: &Path) -> Res<()> {
    let mut archive = zip::ZipArchive::new(File::open(zip_path)?)?;
    let count = archive.len();
    fs::create_dir_all(dest)?;

    for i in 0..count {
        let mut entry = archive.by_index(i)?;
        // enclosed_name() rejects "../" and absolute paths (zip-slip)
        let rel = match entry.enclosed_name() {
            Some(p) => p.to_path_buf(),
            None => continue,
        };
        let out_path = dest.join(rel);

        if entry.is_dir() {
            fs::create_dir_all(&out_path)?;
        } else {
            if let Some(parent) = out_path.parent() {
                fs::create_dir_all(parent)?;
            }

            #[cfg(unix)]
            {
                let mode = entry.unix_mode().unwrap_or(0);
                if mode & 0o170000 == 0o120000 {
                    // symlink entry: the content is the link target
                    let mut target = String::new();
                    entry.read_to_string(&mut target)?;
                    std::os::unix::fs::symlink(target, &out_path)?;
                    continue;
                }
            }

            let mut out = File::create(&out_path)?;
            std::io::copy(&mut entry, &mut out)?;
            drop(out);

            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                if let Some(mode) = entry.unix_mode() {
                    // keeps the executable bit on bin/Strata and the bundled java
                    fs::set_permissions(&out_path, fs::Permissions::from_mode(mode & 0o777))?;
                }
            }
        }

        if i % 25 == 0 || i + 1 == count {
            rep.progress("Extracting", (i + 1) as u64, count as u64);
        }
    }
    Ok(())
}

fn do_install(rep: &Reporter, rel: &Release, dir: &Path, desktop_shortcut: bool) -> Res<InstallInfo> {
    fs::create_dir_all(dir).map_err(|e| format!("Could not create {}: {e}", dir.display()))?;

    let zip_path = std::env::temp_dir().join("strata-download.zip");
    download(rep, &rel.url, &zip_path, rel.size)?;

    let staging = dir.join(".staging");
    let _ = fs::remove_dir_all(&staging);
    extract(rep, &zip_path, &staging)?;
    let _ = fs::remove_file(&zip_path);

    let extracted = staging.join(GAME_FOLDER);
    if !extracted.is_dir() {
        return Err(format!("The download does not contain a \"{GAME_FOLDER}\" folder").into());
    }

    rep.progress("Installing", 0, 0);
    let game = dir.join(GAME_FOLDER);
    if game.exists() {
        fs::remove_dir_all(&game)
            .map_err(|e| format!("Could not replace the old version ({e}). Is Strata still running?"))?;
    }
    fs::rename(&extracted, &game)?;
    let _ = fs::remove_dir_all(&staging);

    // installed copies keep worlds and options in the user's data folder, not next to the game
    let _ = fs::remove_file(game.join("portable.txt"));

    // copy ourselves next to the game: shortcuts and the Settings > Apps entry point at this copy
    let launcher = dir.join(LAUNCHER_NAME);
    let me = std::env::current_exe()?;
    if !same_file(&me, &launcher) {
        fs::copy(&me, &launcher).map_err(|e| format!("Could not copy the launcher: {e}"))?;
    }

    // shortcuts / uninstall entry are nice-to-have, a failure here should not fail the install
    platform::install_integration(&launcher, &game, dir, &rel.tag, desktop_shortcut);

    let info = InstallInfo { version: rel.tag.clone(), path: dir.to_string_lossy().into_owned() };
    write_state(&info)?;
    Ok(info)
}

fn do_uninstall(info: &InstallInfo, delete_data: bool) -> Res<()> {
    let dir = PathBuf::from(&info.path);

    // only ever delete what we installed, never the whole folder the user picked
    let game = dir.join(GAME_FOLDER);
    if game.exists() {
        fs::remove_dir_all(&game)
            .map_err(|e| format!("Could not delete {} ({e}). Is Strata still running?", game.display()))?;
    }
    let _ = fs::remove_dir_all(dir.join(".staging"));

    platform::remove_integration();

    let launcher = dir.join(LAUNCHER_NAME);
    if launcher.exists() && fs::remove_file(&launcher).is_err() {
        // Windows will not delete a running exe: a detached helper does it after we exit
        platform::schedule_self_delete(&launcher, &dir);
    }
    let _ = fs::remove_dir(&dir); // only succeeds if empty

    if delete_data {
        if let Some(data) = game_data_dir() {
            if data.exists() {
                fs::remove_dir_all(&data)
                    .map_err(|e| format!("Could not delete {} ({e})", data.display()))?;
            }
        }
    }

    if let Some(state) = state_path() {
        let _ = fs::remove_file(&state);
        if let Some(parent) = state.parent() {
            let _ = fs::remove_dir(parent);
        }
    }
    Ok(())
}

// ── game options: the username ───────────────────────────────────────────────
// The game keeps its settings in options.txt (a java.util.Properties file) in its data folder.
// The launcher only ever touches the `username` line, everything else in the file is left as it is.

fn options_path() -> Option<PathBuf> {
    game_data_dir().map(|d| d.join(OPTIONS_FILE))
}

/// Properties files are ISO-8859-1, so every byte is exactly one char
fn read_latin1(path: &Path) -> Option<String> {
    let bytes = fs::read(path).ok()?;
    Some(bytes.iter().map(|&b| b as char).collect())
}

fn unescape(s: &str) -> String {
    let mut units: Vec<u16> = Vec::new();
    let mut it = s.chars();
    while let Some(c) = it.next() {
        if c != '\\' {
            let mut b = [0u16; 2];
            units.extend_from_slice(c.encode_utf16(&mut b));
            continue;
        }
        match it.next() {
            Some('u') => {
                let hex: String = it.by_ref().take(4).collect();
                if let Ok(u) = u16::from_str_radix(&hex, 16) {
                    units.push(u);
                }
            }
            Some('t') => units.push(9),
            Some('n') => units.push(10),
            Some('r') => units.push(13),
            Some('f') => units.push(12),
            Some(other) => {
                let mut b = [0u16; 2];
                units.extend_from_slice(other.encode_utf16(&mut b));
            }
            None => {}
        }
    }
    String::from_utf16_lossy(&units)
}

/// what Properties.store writes for a value: ASCII only, anything else as \uXXXX
fn escape_value(s: &str) -> String {
    let mut out = String::new();
    for c in s.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '=' | ':' | '#' | '!' => {
                out.push('\\');
                out.push(c);
            }
            ' '..='~' => out.push(c),
            _ => {
                let mut b = [0u16; 2];
                for u in c.encode_utf16(&mut b).iter() {
                    out.push_str(&format!("\\u{:04X}", u));
                }
            }
        }
    }
    out
}

/// (key, value) of one properties line, None for blank lines and comments
fn parse_property(line: &str) -> Option<(String, String)> {
    let line = line.trim_start();
    if line.is_empty() || line.starts_with('#') || line.starts_with('!') {
        return None;
    }
    let mut key_end = line.len();
    let mut escaped = false;
    for (i, c) in line.char_indices() {
        if escaped {
            escaped = false;
        } else if c == '\\' {
            escaped = true;
        } else if c == '=' || c == ':' || c.is_whitespace() {
            key_end = i;
            break;
        }
    }
    let mut rest = line[key_end..].trim_start();
    if rest.starts_with('=') || rest.starts_with(':') {
        rest = rest[1..].trim_start();
    }
    Some((unescape(&line[..key_end]), unescape(rest)))
}

/// same rules as GameSettings.setUsername: no control characters, trimmed, at most 16, empty = default
fn sanitize_username(raw: &str) -> String {
    let kept: String = raw.chars().filter(|&c| c as u32 >= 32 && c as u32 != 127).collect();
    let mut out = String::new();
    let mut units = 0;
    for c in kept.trim().chars() {
        units += c.len_utf16(); // Java counts UTF-16 units
        if units > MAX_USERNAME_LEN {
            break;
        }
        out.push(c);
    }
    let out = out.trim().to_string();
    if out.is_empty() {
        DEFAULT_USERNAME.to_string()
    } else {
        out
    }
}

/// the saved username, None if options.txt does not exist or has no username line
fn read_username() -> Option<String> {
    let text = read_latin1(&options_path()?)?;
    let mut found = None;
    for line in text.lines() {
        if let Some((key, value)) = parse_property(line) {
            if key == "username" {
                found = Some(value);
            }
        }
    }
    found
}

fn write_username(name: &str) -> Res<()> {
    let path = options_path().ok_or("no data folder")?;
    fs::create_dir_all(path.parent().ok_or("bad options path")?)?;

    let existing = read_latin1(&path).unwrap_or_default();
    let new_line = format!("username={}", escape_value(name));

    let mut lines: Vec<String> = Vec::new();
    let mut replaced = false;
    for line in existing.lines() {
        if matches!(parse_property(line), Some((key, _)) if key == "username") {
            if !replaced {
                lines.push(new_line.clone());
                replaced = true;
            }
        } else {
            lines.push(line.to_string());
        }
    }
    if !replaced {
        lines.push(new_line);
    }

    let mut text = lines.join("\n");
    text.push('\n');
    let bytes: Vec<u8> = text.chars().map(|c| c as u32 as u8).collect(); // everything here is < 256

    let tmp = path.with_extension("txt.tmp");
    fs::write(&tmp, &bytes)?;
    fs::rename(&tmp, &path)?;
    Ok(())
}

// ── platform specific bits ───────────────────────────────────────────────────

#[cfg(windows)]
mod platform {
    use super::*;
    use std::os::windows::process::CommandExt;
    use winreg::{enums::HKEY_CURRENT_USER, RegKey};

    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    const DETACHED_PROCESS: u32 = 0x0000_0008;
    const UNINSTALL_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Uninstall\Strata";

    pub fn game_exe(game_dir: &Path) -> PathBuf {
        game_dir.join("Strata.exe")
    }

    fn quote(s: &str) -> String {
        format!("'{}'", s.replace('\'', "''"))
    }

    fn powershell(script: &str) -> Res<()> {
        let status = Command::new("powershell")
            .args(["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script])
            .creation_flags(CREATE_NO_WINDOW)
            .status()?;
        if status.success() {
            Ok(())
        } else {
            Err("powershell failed".into())
        }
    }

    fn shortcut_script(folder: &str, launcher: &Path, icon: &Path) -> String {
        format!(
            "$l = Join-Path ([Environment]::GetFolderPath('{folder}')) 'Strata.lnk'; \
             $s = $ws.CreateShortcut($l); \
             $s.TargetPath = {target}; \
             $s.WorkingDirectory = {work}; \
             $s.IconLocation = {icon}; \
             $s.Save()",
            folder = folder,
            target = quote(&launcher.to_string_lossy()),
            work = quote(&launcher.parent().unwrap_or(Path::new(".")).to_string_lossy()),
            icon = quote(&format!("{},0", icon.to_string_lossy())),
        )
    }

    pub fn install_integration(launcher: &Path, game_dir: &Path, install_dir: &Path, version: &str, desktop: bool) {
        let icon = game_exe(game_dir);

        let mut script = String::from("$ws = New-Object -ComObject WScript.Shell; ");
        script.push_str(&shortcut_script("Programs", launcher, &icon)); // Start menu
        if desktop {
            script.push_str("; ");
            script.push_str(&shortcut_script("Desktop", launcher, &icon));
        }
        let _ = powershell(&script);

        // Settings > Apps entry, per user so no admin prompt
        let _ = (|| -> Res<()> {
            let hkcu = RegKey::predef(HKEY_CURRENT_USER);
            let (key, _) = hkcu.create_subkey(UNINSTALL_KEY)?;
            key.set_value("DisplayName", &"Strata")?;
            key.set_value("DisplayVersion", &version.to_string())?;
            key.set_value("Publisher", &"noelledotjpg")?;
            key.set_value("InstallLocation", &install_dir.to_string_lossy().to_string())?;
            key.set_value("DisplayIcon", &icon.to_string_lossy().to_string())?;
            key.set_value("UninstallString", &format!("\"{}\" --uninstall", launcher.display()))?;
            key.set_value("NoModify", &1u32)?;
            key.set_value("NoRepair", &1u32)?;
            Ok(())
        })();
    }

    pub fn remove_integration() {
        let _ = powershell(
            "Remove-Item -Force -ErrorAction SilentlyContinue \
             (Join-Path ([Environment]::GetFolderPath('Programs')) 'Strata.lnk'), \
             (Join-Path ([Environment]::GetFolderPath('Desktop')) 'Strata.lnk')",
        );
        let hkcu = RegKey::predef(HKEY_CURRENT_USER);
        let _ = hkcu.delete_subkey_all(UNINSTALL_KEY);
    }

    pub fn schedule_self_delete(exe: &Path, dir: &Path) {
        let cmd = format!(
            "/C ping -n 3 127.0.0.1 >nul & del /f /q \"{}\" & rmdir \"{}\"",
            exe.display(),
            dir.display()
        );
        let _ = Command::new("cmd")
            .raw_arg(cmd)
            .creation_flags(CREATE_NO_WINDOW | DETACHED_PROCESS)
            .spawn();
    }
}

#[cfg(not(windows))]
mod platform {
    use super::*;

    pub fn game_exe(game_dir: &Path) -> PathBuf {
        game_dir.join("bin").join("Strata")
    }

    fn desktop_entry(launcher: &Path, game_dir: &Path) -> String {
        format!(
            "[Desktop Entry]\nType=Application\nName=Strata\nComment=Strata launcher\nExec=\"{}\"\nIcon={}\nTerminal=false\nCategories=Game;\n",
            launcher.display(),
            game_dir.join("lib").join("Strata.png").display()
        )
    }

    pub fn install_integration(launcher: &Path, game_dir: &Path, _install_dir: &Path, _version: &str, desktop: bool) {
        let entry = desktop_entry(launcher, game_dir);

        if let Some(apps) = dirs::data_dir().map(|d| d.join("applications")) {
            let _ = fs::create_dir_all(&apps);
            let _ = fs::write(apps.join("strata.desktop"), &entry);
        }
        if desktop {
            if let Some(path) = dirs::desktop_dir().map(|d| d.join("Strata.desktop")) {
                if fs::write(&path, &entry).is_ok() {
                    use std::os::unix::fs::PermissionsExt;
                    let _ = fs::set_permissions(&path, fs::Permissions::from_mode(0o755));
                }
            }
        }
    }

    pub fn remove_integration() {
        if let Some(p) = dirs::data_dir().map(|d| d.join("applications").join("strata.desktop")) {
            let _ = fs::remove_file(p);
        }
        if let Some(p) = dirs::desktop_dir().map(|d| d.join("Strata.desktop")) {
            let _ = fs::remove_file(p);
        }
    }

    pub fn schedule_self_delete(_exe: &Path, _dir: &Path) {
        // Linux can unlink a running executable, nothing to schedule
    }
}

// ── app ──────────────────────────────────────────────────────────────────────

const ACCENT: egui::Color32 = egui::Color32::from_rgb(0, 120, 215);

// 8x8 Bayer matrix for the ordered-dither fade
const BAYER8: [[u8; 8]; 8] = [
    [0, 32, 8, 40, 2, 34, 10, 42],
    [48, 16, 56, 24, 50, 18, 58, 26],
    [12, 44, 4, 36, 14, 46, 6, 38],
    [60, 28, 52, 20, 62, 30, 54, 22],
    [3, 35, 11, 43, 1, 33, 9, 41],
    [51, 19, 59, 27, 49, 17, 57, 25],
    [15, 47, 7, 39, 13, 45, 5, 37],
    [63, 31, 55, 23, 61, 29, 53, 21],
];

#[derive(Clone)]
enum Remote {
    Checking,
    Ready(Release),
    Failed(String),
}

#[derive(Clone, Copy, PartialEq)]
enum Screen {
    Main,
    ConfirmUninstall,
}

enum Action {
    Install,
    Play,
    AskUninstall,
    CancelUninstall,
    Uninstall,
    Retry,
    Close,
}

struct Busy {
    stage: String,
    done: u64,
    total: u64,
}

struct Fade {
    from: Vec<u8>, // RGBA of whatever was on screen when the fade started
    start: Instant,
}

struct App {
    // banners: raw RGBA so frames of the dithered fade can be composed on the CPU
    banner_rgba: [Vec<u8>; 3], // install, launch, uninstall
    banner_size: [usize; 2],
    banner_tex: egui::TextureHandle,
    displayed: Vec<u8>,
    target_index: usize,
    fade: Option<Fade>,

    tx: Sender<Msg>,
    rx: Receiver<Msg>,
    installed: Option<InstallInfo>,
    remote: Remote,
    screen: Screen,
    install_path: String,
    desktop_shortcut: bool,
    delete_data: bool,
    username: String,
    busy: Option<Busy>,
    message: Option<(String, bool)>, // text, is_error
}

fn banner_index(is_installed: bool, screen: Screen) -> usize {
    match (is_installed, screen) {
        (false, _) => 0,
        (true, Screen::Main) => 1,
        (true, Screen::ConfirmUninstall) => 2,
    }
}

fn decode_banner(bytes: &[u8]) -> ([usize; 2], Vec<u8>) {
    let img = image::load_from_memory(bytes).expect("banner png is valid").to_rgba8();
    ([img.width() as usize, img.height() as usize], img.into_raw())
}

/// Windows-like look: Segoe UI, flat square buttons, white background, blue hover/focus.
/// (egui draws its own widgets, so this is a close imitation, not the real Win32 controls.)
fn apply_style(ctx: &egui::Context) {
    use egui::{Color32, FontData, FontDefinitions, FontFamily, FontId, Rounding, Stroke, TextStyle};

    let mut fonts = FontDefinitions::default();
    if cfg!(windows) {
        let windir = std::env::var("WINDIR").unwrap_or_else(|_| r"C:\Windows".into());
        if let Ok(bytes) = fs::read(Path::new(&windir).join("Fonts").join("segoeui.ttf")) {
            fonts.font_data.insert("segoe".to_owned(), FontData::from_owned(bytes));
            if let Some(family) = fonts.families.get_mut(&FontFamily::Proportional) {
                family.insert(0, "segoe".to_owned());
            }
        }
    }
    ctx.set_fonts(fonts);

    let mut style = (*ctx.style()).clone();
    style.text_styles.insert(TextStyle::Body, FontId::proportional(13.0));
    style.text_styles.insert(TextStyle::Button, FontId::proportional(13.0));
    style.text_styles.insert(TextStyle::Small, FontId::proportional(11.0));
    style.text_styles.insert(TextStyle::Heading, FontId::proportional(18.0));
    style.spacing.item_spacing = egui::vec2(6.0, 6.0);
    style.spacing.button_padding = egui::vec2(8.0, 3.0);
    style.spacing.interact_size.y = 23.0;

    let mut v = egui::Visuals::light();
    v.window_fill = Color32::WHITE;
    v.panel_fill = Color32::WHITE;
    v.extreme_bg_color = Color32::WHITE; // text field background
    v.selection.bg_fill = ACCENT;
    v.selection.stroke = Stroke::new(1.0, ACCENT);

    v.window_rounding = Rounding::ZERO;
    v.menu_rounding = Rounding::ZERO;
    let square = Rounding::ZERO;
    let black = Color32::BLACK;
    let w = &mut v.widgets;

    w.noninteractive.rounding = square;
    w.noninteractive.bg_stroke = Stroke::new(1.0, Color32::from_rgb(213, 213, 213));
    w.noninteractive.fg_stroke = Stroke::new(1.0, black);

    // normal button: light gray with a gray border
    w.inactive.rounding = square;
    w.inactive.bg_fill = Color32::from_rgb(225, 225, 225);
    w.inactive.weak_bg_fill = Color32::from_rgb(225, 225, 225);
    w.inactive.bg_stroke = Stroke::new(1.0, Color32::from_rgb(173, 173, 173));
    w.inactive.fg_stroke = Stroke::new(1.0, black);

    // hover: pale blue with a blue border
    w.hovered.rounding = square;
    w.hovered.bg_fill = Color32::from_rgb(229, 241, 251);
    w.hovered.weak_bg_fill = Color32::from_rgb(229, 241, 251);
    w.hovered.bg_stroke = Stroke::new(1.0, ACCENT);
    w.hovered.fg_stroke = Stroke::new(1.0, black);

    // pressed
    w.active.rounding = square;
    w.active.bg_fill = Color32::from_rgb(204, 228, 247);
    w.active.weak_bg_fill = Color32::from_rgb(204, 228, 247);
    w.active.bg_stroke = Stroke::new(1.0, Color32::from_rgb(0, 84, 153));
    w.active.fg_stroke = Stroke::new(1.0, black);

    w.open.rounding = square;

    style.visuals = v;
    ctx.set_style(style);
}

fn button(text: &str) -> egui::Button<'static> {
    egui::Button::new(text.to_string()).min_size(egui::vec2(75.0, 23.0))
}

/// the "default" button of a page gets the blue outline, like in a Windows dialog
fn default_button(text: &str) -> egui::Button<'static> {
    button(text).stroke(egui::Stroke::new(1.0, ACCENT))
}

/// text field with a square 1px border (the built-in frame is rounded, so it is drawn here)
fn text_field(ui: &mut egui::Ui, id: &str, text: &mut String, hint: &str, max_chars: Option<usize>, enabled: bool) -> egui::Response {
    let id = egui::Id::new(id);
    let focused = ui.memory(|m| m.has_focus(id));
    let border = if focused { ACCENT } else { egui::Color32::from_rgb(122, 122, 122) };
    ui.add_enabled_ui(enabled, |ui| {
        egui::Frame::none()
            .fill(egui::Color32::WHITE)
            .stroke(egui::Stroke::new(1.0, border))
            .rounding(egui::Rounding::ZERO)
            .inner_margin(egui::Margin::symmetric(5.0, 3.0))
            .show(ui, |ui| {
                let mut edit = egui::TextEdit::singleline(text)
                    .id(id)
                    .frame(false)
                    .hint_text(hint)
                    .desired_width(f32::INFINITY);
                if let Some(max) = max_chars {
                    edit = edit.char_limit(max);
                }
                ui.add(edit)
            })
            .inner
    })
    .inner
}

/// square progress bar (egui's own is rounded)
fn progress_bar(ui: &mut egui::Ui, frac: f32, text: &str) {
    let (rect, _) = ui.allocate_exact_size(egui::vec2(ui.available_width(), 20.0), egui::Sense::hover());
    let painter = ui.painter();
    painter.rect_filled(rect, egui::Rounding::ZERO, egui::Color32::from_rgb(188, 188, 188));
    painter.rect_filled(rect.shrink(1.0), egui::Rounding::ZERO, egui::Color32::from_rgb(230, 230, 230));
    let inner = rect.shrink(1.0);
    let filled = egui::Rect::from_min_size(inner.min, egui::vec2(inner.width() * frac.clamp(0.0, 1.0), inner.height()));
    painter.rect_filled(filled, egui::Rounding::ZERO, egui::Color32::from_rgb(6, 176, 37));
    painter.text(
        rect.center(),
        egui::Align2::CENTER_CENTER,
        text,
        egui::FontId::proportional(13.0),
        egui::Color32::BLACK,
    );
}

fn window_icon() -> egui::IconData {
    let img = image::load_from_memory(include_bytes!("../assets/icon.png"))
        .expect("icon png is valid")
        .to_rgba8();
    egui::IconData { width: img.width(), height: img.height(), rgba: img.into_raw() }
}

fn heading(text: &str) -> egui::RichText {
    egui::RichText::new(text.to_string()).size(18.0)
}

impl App {
    fn new(cc: &eframe::CreationContext<'_>, open_uninstall: bool) -> Self {
        apply_style(&cc.egui_ctx);

        let (banner_size, install) = decode_banner(include_bytes!("../assets/banner_install.png"));
        let (_, launch) = decode_banner(include_bytes!("../assets/banner_launch.png"));
        let (_, uninstall) = decode_banner(include_bytes!("../assets/banner_uninstall.png"));
        let banner_rgba = [install, launch, uninstall];

        let (tx, rx) = channel();
        let installed = read_state();
        let screen = if open_uninstall && installed.is_some() { Screen::ConfirmUninstall } else { Screen::Main };

        let target_index = banner_index(installed.is_some(), screen);
        let displayed = banner_rgba[target_index].clone();
        let banner_tex = cc.egui_ctx.load_texture(
            "banner",
            egui::ColorImage::from_rgba_unmultiplied(banner_size, &displayed),
            egui::TextureOptions::NEAREST, // keeps the pixel art crisp
        );

        let mut app = App {
            banner_rgba,
            banner_size,
            banner_tex,
            displayed,
            target_index,
            fade: None,
            tx,
            rx,
            installed,
            remote: Remote::Checking,
            screen,
            install_path: default_install_dir().to_string_lossy().into_owned(),
            desktop_shortcut: true,
            delete_data: false,
            username: read_username().unwrap_or_default(), // empty shows the default as a hint
            busy: None,
            message: None,
        };
        app.check_remote(&cc.egui_ctx);
        app
    }

    /// starts a dithered fade when the wanted banner changes and advances it every frame
    fn update_banner(&mut self, ctx: &egui::Context, target: usize) {
        if target != self.target_index {
            // fade from whatever is visible right now, even if a fade is still running
            self.fade = Some(Fade { from: self.displayed.clone(), start: Instant::now() });
            self.target_index = target;
        }

        let mut finished = false;
        if let Some(fade) = &self.fade {
            let t = (fade.start.elapsed().as_secs_f32() / FADE_SECS).clamp(0.0, 1.0);
            let eased = t * t * (3.0 - 2.0 * t);
            let level = (eased * 64.0) as usize; // 0..=64; a pixel flips once the matrix value is below it

            let to = &self.banner_rgba[self.target_index];
            let w = self.banner_size[0];
            let mut out = Vec::with_capacity(to.len());
            for (i, (a, b)) in fade.from.chunks_exact(4).zip(to.chunks_exact(4)).enumerate() {
                let (x, y) = (i % w, i / w);
                out.extend_from_slice(if (BAYER8[y % 8][x % 8] as usize) < level { b } else { a });
            }
            self.displayed = out;
            self.banner_tex.set(
                egui::ColorImage::from_rgba_unmultiplied(self.banner_size, &self.displayed),
                egui::TextureOptions::NEAREST,
            );
            finished = t >= 1.0;
            ctx.request_repaint();
        }
        if finished {
            self.fade = None;
        }
    }

    fn reporter(&self, ctx: &egui::Context) -> Reporter {
        Reporter { tx: self.tx.clone(), ctx: ctx.clone() }
    }

    fn check_remote(&mut self, ctx: &egui::Context) {
        self.remote = Remote::Checking;
        let rep = self.reporter(ctx);
        thread::spawn(move || {
            let result = fetch_latest();
            rep.send(Msg::Remote(result));
        });
    }

    fn start_install(&mut self, ctx: &egui::Context) {
        let release = match &self.remote {
            Remote::Ready(r) => r.clone(),
            _ => return,
        };
        let (dir, desktop) = match &self.installed {
            // updating: same folder, and don't bring back a desktop shortcut the user may have removed
            Some(info) => (PathBuf::from(&info.path), false),
            None => (PathBuf::from(self.install_path.trim()), self.desktop_shortcut),
        };
        if dir.as_os_str().is_empty() {
            self.message = Some(("Pick an install location first.".into(), true));
            return;
        }

        self.message = None;
        self.busy = Some(Busy { stage: "Starting".into(), done: 0, total: 0 });
        let rep = self.reporter(ctx);
        thread::spawn(move || {
            let result = do_install(&rep, &release, &dir, desktop).map_err(|e| e.to_string());
            rep.send(Msg::InstallDone(result));
        });
    }

    fn start_uninstall(&mut self, ctx: &egui::Context) {
        let info = match &self.installed {
            Some(i) => i.clone(),
            None => return,
        };
        self.message = None;
        self.busy = Some(Busy { stage: "Uninstalling".into(), done: 0, total: 0 });
        let delete_data = self.delete_data;
        let rep = self.reporter(ctx);
        thread::spawn(move || {
            let result = do_uninstall(&info, delete_data).map_err(|e| e.to_string());
            rep.send(Msg::UninstallDone(result));
        });
    }

    /// writes the username box into the game's options.txt (only if it changed)
    fn commit_username(&mut self) {
        let clean = sanitize_username(&self.username);
        let current = read_username();
        if self.username.trim().is_empty() && current.is_none() {
            return; // never set, the game keeps using its default
        }
        if current.as_deref() != Some(clean.as_str()) {
            if let Err(e) = write_username(&clean) {
                self.message = Some((format!("Could not save the username: {e}"), true));
                return;
            }
        }
        self.username = clean; // show what was really saved
    }

    fn play(&mut self, ctx: &egui::Context) {
        let info = match &self.installed {
            Some(i) => i.clone(),
            None => return,
        };
        self.commit_username();
        let game_dir = PathBuf::from(&info.path).join(GAME_FOLDER);
        let exe = platform::game_exe(&game_dir);
        match Command::new(&exe).current_dir(&game_dir).spawn() {
            Ok(_) => ctx.send_viewport_cmd(egui::ViewportCommand::Close),
            Err(e) => self.message = Some((format!("Could not start the game: {e}"), true)),
        }
    }

    fn handle(&mut self, msg: Msg) {
        match msg {
            Msg::Remote(r) => {
                self.remote = match r {
                    Ok(rel) => Remote::Ready(rel),
                    Err(e) => Remote::Failed(e),
                }
            }
            Msg::Progress { stage, done, total } => {
                self.busy = Some(Busy { stage, done, total });
            }
            Msg::InstallDone(Ok(info)) => {
                self.message = Some((format!("Strata {} is installed.", info.version), false));
                self.installed = Some(info);
                self.busy = None;
                self.screen = Screen::Main;
                self.username = read_username().unwrap_or_default();
            }
            Msg::InstallDone(Err(e)) => {
                self.busy = None;
                self.message = Some((e, true));
            }
            Msg::UninstallDone(Ok(())) => {
                self.installed = None;
                self.busy = None;
                self.screen = Screen::Main;
                self.message = Some(("Strata was uninstalled. You can close this window.".into(), false));
            }
            Msg::UninstallDone(Err(e)) => {
                self.busy = None;
                self.message = Some((e, true));
            }
        }
    }

    // ── page content ──

    fn show_status(&self, ui: &mut egui::Ui) {
        if let Some(b) = &self.busy {
            if b.total > 0 {
                let frac = (b.done as f32 / b.total as f32).clamp(0.0, 1.0);
                progress_bar(ui, frac, &format!("{} {}%", b.stage, (frac * 100.0) as u32));
            } else {
                ui.horizontal(|ui| {
                    ui.spinner();
                    ui.label(format!("{}…", b.stage));
                });
            }
        } else if let Some((text, is_error)) = &self.message {
            let color = if *is_error { egui::Color32::from_rgb(190, 40, 40) } else { egui::Color32::from_rgb(30, 120, 50) };
            ui.label(egui::RichText::new(text.as_str()).color(color));
        }
    }

    fn content_install(&mut self, ui: &mut egui::Ui, action: &mut Option<Action>) {
        let busy = self.busy.is_some();

        ui.label(heading("Install Strata"));
        ui.label("Strata is downloaded from GitHub and installed for your user account. No admin rights needed.");
        ui.add_space(6.0);

        ui.label("Install location:");
        text_field(ui, "install_path", &mut self.install_path, "", None, !busy);
        ui.add_enabled(!busy, egui::Checkbox::new(&mut self.desktop_shortcut, "Create a desktop shortcut"));
        ui.add_space(4.0);

        match &self.remote {
            Remote::Checking => {
                ui.horizontal(|ui| {
                    ui.spinner();
                    ui.label("Checking the latest version…");
                });
            }
            Remote::Failed(e) => {
                ui.label(egui::RichText::new(e.as_str()).color(egui::Color32::from_rgb(190, 40, 40)));
                if ui.add(button("Try again")).clicked() {
                    *action = Some(Action::Retry);
                }
            }
            Remote::Ready(r) => {
                ui.label(format!("Latest version: {}", r.tag));
            }
        }

        self.show_status(ui);
    }

    fn content_main(&mut self, ui: &mut egui::Ui, action: &mut Option<Action>) {
        let busy = self.busy.is_some();
        let version = self.installed.as_ref().map(|i| i.version.clone()).unwrap_or_default();

        ui.label(heading("Strata"));
        ui.label(format!("Installed version: {version}"));
        ui.add_space(6.0);

        match &self.remote {
            Remote::Ready(r) if r.tag != version => {
                ui.label(format!("A new version is available: {}", r.tag));
            }
            Remote::Ready(_) => {
                ui.label("You are up to date.");
            }
            Remote::Checking => {
                ui.horizontal(|ui| {
                    ui.spinner();
                    ui.label("Checking for updates…");
                });
            }
            Remote::Failed(_) => {
                ui.label("Could not check for updates.");
                if ui.add(button("Try again")).clicked() {
                    *action = Some(Action::Retry);
                }
            }
        }

        ui.add_space(6.0);
        ui.label("Username:");
        let field = text_field(ui, "username", &mut self.username, DEFAULT_USERNAME, Some(MAX_USERNAME_LEN), !busy);
        if field.lost_focus() {
            // Enter or clicking away
            self.commit_username();
        }

        ui.add_space(4.0);
        self.show_status(ui);
    }

    fn content_uninstall(&mut self, ui: &mut egui::Ui) {
        let busy = self.busy.is_some();

        ui.label(heading("Uninstall Strata"));
        ui.label("This removes the game and the launcher from your computer.");
        ui.add_space(6.0);

        ui.add_enabled(
            !busy,
            egui::Checkbox::new(&mut self.delete_data, "Also delete my worlds and options"),
        );
        if let Some(dir) = game_data_dir() {
            ui.label(egui::RichText::new(dir.to_string_lossy().into_owned()).small().weak());
        }
        ui.add_space(4.0);
        self.show_status(ui);
    }

    // ── footer buttons (bottom right, like a Windows wizard) ──

    fn footer_install(&mut self, ui: &mut egui::Ui, action: &mut Option<Action>) {
        let busy = self.busy.is_some();
        let ready = matches!(self.remote, Remote::Ready(_));
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            if ui.add_enabled(!busy, button("Close")).clicked() {
                *action = Some(Action::Close);
            }
            if ui.add_enabled(ready && !busy, default_button("Install")).clicked() {
                *action = Some(Action::Install);
            }
        });
    }

    fn footer_main(&mut self, ui: &mut egui::Ui, action: &mut Option<Action>) {
        let busy = self.busy.is_some();
        let update_available = match (&self.remote, &self.installed) {
            (Remote::Ready(r), Some(i)) => r.tag != i.version,
            _ => false,
        };
        ui.horizontal(|ui| {
            if ui.add_enabled(!busy, button("Uninstall")).clicked() {
                *action = Some(Action::AskUninstall);
            }
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if ui.add_enabled(!busy, default_button("Play")).clicked() {
                    *action = Some(Action::Play);
                }
                if update_available && ui.add_enabled(!busy, button("Update")).clicked() {
                    *action = Some(Action::Install);
                }
            });
        });
    }

    fn footer_uninstall(&mut self, ui: &mut egui::Ui, action: &mut Option<Action>) {
        let busy = self.busy.is_some();
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            if ui.add_enabled(!busy, button("Cancel")).clicked() {
                *action = Some(Action::CancelUninstall);
            }
            if ui.add_enabled(!busy, default_button("Uninstall")).clicked() {
                *action = Some(Action::Uninstall);
            }
        });
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        while let Ok(msg) = self.rx.try_recv() {
            self.handle(msg);
        }

        let is_installed = self.installed.is_some();
        let screen = self.screen;
        self.update_banner(ctx, banner_index(is_installed, screen));
        let banner_id = self.banner_tex.id();
        let mut action: Option<Action> = None;

        // panels are added in this order: banner (left), footer (bottom of what is left), content (the rest)
        egui::SidePanel::left("banner")
            .exact_width(BANNER_W)
            .resizable(false)
            .show_separator_line(false)
            .frame(egui::Frame::none())
            .show(ctx, |ui| {
                ui.add(egui::Image::new((banner_id, egui::vec2(BANNER_W, BANNER_H))));
            });

        egui::TopBottomPanel::bottom("footer")
            .exact_height(FOOTER_H)
            .resizable(false)
            .show_separator_line(true)
            .frame(
                egui::Frame::none()
                    .fill(egui::Color32::from_rgb(240, 240, 240))
                    .inner_margin(egui::Margin::symmetric(12.0, 9.0)),
            )
            .show(ctx, |ui| match (is_installed, screen) {
                (false, _) => self.footer_install(ui, &mut action),
                (true, Screen::Main) => self.footer_main(ui, &mut action),
                (true, Screen::ConfirmUninstall) => self.footer_uninstall(ui, &mut action),
            });

        egui::CentralPanel::default()
            .frame(egui::Frame::none().fill(egui::Color32::WHITE).inner_margin(egui::Margin::symmetric(16.0, 14.0)))
            .show(ctx, |ui| match (is_installed, screen) {
                (false, _) => self.content_install(ui, &mut action),
                (true, Screen::Main) => self.content_main(ui, &mut action),
                (true, Screen::ConfirmUninstall) => self.content_uninstall(ui),
            });

        match action {
            Some(Action::Install) => self.start_install(ctx),
            Some(Action::Play) => self.play(ctx),
            Some(Action::AskUninstall) => {
                self.message = None;
                self.screen = Screen::ConfirmUninstall;
            }
            Some(Action::CancelUninstall) => {
                self.message = None;
                self.screen = Screen::Main;
            }
            Some(Action::Uninstall) => self.start_uninstall(ctx),
            Some(Action::Retry) => self.check_remote(ctx),
            Some(Action::Close) => ctx.send_viewport_cmd(egui::ViewportCommand::Close),
            None => {}
        }
    }
}

fn main() -> eframe::Result<()> {
    let open_uninstall = std::env::args().any(|a| a == "--uninstall");

    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("Strata")
            .with_icon(window_icon())
            .with_inner_size([BANNER_W + CONTENT_W, BANNER_H])
            .with_resizable(false)
            .with_maximize_button(false),
        centered: true,
        ..Default::default()
    };

    eframe::run_native(
        "Strata",
        options,
        Box::new(move |cc| Ok(Box::new(App::new(cc, open_uninstall)))),
    )
}