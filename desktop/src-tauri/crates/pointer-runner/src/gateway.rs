use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use futures_util::{SinkExt, StreamExt};
use pointer_executor::{Emit, Executor, Workspace};
use serde::Deserialize;
use serde_json::{Value, json};
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;

use crate::config::Config;
use crate::repo;

const PROTOCOL_VERSION: u32 = 1;
const CLIENT_VERSION: &str = env!("CARGO_PKG_VERSION");
const TOKEN_REFRESH_INTERVAL: Duration = Duration::from_secs(300);
const MIN_BACKOFF: Duration = Duration::from_secs(1);
const MAX_BACKOFF: Duration = Duration::from_secs(30);

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct TokenResponse {
    access_token: String,
}

enum TokenError {
    Revoked,
    Unavailable(String),
}

#[derive(Default)]
struct Outbox {
    sender: Mutex<Option<mpsc::UnboundedSender<String>>>,
    undelivered: Mutex<Vec<Value>>,
}

impl Outbox {
    fn send(&self, message_type: &str, payload: Value) -> bool {
        let text = json!({ "type": message_type, "payload": payload }).to_string();
        self.sender
            .lock()
            .ok()
            .and_then(|sender| sender.as_ref().map(|sender| sender.send(text).is_ok()))
            .unwrap_or(false)
    }

    fn deliver(&self, message_type: &str, payload: Value) {
        let is_result = message_type == "exec.result";
        if !self.send(message_type, payload.clone())
            && is_result
            && let Ok(mut undelivered) = self.undelivered.lock()
        {
            undelivered.push(payload);
        }
    }

    fn take_undelivered(&self) -> Vec<Value> {
        self.undelivered
            .lock()
            .map(|mut undelivered| std::mem::take(&mut *undelivered))
            .unwrap_or_default()
    }

    fn attach(&self, sender: Option<mpsc::UnboundedSender<String>>) {
        if let Ok(mut current) = self.sender.lock() {
            *current = sender;
        }
    }
}

pub async fn run(config: Config) {
    let config = Arc::new(config);
    let http = reqwest::Client::new();
    let executor = Arc::new(Executor::default());
    let outbox = Arc::new(Outbox::default());
    let mut backoff = MIN_BACKOFF;
    loop {
        let started = Instant::now();
        match connect_once(&config, &http, &executor, &outbox).await {
            Err(TokenError::Revoked) => {
                eprintln!("pointer-runner: the runner secret was revoked");
                return;
            }
            Err(TokenError::Unavailable(error)) => {
                eprintln!("pointer-runner: {error}");
            }
            Ok(()) => {}
        }
        outbox.attach(None);
        if started.elapsed() > Duration::from_secs(60) {
            backoff = MIN_BACKOFF;
        }
        tokio::time::sleep(backoff).await;
        backoff = (backoff * 2).min(MAX_BACKOFF);
    }
}

async fn fetch_token(config: &Config, http: &reqwest::Client) -> Result<String, TokenError> {
    let response = http
        .post(format!("{}/api/v1/runner/token", config.api_url))
        .json(&json!({ "deviceId": config.device_id, "secret": config.runner_secret }))
        .send()
        .await
        .map_err(|error| TokenError::Unavailable(error.to_string()))?;
    if response.status() == reqwest::StatusCode::UNAUTHORIZED {
        return Err(TokenError::Revoked);
    }
    if !response.status().is_success() {
        return Err(TokenError::Unavailable(format!(
            "token request failed with {}",
            response.status()
        )));
    }
    response
        .json::<TokenResponse>()
        .await
        .map(|body| body.access_token)
        .map_err(|error| TokenError::Unavailable(error.to_string()))
}

