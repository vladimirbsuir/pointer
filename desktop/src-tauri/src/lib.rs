mod auth;
mod commands;
mod config;
mod device;
mod error;
mod executor;
mod projects;
mod realtime;

use tauri::Manager;

use crate::auth::api::ApiClient;
use crate::auth::session::AuthSession;
use crate::auth::token_store::TokenStore;
use crate::device::DeviceInfo;
use crate::projects::ProjectMap;
use crate::realtime::Realtime;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let data_dir = app.path().app_data_dir()?;
            let device = DeviceInfo::load_or_create(&data_dir)?;
            let session = AuthSession::new(
                ApiClient::new(config::API_URL),
                TokenStore::new()?,
                device.clone(),
            );
            app.manage(session);
            app.manage(device);
            app.manage(ProjectMap::load(&data_dir)?);
            app.manage(Realtime::default());
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::app_config,
            commands::auth_restore,
            commands::auth_sign_in,
            commands::auth_register,
            commands::auth_sign_in_with_github,
            commands::auth_link_github,
            commands::auth_access_token,
            commands::auth_sign_out,
            commands::realtime_subscribe,
            commands::realtime_unsubscribe,
            commands::realtime_status,
            commands::projects_local,
            commands::project_link,
            commands::project_unlink,
        ])
        .run(tauri::generate_context!())
        .expect("error while running Pointer");
}
