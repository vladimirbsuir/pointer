use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use crate::device::DeviceInfo;
use crate::error::{AppError, AppResult};

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthResponse {
    pub access_token: String,
    pub refresh_token: String,
    pub user: Value,
}

#[derive(Deserialize)]
struct AuthorizeUrlResponse {
    url: String,
}

#[derive(Deserialize)]
struct Problem {
    code: Option<String>,
    detail: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GitHubCode {
    pub code: String,
    pub code_verifier: String,
    pub redirect_uri: String,
}

#[derive(Clone)]
pub struct ApiClient {
    http: reqwest::Client,
    base_url: String,
}

impl ApiClient {
    pub fn new(base_url: &str) -> Self {
        Self {
            http: reqwest::Client::new(),
            base_url: base_url.trim_end_matches('/').to_string(),
        }
    }

    pub async fn register(
        &self,
        email: &str,
        password: &str,
        display_name: &str,
        locale: &str,
        device: &DeviceInfo,
    ) -> AppResult<AuthResponse> {
        self.post(
            "/api/v1/auth/register",
            &json!({
                "email": email,
                "password": password,
                "displayName": display_name,
                "locale": locale,
                "device": device,
            }),
            None,
        )
        .await
    }

    pub async fn login(
        &self,
        email: &str,
        password: &str,
        device: &DeviceInfo,
    ) -> AppResult<AuthResponse> {
        self.post(
            "/api/v1/auth/login",
            &json!({ "email": email, "password": password, "device": device }),
            None,
        )
        .await
    }

    pub async fn refresh(&self, refresh_token: &str) -> AppResult<AuthResponse> {
        self.post(
            "/api/v1/auth/refresh",
            &json!({ "refreshToken": refresh_token }),
            None,
        )
        .await
    }

    pub async fn logout(&self, refresh_token: &str) -> AppResult<()> {
        let response = self
            .http
            .post(self.url("/api/v1/auth/logout"))
            .json(&json!({ "refreshToken": refresh_token }))
            .send()
            .await?;
        Self::check(response).await.map(|_| ())
    }

    pub async fn github_authorize_url(
        &self,
        code_challenge: &str,
        state: &str,
        redirect_uri: &str,
        repositories: bool,
    ) -> AppResult<String> {
        let response = self
            .http
            .get(self.url("/api/v1/auth/github/authorize-url"))
            .query(&[
                ("codeChallenge", code_challenge),
                ("state", state),
                ("redirectUri", redirect_uri),
                ("repositories", if repositories { "true" } else { "false" }),
            ])
            .send()
            .await?;
        let body: AuthorizeUrlResponse = Self::parse(response).await?;
        Ok(body.url)
    }

    pub async fn github_callback(
        &self,
        code: &GitHubCode,
        locale: &str,
        device: &DeviceInfo,
    ) -> AppResult<AuthResponse> {
        self.post(
            "/api/v1/auth/github/callback",
            &json!({
                "code": code.code,
                "codeVerifier": code.code_verifier,
                "redirectUri": code.redirect_uri,
                "locale": locale,
                "device": device,
            }),
            None,
        )
        .await
    }

    pub async fn github_link(&self, code: &GitHubCode, access_token: &str) -> AppResult<Value> {
        self.post("/api/v1/auth/github/link", code, Some(access_token))
            .await
    }

    async fn post<B: Serialize + ?Sized, T: DeserializeOwned>(
        &self,
        path: &str,
        body: &B,
        access_token: Option<&str>,
    ) -> AppResult<T> {
        let mut request = self.http.post(self.url(path)).json(body);
        if let Some(token) = access_token {
            request = request.bearer_auth(token);
        }
        Self::parse(request.send().await?).await
    }

    fn url(&self, path: &str) -> String {
        format!("{}{}", self.base_url, path)
    }

    async fn parse<T: DeserializeOwned>(response: reqwest::Response) -> AppResult<T> {
        let response = Self::check(response).await?;
        Ok(response.json::<T>().await?)
    }

    async fn check(response: reqwest::Response) -> AppResult<reqwest::Response> {
        let status = response.status();
        if status.is_success() {
            return Ok(response);
        }
        let problem = response.json::<Problem>().await.ok();
        let code = problem
            .as_ref()
            .and_then(|problem| problem.code.clone())
            .unwrap_or_else(|| format!("http_{}", status.as_u16()));
        let message = problem
            .and_then(|problem| problem.detail)
            .unwrap_or_else(|| status.to_string());
        Err(AppError::from_status(status.as_u16(), code, message))
    }
}
