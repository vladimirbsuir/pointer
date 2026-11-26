use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};

struct Settings {
    state_path: PathBuf,
    lock_at: Option<u32>,
    announced: AtomicBool,
}

#[tauri::command]
fn report(settings: tauri::State<'_, Settings>, snapshot: serde_json::Value) -> Result<(), String> {
    let temporary = settings.state_path.with_extension("tmp");
    fs::write(&temporary, snapshot.to_string()).map_err(|error| error.to_string())?;
    fs::rename(&temporary, &settings.state_path).map_err(|error| error.to_string())?;
    if !settings.announced.swap(true, Ordering::SeqCst) {
        println!("tauri-counter ready");
    }
    Ok(())
}

#[tauri::command]
fn lock_at(settings: tauri::State<'_, Settings>) -> Option<u32> {
    settings.lock_at
}

fn main() {
    let arguments: Vec<String> = std::env::args().skip(1).collect();
    let state_path = arguments
        .iter()
        .find(|argument| !argument.starts_with("--"))
        .map_or_else(|| PathBuf::from("/tmp/tauri-counter.json"), PathBuf::from);
    let threshold = arguments
        .iter()
        .find_map(|argument| argument.strip_prefix("--lock-at="))
        .and_then(|value| value.parse().ok());
    tauri::Builder::default()
        .manage(Settings {
            state_path,
            lock_at: threshold,
            announced: AtomicBool::new(false),
        })
        .invoke_handler(tauri::generate_handler![report, lock_at])
        .run(tauri::generate_context!())
        .expect("tauri-counter failed");
}