async fn connect_once(
    config: &Arc<Config>,
    http: &reqwest::Client,
    executor: &Arc<Executor>,
    outbox: &Arc<Outbox>,
) -> Result<(), TokenError> {
    let token = fetch_token(config, http).await?;
    let mut request = config
        .gateway_url()
        .into_client_request()
        .map_err(|error| TokenError::Unavailable(error.to_string()))?;
    request.headers_mut().insert(
        "Authorization",
        HeaderValue::from_str(&format!("Bearer {token}"))
            .map_err(|error| TokenError::Unavailable(error.to_string()))?,
    );
    let (stream, _) = tokio_tungstenite::connect_async(request)
        .await
        .map_err(|error| TokenError::Unavailable(error.to_string()))?;
    let (mut sink, mut source) = stream.split();
    let (sender, mut receiver) = mpsc::unbounded_channel::<String>();
    outbox.attach(Some(sender));

    let interpreters: Vec<&str> = executor.interpreters().await.names();
    outbox.send(
        "hello",
        json!({
            "protocolVersion": PROTOCOL_VERSION,
            "deviceId": config.device_id,
            "deviceName": "Pointer runner",
            "clientVersion": CLIENT_VERSION,
            "platform": { "os": std::env::consts::OS, "arch": std::env::consts::ARCH, "shell": "sh" },
            "capabilities": ["executor"],
            "interpreters": interpreters,
            "projects": [config.project_id],
            "undeliveredResults": outbox.take_undelivered(),
        }),
    );

    let writer = tokio::spawn(async move {
        while let Some(text) = receiver.recv().await {
            if sink.send(Message::Text(text.into())).await.is_err() {
                break;
            }
        }
    });

    let refresher = {
        let config = config.clone();
        let http = http.clone();
        let outbox = outbox.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(TOKEN_REFRESH_INTERVAL).await;
                if let Ok(token) = fetch_token(&config, &http).await {
                    outbox.send("auth.refresh", json!({ "accessToken": token }));
                }
            }
        })
    };

    while let Some(message) = source.next().await {
        let text = match message {
            Ok(Message::Text(text)) => text.to_string(),
            Ok(Message::Close(_)) | Err(_) => break,
            Ok(_) => continue,
        };
        let Ok(envelope) = serde_json::from_str::<Value>(&text) else {
            continue;
        };
        match envelope["type"].as_str().unwrap_or_default() {
            "exec.request" => handle_request(config, executor, outbox, envelope["payload"].clone()),
            "exec.cancel" => {
                executor.cancel(envelope["payload"]["callId"].as_str().unwrap_or_default())
            }
            _ => {}
        }
    }

    writer.abort();
    refresher.abort();
    Ok(())
}

fn handle_request(config: &Config, executor: &Arc<Executor>, outbox: &Arc<Outbox>, payload: Value) {
    let operation = payload["operation"]
        .as_str()
        .unwrap_or_default()
        .to_string();
    if operation == repo::PREPARE || operation == repo::PUBLISH {
        let workspace = config.workspace.clone();
        let outbox = outbox.clone();
        tokio::spawn(async move {
            let started = Instant::now();
            let call_id = payload["callId"].as_str().unwrap_or_default().to_string();
            let arguments = payload["arguments"].clone();
            let outcome = tokio::task::spawn_blocking(move || {
                repo::handle(&workspace, &operation, &arguments)
            })
            .await
            .unwrap_or_else(|error| {
                Err(repo::RepoError {
                    code: "internal_error",
                    message: error.to_string(),
                })
            });
            outbox.deliver("exec.result", repo_result(&call_id, outcome, started));
        });
        return;
    }
    let emit_outbox = outbox.clone();
    let emit: Emit =
        Arc::new(move |message_type, payload| emit_outbox.deliver(message_type, payload));
    let workspace = Workspace {
        project: config.workspace.clone(),
        worktree: None,
    };
    executor.handle(payload, Some(workspace), None, emit);
}

fn repo_result(call_id: &str, outcome: Result<Value, repo::RepoError>, started: Instant) -> Value {
    let duration = started.elapsed().as_millis() as u64;
    match outcome {
        Ok(result) => json!({
            "callId": call_id,
            "status": "succeeded",
            "result": result,
            "error": null,
            "fileChanges": [],
            "durationMs": duration,
        }),
        Err(error) => json!({
            "callId": call_id,
            "status": "failed",
            "result": {},
            "error": { "code": error.code, "message": error.message },
            "fileChanges": [],
            "durationMs": duration,
        }),
    }
}
