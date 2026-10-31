use std::collections::HashMap;
use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use sha2::{Digest, Sha256};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use url::Url;

use crate::auth::api::{ApiClient, GitHubCode};
use crate::error::{AppError, AppResult};

const CALLBACK_PATH: &str = "/callback";
const SIGN_IN_TIMEOUT: Duration = Duration::from_secs(300);
const MAX_REQUEST_BYTES: usize = 8192;

const SUCCESS_PAGE: &str = "<!doctype html><html><head><meta charset=\"utf-8\"><title>Pointer</title></head>\
<body style=\"font-family:system-ui;display:flex;align-items:center;justify-content:center;height:100vh;margin:0\">\
<p>Signed in to Pointer. You can close this tab and return to the app.</p></body></html>";

const FAILURE_PAGE: &str = "<!doctype html><html><head><meta charset=\"utf-8\"><title>Pointer</title></head>\
<body style=\"font-family:system-ui;display:flex;align-items:center;justify-content:center;height:100vh;margin:0\">\
<p>Sign-in was not completed. Return to Pointer and try again.</p></body></html>";

pub async fn authorize_with_github<F>(api: &ApiClient, open_browser: F) -> AppResult<GitHubCode>
where
    F: FnOnce(&str) -> AppResult<()>,
{
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let port = listener.local_addr()?.port();
    let redirect_uri = format!("http://127.0.0.1:{port}{CALLBACK_PATH}");
    let code_verifier = random_token();
    let code_challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(code_verifier.as_bytes()));
    let state = random_token();

    let authorize_url = api
        .github_authorize_url(&code_challenge, &state, &redirect_uri)
        .await?;
    open_browser(&authorize_url)?;

    let code = tokio::time::timeout(SIGN_IN_TIMEOUT, wait_for_code(&listener, &state))
        .await
        .map_err(|_| AppError::new("github_timeout", "GitHub sign-in timed out"))??;

    Ok(GitHubCode {
        code,
        code_verifier,
        redirect_uri,
    })
}

async fn wait_for_code(listener: &TcpListener, expected_state: &str) -> AppResult<String> {
    loop {
        let (mut stream, _) = listener.accept().await?;
        let Some(params) = read_callback_params(&mut stream).await? else {
            respond(&mut stream, "404 Not Found", "").await;
            continue;
        };
        if params.get("state").map(String::as_str) != Some(expected_state) {
            respond(&mut stream, "400 Bad Request", FAILURE_PAGE).await;
            return Err(AppError::new(
                "github_state_mismatch",
                "GitHub sign-in state does not match",
            ));
        }
        if let Some(code) = params.get("code") {
            respond(&mut stream, "200 OK", SUCCESS_PAGE).await;
            return Ok(code.clone());
        }
        respond(&mut stream, "200 OK", FAILURE_PAGE).await;
        return Err(AppError::new(
            "github_denied",
            params
                .get("error_description")
                .cloned()
                .unwrap_or_else(|| "GitHub sign-in was cancelled".to_string()),
        ));
    }
}

async fn read_callback_params(
    stream: &mut TcpStream,
) -> AppResult<Option<HashMap<String, String>>> {
    let mut buffer = vec![0; MAX_REQUEST_BYTES];
    let read = stream.read(&mut buffer).await?;
    let request = String::from_utf8_lossy(&buffer[..read]);
    let Some(target) = request
        .lines()
        .next()
        .and_then(|line| line.strip_prefix("GET "))
        .and_then(|rest| rest.split_whitespace().next())
    else {
        return Ok(None);
    };
    let Ok(url) = Url::parse(&format!("http://127.0.0.1{target}")) else {
        return Ok(None);
    };
    if url.path() != CALLBACK_PATH {
        return Ok(None);
    }
    Ok(Some(url.query_pairs().into_owned().collect()))
}

async fn respond(stream: &mut TcpStream, status: &str, body: &str) {
    let response = format!(
        "HTTP/1.1 {status}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    let _ = stream.write_all(response.as_bytes()).await;
    let _ = stream.shutdown().await;
}

fn random_token() -> String {
    URL_SAFE_NO_PAD.encode(rand::random::<[u8; 32]>())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn random_tokens_are_valid_pkce_verifiers() {
        let token = random_token();

        assert_eq!(token.len(), 43);
        assert!(
            token
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
        );
        assert_ne!(token, random_token());
    }

    #[tokio::test]
    async fn listener_returns_code_for_matching_state() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let client = tokio::spawn(async move {
            let mut stream = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
            stream
                .write_all(b"GET /callback?code=abc&state=xyz HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
                .await
                .unwrap();
            let mut response = String::new();
            stream.read_to_string(&mut response).await.unwrap();
            response
        });

        let code = wait_for_code(&listener, "xyz").await.unwrap();

        assert_eq!(code, "abc");
        assert!(client.await.unwrap().starts_with("HTTP/1.1 200 OK"));
    }

    #[tokio::test]
    async fn listener_rejects_mismatched_state() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        tokio::spawn(async move {
            let mut stream = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
            stream
                .write_all(b"GET /callback?code=abc&state=forged HTTP/1.1\r\n\r\n")
                .await
                .unwrap();
        });

        let error = wait_for_code(&listener, "xyz").await.unwrap_err();

        assert_eq!(error.code, "github_state_mismatch");
    }
}
