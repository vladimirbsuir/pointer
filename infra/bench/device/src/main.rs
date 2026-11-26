use std::env;
use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use pointer_executor::{Emit, Executor, Workspace};
use serde_json::{Value, json};
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;

const TOKEN_REFRESH_INTERVAL: Duration = Duration::from_secs(300);
const RECONNECT_DELAY: Duration = Duration::from_secs(2);
const DEVICE_NAME: &str = "Bench device";

struct Config {
    api_url: String,
    email: String,
    password: String,
    device_id: String,
    project_id: String,
    workspace: PathBuf,
}

impl Config {
    fn from_env() -> Result<Self, String> {
        Ok(Self {
            api_url: required("POINTER_API_URL")?
                .trim_end_matches('/')
                .to_string(),
            email: required("POINTER_EMAIL")?,
            password: required("POINTER_PASSWORD")?,
            device_id: required("POINTER_DEVICE_ID")?,
            project_id: required("POINTER_PROJECT_ID")?,
            workspace: PathBuf::from(required("POINTER_WORKSPACE")?),
        })
    }

    fn gateway_url(&self) -> String {
        format!(
            "{}/ws",
            self.api_url
                .replacen("https://", "wss://", 1)
                .replacen("http://", "ws://", 1)
        )
    }
}

fn required(name: &str) -> Result<String, String> {
    env::var(name)
        .ok()
        .filter(|value| !value.is_empty())
        .ok_or_else(|| format!("{name} is not set"))
}

type Sender = Arc<dyn Fn(&str, Value) + Send + Sync>;

#[tokio::main]
async fn main() -> ExitCode {
    let config = match Config::from_env() {
        Ok(config) => Arc::new(config),
        Err(error) => {
            eprintln!("bench-device: {error}");
            return ExitCode::FAILURE;
        }
    };
    let http = reqwest::Client::new();
    let executor = Arc::new(Executor::default().with_api_url(config.api_url.clone()));
    loop {
        if let Err(error) = connect(&config, &http, &executor).await {
            eprintln!("bench-device: {error}");
        }
        tokio::time::sleep(RECONNECT_DELAY).await;
    }
}

async fn login(config: &Config, http: &reqwest::Client) -> Result<String, String> {
    let response = http
        .post(format!("{}/api/v1/auth/login", config.api_url))
        .json(&json!({
            "email": config.email,
            "password": config.password,
            "device": {
                "id": config.device_id,
                "name": DEVICE_NAME,
                "platform": { "os": std::env::consts::OS }
            }
        }))
        .send()
        .await
        .map_err(|error| error.to_string())?;
    let body: Value = response.json().await.map_err(|error| error.to_string())?;
    body["accessToken"]
        .as_str()
        .map(str::to_string)
        .ok_or_else(|| format!("sign-in failed: {body}"))
}

async fn connect(
    config: &Arc<Config>,
    http: &reqwest::Client,
    executor: &Arc<Executor>,
) -> Result<(), String> {
    let token = login(config, http).await?;
    let mut request = config
        .gateway_url()
        .into_client_request()
        .map_err(|error| error.to_string())?;
    request.headers_mut().insert(
        "Authorization",
        HeaderValue::from_str(&format!("Bearer {token}")).map_err(|error| error.to_string())?,
    );
    let (stream, _) = tokio_tungstenite::connect_async(request)
        .await
        .map_err(|error| error.to_string())?;
    let (mut sink, mut source) = stream.split();
    let (outgoing, mut receiver) = mpsc::unbounded_channel::<String>();
    let outgoing = Mutex::new(outgoing);
    let send: Sender = Arc::new(move |message_type, payload| {
        let text = json!({ "type": message_type, "payload": payload }).to_string();
        if let Ok(outgoing) = outgoing.lock() {
            let _ = outgoing.send(text);
        }
    });

    let interpreters: Vec<&str> = executor.interpreters().await.names();
    let shell = if cfg!(windows) { "powershell" } else { "sh" };
    let mut capabilities = vec!["executor"];
    if pointer_executor::desktop_supported() {
        capabilities.push("desktop");
    }
    send(
        "hello",
        json!({
            "protocolVersion": 1,
            "deviceId": config.device_id,
            "deviceName": DEVICE_NAME,
            "clientVersion": env!("CARGO_PKG_VERSION"),
            "platform": { "os": std::env::consts::OS, "arch": std::env::consts::ARCH, "shell": shell },
            "capabilities": capabilities,
            "interpreters": interpreters,
            "projects": [config.project_id],
            "undeliveredResults": [],
        }),
    );
    eprintln!("bench-device: connected");

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
        let send = send.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(TOKEN_REFRESH_INTERVAL).await;
                if let Ok(token) = login(&config, &http).await {
                    send("auth.refresh", json!({ "accessToken": token }));
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
            "exec.request" => {
                let send = send.clone();
                let emit: Emit = Arc::new(move |message_type, payload| send(message_type, payload));
                let workspace = Workspace {
                    project: config.workspace.clone(),
                    worktree: None,
                };
                executor.handle(envelope["payload"].clone(), Some(workspace), None, emit);
            }
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
