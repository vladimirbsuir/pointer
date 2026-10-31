use serde::Serialize;
use serde_json::Value;
use tauri::{AppHandle, State};
use tauri_plugin_opener::OpenerExt;

use crate::auth::oauth::authorize_with_github;
use crate::auth::session::AuthSession;
use crate::config::API_URL;
use crate::error::{AppError, AppResult};

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppConfig {
    api_url: &'static str,
}

#[tauri::command]
pub fn app_config() -> AppConfig {
    AppConfig { api_url: API_URL }
}

#[tauri::command]
pub async fn auth_restore(session: State<'_, AuthSession>) -> AppResult<Option<Value>> {
    session.restore().await
}

#[tauri::command]
pub async fn auth_sign_in(
    session: State<'_, AuthSession>,
    email: String,
    password: String,
) -> AppResult<Value> {
    session.sign_in(&email, &password).await
}

#[tauri::command]
pub async fn auth_register(
    session: State<'_, AuthSession>,
    email: String,
    password: String,
    display_name: String,
    locale: String,
) -> AppResult<Value> {
    session
        .register(&email, &password, &display_name, &locale)
        .await
}

#[tauri::command]
pub async fn auth_sign_in_with_github(
    app: AppHandle,
    session: State<'_, AuthSession>,
    locale: String,
) -> AppResult<Value> {
    let code = authorize_with_github(session.api(), |url| open_in_browser(&app, url)).await?;
    session.sign_in_with_github(&code, &locale).await
}

#[tauri::command]
pub async fn auth_link_github(app: AppHandle, session: State<'_, AuthSession>) -> AppResult<Value> {
    let code = authorize_with_github(session.api(), |url| open_in_browser(&app, url)).await?;
    session.link_github(&code).await
}

#[tauri::command]
pub async fn auth_access_token(session: State<'_, AuthSession>) -> AppResult<String> {
    session.access_token().await
}

#[tauri::command]
pub async fn auth_sign_out(session: State<'_, AuthSession>) -> AppResult<()> {
    session.sign_out().await
}

fn open_in_browser(app: &AppHandle, url: &str) -> AppResult<()> {
    app.opener()
        .open_url(url, None::<&str>)
        .map_err(|error| AppError::new("browser_open_failed", error.to_string()))
}
