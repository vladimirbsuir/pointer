mod relay;
mod subscriptions;

use std::sync::{Arc, Mutex};
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use serde::Serialize;
use serde_json::{Value, json};
use tauri::async_runtime::JoinHandle;
use tauri::{AppHandle, Emitter, Manager};
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;
use uuid::Uuid;

use crate::auth::session::AuthSession;
use crate::config::{API_URL, CLIENT_VERSION};
use crate::device::DeviceInfo;
use crate::mcp::McpHub;
use crate::projects::ProjectMap;
use pointer_executor::{Emit, Executor, McpCaller, Workspace, Worktree};
use relay::Relay;
use subscriptions::{EventRoute, Subscriptions};

const PROTOCOL_VERSION: u32 = 1;
const MESSAGE_EVENT: &str = "realtime://message";
const STATUS_EVENT: &str = "realtime://status";
const TOKEN_REFRESH_INTERVAL: Duration = Duration::from_secs(30);
const READ_TIMEOUT: Duration = Duration::from_secs(60);
const MAX_BACKOFF: Duration = Duration::from_secs(30);
const FINAL_CLOSE_CODES: [u16; 3] = [4403, 4409, 4426];

#[derive(Clone, Copy, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RealtimeStatus {
    connected: bool,
    close_code: Option<u16>,
}

#[derive(Clone)]
pub struct Outgoing(mpsc::UnboundedSender<String>);

impl Outgoing {
    pub fn send(&self, message_type: &str, payload: Value) {
        let _ = self.try_send(message_type, payload);
    }

    fn try_send(&self, message_type: &str, payload: Value) -> Result<(), Value> {
        let envelope = json!({
            "type": message_type,
            "id": Uuid::now_v7().to_string(),
            "replyTo": null,
            "payload": payload,
        });
        self.0
            .send(envelope.to_string())
            .map_err(|_| envelope["payload"].clone())
    }
}

struct AbortOnDrop(JoinHandle<()>);

impl Drop for AbortOnDrop {
    fn drop(&mut self) {
        self.0.abort();
    }
}

pub struct Realtime {
    task: Mutex<Option<JoinHandle<()>>>,
    outgoing: Mutex<Option<Outgoing>>,
    subscriptions: Mutex<Subscriptions>,
    status: Mutex<RealtimeStatus>,
    relay: Arc<Relay>,
    executor: Arc<Executor>,
    undelivered: Mutex<Vec<Value>>,
}

impl Default for Realtime {
    fn default() -> Self {
        Self {
            task: Mutex::default(),
            outgoing: Mutex::default(),
            subscriptions: Mutex::default(),
            status: Mutex::default(),
            relay: Arc::default(),
            executor: Arc::new(Executor::default().with_api_url(API_URL)),
            undelivered: Mutex::default(),
        }
    }
}

impl Realtime {
    pub fn start(&self, app: &AppHandle) {
        let mut task = match self.task.lock() {
            Ok(task) => task,
            Err(_) => return,
        };
        if task
            .as_ref()
            .is_some_and(|handle| !handle.inner().is_finished())
        {
            return;
        }
        let app = app.clone();
        *task = Some(tauri::async_runtime::spawn(async move { run(app).await }));
    }

    pub fn stop(&self, app: &AppHandle) {
        if let Some(handle) = self.task.lock().ok().and_then(|mut task| task.take()) {
            handle.abort();
        }
        self.set_outgoing(None);
        self.set_status(app, RealtimeStatus::default());
        if let Ok(mut subscriptions) = self.subscriptions.lock() {
            subscriptions.clear();
        }
    }

    pub fn subscribe(&self, session_id: &str, after_seq: i64) {
        if let Ok(mut subscriptions) = self.subscriptions.lock() {
            subscriptions.subscribe(session_id, after_seq);
        }
        self.send(
            "session.subscribe",
            json!({ "sessionId": session_id, "afterSeq": after_seq }),
        );
    }

    pub fn unsubscribe(&self, session_id: &str) {
        if let Ok(mut subscriptions) = self.subscriptions.lock() {
            subscriptions.unsubscribe(session_id);
        }
        self.send("session.unsubscribe", json!({ "sessionId": session_id }));
    }

    pub fn status(&self) -> RealtimeStatus {
        self.status.lock().map(|status| *status).unwrap_or_default()
    }

    pub fn projects_changed(&self, project_ids: Vec<String>) {
        self.send(
            "device.projects_changed",
            json!({ "projects": project_ids }),
        );
    }

