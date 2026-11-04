use std::time::Duration;

use reqwest::header::CONTENT_TYPE;
use serde_json::json;
use url::Url;

use super::{ExecError, ExecOutcome, Request};

const DEFAULT_MAX_CHARS: usize = 20_000;
const MAX_CHARS_LIMIT: usize = 25_000;
const MAX_BODY_BYTES: usize = 5 * 1024 * 1024;
const TEXT_WIDTH: usize = 120;
const USER_AGENT: &str = concat!("Pointer/", env!("CARGO_PKG_VERSION"));

pub async fn web_fetch(request: &Request) -> Result<ExecOutcome, ExecError> {
    let url = parse_url(request.required_str("url")?)?;
    let max_chars = request
        .u64_arg("maxChars")
        .map(|value| (value as usize).clamp(1, MAX_CHARS_LIMIT))
        .unwrap_or(DEFAULT_MAX_CHARS);
    let client = reqwest::Client::builder()
        .user_agent(USER_AGENT)
        .timeout(Duration::from_millis(request.timeout_ms))
        .build()
        .map_err(|error| ExecError::failed("fetch_failed", error.to_string()))?;
    let mut response = client
        .get(url.clone())
        .header(
            "Accept",
            "text/html,text/plain,application/json;q=0.9,*/*;q=0.5",
        )
        .send()
        .await
        .map_err(|error| ExecError::failed("fetch_failed", error.to_string()))?;
    let status = response.status();
    let final_url = response.url().to_string();
    let content_type = response
        .headers()
        .get(CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .unwrap_or_default()
        .to_ascii_lowercase();
    if !is_textual(&content_type) {
        return Err(ExecError::failed(
            "unsupported_content",
            format!("The URL returned {content_type}, which is not text"),
        ));
    }
    let mut body = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|error| ExecError::failed("fetch_failed", error.to_string()))?
    {
        body.extend_from_slice(&chunk);
        if body.len() > MAX_BODY_BYTES {
            body.truncate(MAX_BODY_BYTES);
            break;
        }
    }
    let text = if content_type.contains("html") || content_type.is_empty() && looks_like_html(&body)
    {
        html2text::from_read(body.as_slice(), TEXT_WIDTH)
            .map_err(|error| ExecError::failed("fetch_failed", error.to_string()))?
    } else {
        String::from_utf8_lossy(&body).into_owned()
    };
    let (content, truncated) = truncate(text.trim(), max_chars);
    Ok(ExecOutcome::result(json!({
        "url": final_url,
        "status": status.as_u16(),
        "contentType": content_type,
        "content": content,
        "truncated": truncated,
    })))
}

fn parse_url(raw: &str) -> Result<Url, ExecError> {
    let url = Url::parse(raw.trim())
        .map_err(|_| ExecError::rejected("invalid_arguments", "url must be an absolute URL"))?;
    if url.scheme() != "http" && url.scheme() != "https" {
        return Err(ExecError::rejected(
            "invalid_arguments",
            "Only http and https URLs can be fetched",
        ));
    }
    Ok(url)
}

fn is_textual(content_type: &str) -> bool {
    content_type.is_empty()
        || content_type.starts_with("text/")
        || content_type.contains("json")
        || content_type.contains("xml")
        || content_type.contains("javascript")
}

fn looks_like_html(body: &[u8]) -> bool {
    let start = String::from_utf8_lossy(&body[..body.len().min(512)]).to_ascii_lowercase();
    start.contains("<html") || start.contains("<!doctype html")
}

fn truncate(text: &str, max_chars: usize) -> (String, bool) {
    match text.char_indices().nth(max_chars) {
        Some((index, _)) => (text[..index].to_string(), true),
        None => (text.to_string(), false),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    async fn serve_once(content_type: &'static str, body: &'static str) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut buffer = [0u8; 2048];
            let _ = socket.read(&mut buffer).await;
            let response = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
            socket.write_all(response.as_bytes()).await.unwrap();
        });
        format!("http://{address}/page")
    }

    fn request(args: serde_json::Value) -> Request {
        Request {
            operation: "web_fetch".into(),
            args,
            dry_run: false,
            timeout_ms: 10_000,
        }
    }

    #[tokio::test]
    async fn html_is_converted_to_readable_text() {
        let url = serve_once(
            "text/html; charset=utf-8",
            "<html><head><script>var x = 1;</script></head><body><h1>Title</h1><p>Hello <b>world</b></p></body></html>",
        )
        .await;

        let outcome = web_fetch(&request(json!({ "url": url }))).await.unwrap();

        let content = outcome.result["content"].as_str().unwrap();
        assert!(content.contains("Title"));
        assert!(content.contains("Hello"));
        assert!(!content.contains("<p>"));
        assert_eq!(outcome.result["status"], 200);
    }

    #[tokio::test]
    async fn long_text_is_truncated() {
        let url = serve_once("text/plain", "abcdefghij").await;

        let outcome = web_fetch(&request(json!({ "url": url, "maxChars": 4 })))
            .await
            .unwrap();

        assert_eq!(outcome.result["content"], "abcd");
        assert_eq!(outcome.result["truncated"], true);
    }

    #[tokio::test]
    async fn binary_content_and_other_schemes_are_refused() {
        let url = serve_once("image/png", "png").await;

        let binary = web_fetch(&request(json!({ "url": url })))
            .await
            .unwrap_err();
        let file = web_fetch(&request(json!({ "url": "file:///C:/Windows/win.ini" })))
            .await
            .unwrap_err();

        assert_eq!(binary.code, "unsupported_content");
        assert_eq!(file.code, "invalid_arguments");
    }
}
