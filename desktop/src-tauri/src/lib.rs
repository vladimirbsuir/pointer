mod auth;
mod commands;
mod config;
mod device;
mod error;

use tauri::Manager;

use crate::auth::api::ApiClient;
use crate::auth::session::AuthSession;
use crate::auth::token_store::TokenStore;
use crate::device::DeviceInfo;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            let device = DeviceInfo::load_or_create(&app.path().app_data_dir()?)?;
            let session =
                AuthSession::new(ApiClient::new(config::API_URL), TokenStore::new()?, device);
            app.manage(session);
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
        ])
        .run(tauri::generate_context!())
        .expect("error while running Pointer");
}
