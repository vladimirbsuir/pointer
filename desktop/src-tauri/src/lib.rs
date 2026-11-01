mod auth;
mod commands;
mod config;
mod device;
mod error;
mod executor;
mod mcp;
mod projects;
mod realtime;
mod terminal;

use tauri::Manager;

use crate::auth::api::ApiClient;
use crate::auth::session::AuthSession;
use crate::auth::token_store::TokenStore;
use crate::device::DeviceInfo;
use crate::mcp::McpHub;
use crate::projects::ProjectMap;
use crate::realtime::Realtime;
use crate::terminal::Terminals;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_notification::init())
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
            app.manage(Terminals::default());
            let mcp = McpHub::load(&data_dir);
            app.manage(mcp.clone());
            mcp.start_all(app.handle());
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
            commands::project_files,
            commands::project_read_file,
            commands::terminal_open,
            commands::terminal_write,
            commands::terminal_resize,
            commands::terminal_close,
            commands::mcp_servers,
            commands::mcp_save,
            commands::mcp_remove,
            commands::mcp_restart,
        ])
        .build(tauri::generate_context!())
        .expect("error while building Pointer")
        .run(|app, event| {
            if let tauri::RunEvent::Exit = event {
                app.state::<Terminals>().close_all();
                app.state::<std::sync::Arc<McpHub>>().shutdown_all();
            }
        });
}
