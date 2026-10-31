use std::collections::HashMap;

use serde::Serialize;
use serde_json::Value;
use tauri::{AppHandle, Manager, State};
use tauri_plugin_opener::OpenerExt;

use crate::auth::oauth::authorize_with_github;
use crate::auth::session::AuthSession;
use crate::config::API_URL;
use crate::error::{AppError, AppResult};
use crate::projects::ProjectMap;
use crate::realtime::Realtime;

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
pub async fn auth_restore(
    app: AppHandle,
    session: State<'_, AuthSession>,
) -> AppResult<Option<Value>> {
    let user = session.restore().await?;
    if user.is_some() {
        app.state::<Realtime>().start(&app);
    }
    Ok(user)
}

#[tauri::command]
pub async fn auth_sign_in(
    app: AppHandle,
    session: State<'_, AuthSession>,
    email: String,
    password: String,
) -> AppResult<Value> {
    signed_in(&app, session.sign_in(&email, &password).await)
}

#[tauri::command]
pub async fn auth_register(
    app: AppHandle,
    session: State<'_, AuthSession>,
    email: String,
    password: String,
    display_name: String,
    locale: String,
) -> AppResult<Value> {
    signed_in(
        &app,
        session
            .register(&email, &password, &display_name, &locale)
            .await,
    )
}

#[tauri::command]
pub async fn auth_sign_in_with_github(
    app: AppHandle,
    session: State<'_, AuthSession>,
    locale: String,
) -> AppResult<Value> {
    let code = authorize_with_github(session.api(), |url| open_in_browser(&app, url)).await?;
    signed_in(&app, session.sign_in_with_github(&code, &locale).await)
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
pub async fn auth_sign_out(app: AppHandle, session: State<'_, AuthSession>) -> AppResult<()> {
    app.state::<Realtime>().stop(&app);
    session.sign_out().await
}

#[tauri::command]
pub fn realtime_subscribe(realtime: State<'_, Realtime>, session_id: String, after_seq: i64) {
    realtime.subscribe(&session_id, after_seq);
}

#[tauri::command]
pub fn realtime_unsubscribe(realtime: State<'_, Realtime>, session_id: String) {
    realtime.unsubscribe(&session_id);
}

#[tauri::command]
pub fn realtime_status(realtime: State<'_, Realtime>) -> bool {
    realtime.is_connected()
}

#[tauri::command]
pub fn projects_local(projects: State<'_, ProjectMap>) -> HashMap<String, String> {
    projects.all()
}

#[tauri::command]
pub fn project_link(
    projects: State<'_, ProjectMap>,
    realtime: State<'_, Realtime>,
    project_id: String,
    path: String,
) -> AppResult<()> {
    projects.link(&project_id, &path)?;
    realtime.projects_changed(projects.project_ids());
    Ok(())
}

#[tauri::command]
pub fn project_unlink(
    projects: State<'_, ProjectMap>,
    realtime: State<'_, Realtime>,
    project_id: String,
) -> AppResult<()> {
    projects.unlink(&project_id)?;
    realtime.projects_changed(projects.project_ids());
    Ok(())
}

fn signed_in(app: &AppHandle, result: AppResult<Value>) -> AppResult<Value> {
    let user = result?;
    app.state::<Realtime>().start(app);
    Ok(user)
}

fn open_in_browser(app: &AppHandle, url: &str) -> AppResult<()> {
    app.opener()
        .open_url(url, None::<&str>)
        .map_err(|error| AppError::new("browser_open_failed", error.to_string()))
}