    pub fn report_tools(&self, tools: Value) {
        self.send("device.tools", json!({ "tools": tools }));
    }

    pub fn deliver(&self, message_type: &str, payload: Value) {
        let outgoing = self
            .outgoing
            .lock()
            .ok()
            .and_then(|outgoing| outgoing.clone());
        let unsent = match outgoing {
            Some(outgoing) => outgoing.try_send(message_type, payload).err(),
            None => Some(payload),
        };
        if let Some(payload) = unsent
            && message_type == "exec.result"
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

    fn send(&self, message_type: &str, payload: Value) {
        if let Some(outgoing) = self
            .outgoing
            .lock()
            .ok()
            .and_then(|outgoing| outgoing.clone())
        {
            outgoing.send(message_type, payload);
        }
    }

    fn set_outgoing(&self, outgoing: Option<Outgoing>) {
        if let Ok(mut current) = self.outgoing.lock() {
            *current = outgoing;
        }
    }

    fn set_status(&self, app: &AppHandle, status: RealtimeStatus) {
        if let Ok(mut current) = self.status.lock() {
            *current = status;
        }
        let _ = app.emit(STATUS_EVENT, status);
    }

    fn route_event(&self, payload: &Value) -> EventRoute {
        let (Some(session_id), Some(seq)) =
            (payload["sessionId"].as_str(), payload["seq"].as_i64())
        else {
            return EventRoute::Deliver;
        };
        self.subscriptions
            .lock()
            .map(|mut subscriptions| subscriptions.route(session_id, seq))
            .unwrap_or(EventRoute::Deliver)
    }

    fn confirm_subscription(&self, payload: &Value) {
        if let Some(session_id) = payload["sessionId"].as_str()
            && let Ok(mut subscriptions) = self.subscriptions.lock()
        {
            subscriptions.confirm(session_id);
        }
    }

    fn resubscribe_all(&self) -> Vec<(String, i64)> {
        self.subscriptions
            .lock()
            .map(|mut subscriptions| subscriptions.resubscribe_all())
            .unwrap_or_default()
    }
}

async fn run(app: AppHandle) {
    let mut backoff = Duration::from_secs(1);
    loop {
        let outcome = connect_once(&app).await;
        let realtime = app.state::<Realtime>();
        realtime.set_outgoing(None);
        let close_code = match outcome {
            Ok(Disconnect::Final { close_code }) => Some(close_code),
            Ok(Disconnect::Lost { was_stable: true }) => {
                backoff = Duration::from_secs(1);
                None
            }
            Ok(Disconnect::Lost { .. }) | Err(_) => None,
        };
        realtime.set_status(
            &app,
            RealtimeStatus {
                connected: false,
                close_code,
            },
        );
        if close_code.is_some() {
            break;
        }
        tokio::time::sleep(jitter(backoff)).await;
        backoff = (backoff * 2).min(MAX_BACKOFF);
    }
}

enum Disconnect {
    Final { close_code: u16 },
    Lost { was_stable: bool },
}

async fn connect_once(app: &AppHandle) -> Result<Disconnect, String> {
    let session = app.state::<AuthSession>();
    let token = session
        .access_token()
        .await
        .map_err(|error| error.message)?;
    let url = format!(
        "{}/ws",
        API_URL
            .replacen("https://", "wss://", 1)
            .replacen("http://", "ws://", 1)
    );
    let mut request = url
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
    let (sender, mut receiver) = mpsc::unbounded_channel::<String>();
    let outgoing = Outgoing(sender);
    let realtime = app.state::<Realtime>();
    let device = app.state::<DeviceInfo>();
    let projects = app.state::<ProjectMap>();
    let worktrees_dir = app
        .path()
        .app_data_dir()
        .map_err(|error| error.to_string())?
        .join("worktrees");

    outgoing.send(
        "hello",
        json!({
            "protocolVersion": PROTOCOL_VERSION,
            "deviceId": device.id,
            "deviceName": device.name,
            "clientVersion": CLIENT_VERSION,
            "platform": device.platform,
            "capabilities": ["viewer", "relay", "executor"],
            "interpreters": realtime.executor.interpreters().await.names(),
            "projects": projects.project_ids(),
            "undeliveredResults": realtime.take_undelivered(),
        }),
    );

    let _writer = AbortOnDrop(tauri::async_runtime::spawn(async move {
        while let Some(text) = receiver.recv().await {
            if sink.send(Message::Text(text.into())).await.is_err() {
                break;
            }
        }
    }));

    let refresher_outgoing = outgoing.clone();
    let refresher_app = app.clone();
    let _refresher = AbortOnDrop(tauri::async_runtime::spawn(async move {
        let mut sent = token;
        loop {
            tokio::time::sleep(TOKEN_REFRESH_INTERVAL).await;
            let session = refresher_app.state::<AuthSession>();
            if let Ok(token) = session.access_token().await
                && token != sent
            {
                refresher_outgoing.send("auth.refresh", json!({ "accessToken": token }));
                sent = token;
            }
        }
    }));

    let started = std::time::Instant::now();
    let mut result = Disconnect::Lost { was_stable: false };
    while let Ok(Some(message)) = tokio::time::timeout(READ_TIMEOUT, source.next()).await {
        let text = match message {
            Ok(Message::Text(text)) => text.to_string(),
            Ok(Message::Close(frame)) => {
                if let Some(close_code) = frame
                    .map(|frame| u16::from(frame.code))
                    .filter(|code| FINAL_CLOSE_CODES.contains(code))
                {
                    result = Disconnect::Final { close_code };
                }
                break;
            }
            Ok(_) => continue,
            Err(_) => break,
        };
        let Ok(envelope) = serde_json::from_str::<Value>(&text) else {
            continue;
        };
        match envelope["type"].as_str().unwrap_or_default() {
            "welcome" => {
                realtime.set_outgoing(Some(outgoing.clone()));
                realtime.set_status(
                    app,
                    RealtimeStatus {
                        connected: true,
                        close_code: None,
                    },
                );
                for payload in realtime.take_undelivered() {
                    outgoing.send("exec.result", payload);
                }
                outgoing.send(
                    "device.tools",
                    json!({ "tools": app.state::<Arc<McpHub>>().reported_tools() }),
                );
                for (session_id, after_seq) in realtime.resubscribe_all() {
                    outgoing.send(
                        "session.subscribe",
                        json!({ "sessionId": session_id, "afterSeq": after_seq }),
                    );
                }
            }
            "relay.request" => realtime
                .relay
                .handle_request(envelope["payload"].clone(), outgoing.clone()),
            "exec.request" => {
                let payload = envelope["payload"].clone();
                let workspace = payload["projectId"]
                    .as_str()
                    .and_then(|project_id| projects.path(project_id))
                    .map(|project| Workspace {
                        project,
                        worktree: payload["worktree"].as_str().and_then(|branch| {
                            Worktree::locate(
                                &worktrees_dir,
                                payload["sessionId"].as_str().unwrap_or_default(),
                                branch,
                            )
                        }),
                    });
                let emit_app = app.clone();
                let emit: Emit = Arc::new(move |message_type, payload| {
                    emit_app.state::<Realtime>().deliver(message_type, payload)
                });
                let mcp: Arc<dyn McpCaller> = app.state::<Arc<McpHub>>().inner().clone();
                realtime
                    .executor
                    .handle(payload, workspace, Some(mcp), emit);
            }
            "exec.cancel" => realtime
                .executor
                .cancel(envelope["payload"]["callId"].as_str().unwrap_or_default()),
            "relay.cancel" => realtime
                .relay
                .cancel(envelope["payload"]["relayId"].as_str().unwrap_or_default()),
            "session.event" => match realtime.route_event(&envelope["payload"]) {
                EventRoute::Deliver => {
                    let _ = app.emit(MESSAGE_EVENT, envelope);
                }
                EventRoute::Discard => {}
                EventRoute::Resubscribe { after_seq } => outgoing.send(
                    "session.subscribe",
                    json!({ "sessionId": envelope["payload"]["sessionId"], "afterSeq": after_seq }),
                ),
            },
            "session.subscribed" => {
                realtime.confirm_subscription(&envelope["payload"]);
                let _ = app.emit(MESSAGE_EVENT, envelope);
            }
            _ => {
                let _ = app.emit(MESSAGE_EVENT, envelope);
            }
        }
    }

    if let Disconnect::Lost { .. } = result {
        result = Disconnect::Lost {
            was_stable: started.elapsed() > Duration::from_secs(60),
        };
    }
    Ok(result)
}

fn jitter(duration: Duration) -> Duration {
    let factor = 0.8 + (rand::random::<u8>() as f64 / 255.0) * 0.4;
    duration.mul_f64(factor)
}
