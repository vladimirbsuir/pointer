mod relay;

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use serde_json::{Value, json};
use tauri::async_runtime::JoinHandle;
use tauri::{AppHandle, Emitter, Manager};
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;
use tokio_tungstenite::tungstenite::protocol::frame::coding::CloseCode;
use uuid::Uuid;

use crate::auth::session::AuthSession;
use crate::config::{API_URL, CLIENT_VERSION};
use crate::device::DeviceInfo;
use crate::projects::ProjectMap;
use relay::Relay;

const PROTOCOL_VERSION: u32 = 1;
const MESSAGE_EVENT: &str = "realtime://message";
const STATUS_EVENT: &str = "realtime://status";
const TOKEN_REFRESH_INTERVAL: Duration = Duration::from_secs(300);
const MAX_BACKOFF: Duration = Duration::from_secs(30);
const REPLACED_CODE: u16 = 4409;

#[derive(Clone)]
pub struct Outgoing(mpsc::UnboundedSender<String>);

impl Outgoing {
    pub fn send(&self, message_type: &str, payload: Value) {
        let envelope = json!({
            "type": message_type,
            "id": Uuid::now_v7().to_string(),
            "replyTo": null,
            "payload": payload,
        });
        let _ = self.0.send(envelope.to_string());
    }
}

#[derive(Default)]
pub struct Realtime {
    task: Mutex<Option<JoinHandle<()>>>,
    outgoing: Mutex<Option<Outgoing>>,
    subscriptions: Mutex<HashMap<String, i64>>,
    connected: AtomicBool,
    relay: Arc<Relay>,
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
        self.set_connected(app, false);
        if let Ok(mut subscriptions) = self.subscriptions.lock() {
            subscriptions.clear();
        }
    }

    pub fn subscribe(&self, session_id: &str, after_seq: i64) {
        if let Ok(mut subscriptions) = self.subscriptions.lock() {
            subscriptions.insert(session_id.to_string(), after_seq);
        }
        self.send(
            "session.subscribe",
            json!({ "sessionId": session_id, "afterSeq": after_seq }),
        );
    }

    pub fn unsubscribe(&self, session_id: &str) {
        if let Ok(mut subscriptions) = self.subscriptions.lock() {
            subscriptions.remove(session_id);
        }
        self.send("session.unsubscribe", json!({ "sessionId": session_id }));
    }

    pub fn is_connected(&self) -> bool {
        self.connected.load(Ordering::SeqCst)
    }

    pub fn projects_changed(&self, project_ids: Vec<String>) {
        self.send(
            "device.projects_changed",
            json!({ "projects": project_ids }),
        );
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

    fn set_connected(&self, app: &AppHandle, connected: bool) {
        self.connected.store(connected, Ordering::SeqCst);
        let _ = app.emit(STATUS_EVENT, json!({ "connected": connected }));
    }

    fn track_event(&self, payload: &Value) {
        let (Some(session_id), Some(seq)) =
            (payload["sessionId"].as_str(), payload["seq"].as_i64())
        else {
            return;
        };
        if let Ok(mut subscriptions) = self.subscriptions.lock()
            && let Some(last) = subscriptions.get_mut(session_id)
            && seq > *last
        {
            *last = seq;
        }
    }

    fn subscriptions(&self) -> Vec<(String, i64)> {
        self.subscriptions
            .lock()
            .map(|subscriptions| {
                subscriptions
                    .iter()
                    .map(|(id, seq)| (id.clone(), *seq))
                    .collect()
            })
            .unwrap_or_default()
    }
}

async fn run(app: AppHandle) {
    let mut backoff = Duration::from_secs(1);
    loop {
        match connect_once(&app).await {
            Ok(Disconnect::Replaced) => break,
            Ok(Disconnect::Lost { was_stable }) if was_stable => backoff = Duration::from_secs(1),
            Ok(Disconnect::Lost { .. }) | Err(_) => {}
        }
        let realtime = app.state::<Realtime>();
        realtime.set_outgoing(None);
        realtime.set_connected(&app, false);
        tokio::time::sleep(jitter(backoff)).await;
        backoff = (backoff * 2).min(MAX_BACKOFF);
    }
}

enum Disconnect {
    Replaced,
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

    outgoing.send(
        "hello",
        json!({
            "protocolVersion": PROTOCOL_VERSION,
            "deviceId": device.id,
            "deviceName": device.name,
            "clientVersion": CLIENT_VERSION,
            "platform": device.platform,
            "capabilities": ["viewer", "relay"],
            "projects": projects.project_ids(),
            "undeliveredResults": [],
        }),
    );

    let writer = tauri::async_runtime::spawn(async move {
        while let Some(text) = receiver.recv().await {
            if sink.send(Message::Text(text.into())).await.is_err() {
                break;
            }
        }
    });

    let refresher_outgoing = outgoing.clone();
    let refresher_app = app.clone();
    let refresher = tauri::async_runtime::spawn(async move {
        loop {
            tokio::time::sleep(TOKEN_REFRESH_INTERVAL).await;
            let session = refresher_app.state::<AuthSession>();
            if let Ok(token) = session.access_token().await {
                refresher_outgoing.send("auth.refresh", json!({ "accessToken": token }));
            }
        }
    });

    let started = std::time::Instant::now();
    let mut result = Disconnect::Lost { was_stable: false };
    while let Some(message) = source.next().await {
        let text = match message {
            Ok(Message::Text(text)) => text.to_string(),
            Ok(Message::Close(frame)) => {
                if frame.is_some_and(|frame| frame.code == CloseCode::from(REPLACED_CODE)) {
                    result = Disconnect::Replaced;
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
                realtime.set_connected(app, true);
                for (session_id, after_seq) in realtime.subscriptions() {
                    outgoing.send(
                        "session.subscribe",
                        json!({ "sessionId": session_id, "afterSeq": after_seq }),
                    );
                }
            }
            "relay.request" => realtime
                .relay
                .handle_request(envelope["payload"].clone(), outgoing.clone()),
            "relay.cancel" => realtime
                .relay
                .cancel(envelope["payload"]["relayId"].as_str().unwrap_or_default()),
            "session.event" => {
                realtime.track_event(&envelope["payload"]);
                let _ = app.emit(MESSAGE_EVENT, envelope);
            }
            _ => {
                let _ = app.emit(MESSAGE_EVENT, envelope);
            }
        }
    }

    writer.abort();
    refresher.abort();
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
