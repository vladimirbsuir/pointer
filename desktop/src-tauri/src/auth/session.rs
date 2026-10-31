use std::time::{SystemTime, UNIX_EPOCH};

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use serde::Deserialize;
use serde_json::Value;
use tokio::sync::Mutex;

use crate::auth::api::{ApiClient, AuthResponse, GitHubCode};
use crate::auth::token_store::TokenStore;
use crate::device::DeviceInfo;
use crate::error::{AppError, AppResult};

const REFRESH_MARGIN_SECONDS: u64 = 60;

struct AccessToken {
    value: String,
    expires_at: u64,
}

#[derive(Default)]
struct State {
    access_token: Option<AccessToken>,
    user: Option<Value>,
}

pub struct AuthSession {
    api: ApiClient,
    store: TokenStore,
    device: DeviceInfo,
    state: Mutex<State>,
}

impl AuthSession {
    pub fn new(api: ApiClient, store: TokenStore, device: DeviceInfo) -> Self {
        Self {
            api,
            store,
            device,
            state: Mutex::new(State::default()),
        }
    }

    pub fn api(&self) -> &ApiClient {
        &self.api
    }

    pub async fn restore(&self) -> AppResult<Option<Value>> {
        let mut state = self.state.lock().await;
        if state.user.is_some() {
            return Ok(state.user.clone());
        }
        let Some(refresh_token) = self.store.load()? else {
            return Ok(None);
        };
        match self.api.refresh(&refresh_token).await {
            Ok(response) => Ok(Some(self.accept(&mut state, response)?)),
            Err(error) if error.is_unauthorized() => {
                self.store.clear()?;
                Ok(None)
            }
            Err(error) => Err(error),
        }
    }

    pub async fn sign_in(&self, email: &str, password: &str) -> AppResult<Value> {
        let response = self.api.login(email, password, &self.device).await?;
        self.accept(&mut *self.state.lock().await, response)
    }

    pub async fn register(
        &self,
        email: &str,
        password: &str,
        display_name: &str,
        locale: &str,
    ) -> AppResult<Value> {
        let response = self
            .api
            .register(email, password, display_name, locale, &self.device)
            .await?;
        self.accept(&mut *self.state.lock().await, response)
    }

    pub async fn sign_in_with_github(&self, code: &GitHubCode, locale: &str) -> AppResult<Value> {
        let response = self.api.github_callback(code, locale, &self.device).await?;
        self.accept(&mut *self.state.lock().await, response)
    }

    pub async fn link_github(&self, code: &GitHubCode) -> AppResult<Value> {
        let access_token = self.access_token().await?;
        let user = self.api.github_link(code, &access_token).await?;
        self.state.lock().await.user = Some(user.clone());
        Ok(user)
    }

    pub async fn access_token(&self) -> AppResult<String> {
        let mut state = self.state.lock().await;
        if let Some(token) = &state.access_token
            && token.expires_at > now_seconds() + REFRESH_MARGIN_SECONDS
        {
            return Ok(token.value.clone());
        }
        let refresh_token = self
            .store
            .load()?
            .ok_or_else(|| AppError::from_status(401, "signed_out", "Not signed in"))?;
        match self.api.refresh(&refresh_token).await {
            Ok(response) => {
                self.accept(&mut state, response)?;
                Ok(state
                    .access_token
                    .as_ref()
                    .map(|token| token.value.clone())
                    .unwrap_or_default())
            }
            Err(error) => {
                if error.is_unauthorized() {
                    self.store.clear()?;
                    *state = State::default();
                }
                Err(error)
            }
        }
    }

    pub async fn sign_out(&self) -> AppResult<()> {
        let mut state = self.state.lock().await;
        if let Some(refresh_token) = self.store.load()? {
            let _ = self.api.logout(&refresh_token).await;
        }
        self.store.clear()?;
        *state = State::default();
        Ok(())
    }

    fn accept(&self, state: &mut State, response: AuthResponse) -> AppResult<Value> {
        self.store.save(&response.refresh_token)?;
        state.access_token = Some(AccessToken {
            expires_at: jwt_expiry(&response.access_token).unwrap_or(0),
            value: response.access_token,
        });
        state.user = Some(response.user.clone());
        Ok(response.user)
    }
}

#[derive(Deserialize)]
struct Claims {
    exp: u64,
}

fn jwt_expiry(token: &str) -> Option<u64> {
    let payload = token.split('.').nth(1)?;
    let bytes = URL_SAFE_NO_PAD.decode(payload).ok()?;
    serde_json::from_slice::<Claims>(&bytes)
        .ok()
        .map(|claims| claims.exp)
}

fn now_seconds() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_expiry_from_jwt_payload() {
        let payload = URL_SAFE_NO_PAD.encode(br#"{"sub":"user","exp":1893456000}"#);
        let token = format!("header.{payload}.signature");

        assert_eq!(jwt_expiry(&token), Some(1_893_456_000));
    }

    #[test]
    fn malformed_jwt_has_no_expiry() {
        assert_eq!(jwt_expiry("not-a-jwt"), None);
    }
}
