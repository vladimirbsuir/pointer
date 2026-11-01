use std::collections::{BTreeMap, HashMap, VecDeque};
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde::Serialize;
use serde_json::{Value, json};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{ChildStdin, Command};
use tokio::sync::{mpsc, oneshot};

use super::config::TransportConfig;
use crate::config::CLIENT_VERSION;

const PROTOCOL_VERSION: &str = "2025-06-18";
const INITIALIZE_TIMEOUT: Duration = Duration::from_secs(90);
const LIST_TIMEOUT: Duration = Duration::from_secs(30);
const STDERR_TAIL_LINES: usize = 20;
const MAX_TOOL_PAGES: usize = 20;
#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolInfo {
    pub name: String,
    pub description: String,
    pub input_schema: Value,
    pub read_only: bool,
}

pub enum ClientEvent {
    Exited(String),
    ToolsChanged,
}

type Pending = Arc<Mutex<HashMap<u64, oneshot::Sender<Result<Value, String>>>>>;

pub struct McpClient {
    transport: Transport,
    next_id: AtomicU64,
}

enum Transport {
    Stdio(StdioTransport),
    Http(HttpTransport),
}

impl McpClient {
    pub async fn connect(
        config: &TransportConfig,
        events: mpsc::UnboundedSender<ClientEvent>,
    ) -> Result<Self, String> {
        let transport = match config {
            TransportConfig::Stdio { command, args, env } => {
                Transport::Stdio(StdioTransport::spawn(command, args, env, events)?)
            }
            TransportConfig::Http { url, headers } => {
                Transport::Http(HttpTransport::new(url, headers)?)
            }
        };
        let client = Self {
            transport,
            next_id: AtomicU64::new(1),
        };
        client
            .request(
                "initialize",
                json!({
                    "protocolVersion": PROTOCOL_VERSION,
                    "capabilities": {},
                    "clientInfo": { "name": "pointer", "version": CLIENT_VERSION },
                }),
                INITIALIZE_TIMEOUT,
            )
            .await
            .map_err(|error| client.describe_failure(error))?;
        client
            .notify("notifications/initialized", json!({}))
            .await?;
        Ok(client)
    }

    pub async fn list_tools(&self) -> Result<Vec<ToolInfo>, String> {
        let mut tools = Vec::new();
        let mut cursor: Option<String> = None;
        for _ in 0..MAX_TOOL_PAGES {
            let params = match &cursor {
                Some(cursor) => json!({ "cursor": cursor }),
                None => json!({}),
            };
            let page = self.request("tools/list", params, LIST_TIMEOUT).await?;
            tools.extend(
                page["tools"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .filter_map(tool_info),
            );
            cursor = page["nextCursor"].as_str().map(str::to_string);
            if cursor.is_none() {
                break;
            }
        }
        Ok(tools)
    }

    pub async fn call_tool(
        &self,
        name: &str,
        arguments: Value,
        timeout: Duration,
    ) -> Result<Value, String> {
        self.request(
            "tools/call",
            json!({ "name": name, "arguments": arguments }),
            timeout,
        )
        .await
    }

    pub async fn shutdown(&self) {
        if let Transport::Stdio(stdio) = &self.transport {
            stdio.kill().await;
        }
    }

    async fn request(
        &self,
        method: &str,
        params: Value,
        timeout: Duration,
    ) -> Result<Value, String> {
        let id = self.next_id.fetch_add(1, Ordering::SeqCst);
        let message = json!({ "jsonrpc": "2.0", "id": id, "method": method, "params": params });
        match &self.transport {
            Transport::Stdio(stdio) => tokio::time::timeout(timeout, stdio.request(id, message))
                .await
                .map_err(|_| format!("{method} timed out"))?,
            Transport::Http(http) => tokio::time::timeout(timeout, http.request(id, message))
                .await
                .map_err(|_| format!("{method} timed out"))?,
        }
    }

    async fn notify(&self, method: &str, params: Value) -> Result<(), String> {
        let message = json!({ "jsonrpc": "2.0", "method": method, "params": params });
        match &self.transport {
            Transport::Stdio(stdio) => stdio.write(&message).await,
            Transport::Http(http) => http.notify(message).await,
        }
    }

    fn describe_failure(&self, error: String) -> String {
        match &self.transport {
            Transport::Stdio(stdio) => {
                let tail = stdio.stderr_tail();
                if tail.is_empty() {
                    error
                } else {
                    format!("{error}\n{tail}")
                }
            }
            Transport::Http(_) => error,
        }
    }
}

fn tool_info(tool: &Value) -> Option<ToolInfo> {
    Some(ToolInfo {
        name: tool["name"].as_str()?.to_string(),
        description: tool["description"].as_str().unwrap_or_default().to_string(),
        input_schema: if tool["inputSchema"].is_object() {
            tool["inputSchema"].clone()
        } else {
            json!({ "type": "object", "properties": {} })
        },
        read_only: tool["annotations"]["readOnlyHint"]
            .as_bool()
            .unwrap_or(false),
    })
}

fn response_result(message: &Value) -> Result<Value, String> {
    if let Some(error) = message.get("error") {
        return Err(error["message"]
            .as_str()
            .unwrap_or("The MCP server returned an error")
            .to_string());
    }
    Ok(message.get("result").cloned().unwrap_or(Value::Null))
}

struct StdioTransport {
    stdin: Arc<tokio::sync::Mutex<ChildStdin>>,
    pending: Pending,
    pid: Option<u32>,
    stderr: Arc<Mutex<VecDeque<String>>>,
}

impl StdioTransport {
    fn spawn(
        command: &str,
        args: &[String],
        env: &BTreeMap<String, String>,
        events: mpsc::UnboundedSender<ClientEvent>,
    ) -> Result<Self, String> {
        let mut process = Command::new(resolve_program(command));
        process
            .args(args)
            .envs(env)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        #[cfg(windows)]
        process.creation_flags(CREATE_NO_WINDOW);
        let mut child = process
            .spawn()
            .map_err(|error| format!("Could not start {command}: {error}"))?;
        let stdin = Arc::new(tokio::sync::Mutex::new(
            child.stdin.take().ok_or("The server has no stdin")?,
        ));
        let stdout = child.stdout.take().ok_or("The server has no stdout")?;
        let stderr_pipe = child.stderr.take().ok_or("The server has no stderr")?;
        let pending: Pending = Arc::default();
        let stderr: Arc<Mutex<VecDeque<String>>> = Arc::default();
        let pid = child.id();

        let tail = stderr.clone();
        tokio::spawn(async move {
            let mut lines = BufReader::new(stderr_pipe).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                if let Ok(mut tail) = tail.lock() {
                    if tail.len() == STDERR_TAIL_LINES {
                        tail.pop_front();
                    }
                    tail.push_back(line);
                }
            }
        });

        let reader_pending = pending.clone();
        let reader_stdin = stdin.clone();
        let reader_tail = stderr.clone();
        tokio::spawn(async move {
            let mut lines = BufReader::new(stdout).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                let Ok(message) = serde_json::from_str::<Value>(&line) else {
                    continue;
                };
                handle_incoming(&message, &reader_pending, &reader_stdin, &events).await;
            }
            let _ = child.wait().await;
            if let Ok(mut pending) = reader_pending.lock() {
                for (_, sender) in pending.drain() {
                    let _ = sender.send(Err("The MCP server exited".into()));
                }
            }
            let tail = reader_tail
                .lock()
                .map(|tail| tail.iter().cloned().collect::<Vec<_>>().join("\n"))
                .unwrap_or_default();
            let _ = events.send(ClientEvent::Exited(tail));
        });

        Ok(Self {
            stdin,
            pending,
            pid,
            stderr,
        })
    }

    async fn request(&self, id: u64, message: Value) -> Result<Value, String> {
        let (sender, receiver) = oneshot::channel();
        if let Ok(mut pending) = self.pending.lock() {
            pending.insert(id, sender);
        }
        if let Err(error) = self.write(&message).await {
            if let Ok(mut pending) = self.pending.lock() {
                pending.remove(&id);
            }
            return Err(error);
        }
        receiver
            .await
            .unwrap_or_else(|_| Err("The MCP server exited".into()))
    }

    async fn write(&self, message: &Value) -> Result<(), String> {
        write_line(&self.stdin, message).await
    }

    fn stderr_tail(&self) -> String {
        self.stderr
            .lock()
            .map(|tail| tail.iter().cloned().collect::<Vec<_>>().join("\n"))
            .unwrap_or_default()
    }

    async fn kill(&self) {
        if let Some(pid) = self.pid {
            kill_tree(pid).await;
        }
    }
}

async fn handle_incoming(
    message: &Value,
    pending: &Pending,
    stdin: &tokio::sync::Mutex<ChildStdin>,
    events: &mpsc::UnboundedSender<ClientEvent>,
) {
    let method = message["method"].as_str();
    match (message.get("id").and_then(Value::as_u64), method) {
        (Some(id), None) => {
            let sender = pending
                .lock()
                .ok()
                .and_then(|mut pending| pending.remove(&id));
            if let Some(sender) = sender {
                let _ = sender.send(response_result(message));
            }
        }
        (_, Some("notifications/tools/list_changed")) => {
            let _ = events.send(ClientEvent::ToolsChanged);
        }
        (None, _) => {}
        (Some(_), Some(method)) => {
            let reply = if method == "ping" {
                json!({ "jsonrpc": "2.0", "id": message["id"], "result": {} })
            } else {
                json!({
                    "jsonrpc": "2.0",
                    "id": message["id"],
                    "error": { "code": -32601, "message": format!("{method} is not supported") },
                })
            };
            let _ = write_line(stdin, &reply).await;
        }
    }
}

async fn write_line(stdin: &tokio::sync::Mutex<ChildStdin>, message: &Value) -> Result<(), String> {
    let mut line = message.to_string();
    line.push('\n');
    let mut stdin = stdin.lock().await;
    stdin
        .write_all(line.as_bytes())
        .await
        .map_err(|error| format!("Could not write to the MCP server: {error}"))?;
    stdin.flush().await.map_err(|error| error.to_string())
}

#[cfg(windows)]
async fn kill_tree(pid: u32) {
    let mut taskkill = Command::new("taskkill");
    taskkill
        .args(["/PID", &pid.to_string(), "/T", "/F"])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .creation_flags(CREATE_NO_WINDOW);
    let _ = taskkill.status().await;
}

#[cfg(not(windows))]
async fn kill_tree(pid: u32) {
    let _ = Command::new("kill")
        .args(["-9", &pid.to_string()])
        .status()
        .await;
}

fn resolve_program(command: &str) -> PathBuf {
    let path = Path::new(command);
    if path.extension().is_some() || path.components().count() > 1 {
        return path.to_path_buf();
    }
    let extensions: Vec<String> = std::env::var("PATHEXT")
        .unwrap_or_else(|_| ".EXE;.CMD;.BAT;.COM".into())
        .split(';')
        .filter(|extension| !extension.is_empty())
        .map(str::to_lowercase)
        .collect();
    let directories = std::env::var_os("PATH")
        .map(|paths| std::env::split_paths(&paths).collect::<Vec<_>>())
        .unwrap_or_default();
    directories
        .iter()
        .flat_map(|directory| {
            extensions
                .iter()
                .map(move |extension| directory.join(format!("{command}{extension}")))
        })
        .find(|candidate| candidate.is_file())
        .unwrap_or_else(|| path.to_path_buf())
}

struct HttpTransport {
    client: reqwest::Client,
    url: String,
    headers: BTreeMap<String, String>,
    session: Mutex<Option<String>>,
}

impl HttpTransport {
    fn new(url: &str, headers: &BTreeMap<String, String>) -> Result<Self, String> {
        let client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(15))
            .build()
            .map_err(|error| error.to_string())?;
        Ok(Self {
            client,
            url: url.to_string(),
            headers: headers.clone(),
            session: Mutex::new(None),
        })
    }

    async fn request(&self, id: u64, message: Value) -> Result<Value, String> {
        let response = self.post(&message).await?;
        let content_type = response
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|value| value.to_str().ok())
            .unwrap_or_default()
            .to_string();
        let body = response.text().await.map_err(|error| error.to_string())?;
        let messages = if content_type.starts_with("text/event-stream") {
            sse_messages(&body)
        } else {
            match serde_json::from_str::<Value>(&body).map_err(|error| error.to_string())? {
                Value::Array(batch) => batch,
                single => vec![single],
            }
        };
        messages
            .iter()
            .find(|candidate| candidate.get("id").and_then(Value::as_u64) == Some(id))
            .map(response_result)
            .unwrap_or_else(|| Err("The MCP server sent no response".into()))
    }

    async fn notify(&self, message: Value) -> Result<(), String> {
        self.post(&message).await.map(|_| ())
    }

    async fn post(&self, message: &Value) -> Result<reqwest::Response, String> {
        let mut request = self
            .client
            .post(&self.url)
            .header(
                reqwest::header::ACCEPT,
                "application/json, text/event-stream",
            )
            .header("MCP-Protocol-Version", PROTOCOL_VERSION)
            .json(message);
        for (name, value) in &self.headers {
            request = request.header(name, value);
        }
        let session = self.session.lock().ok().and_then(|session| session.clone());
        if let Some(session) = session {
            request = request.header("Mcp-Session-Id", session);
        }
        let response = request
            .send()
            .await
            .map_err(|error| format!("Cannot reach the MCP server: {error}"))?;
        if let Some(session) = response
            .headers()
            .get("Mcp-Session-Id")
            .and_then(|value| value.to_str().ok())
            && let Ok(mut current) = self.session.lock()
        {
            *current = Some(session.to_string());
        }
        if !response.status().is_success() {
            return Err(format!(
                "The MCP server answered HTTP {}",
                response.status()
            ));
        }
        Ok(response)
    }
}

fn sse_messages(body: &str) -> Vec<Value> {
    body.split("\n\n")
        .flat_map(|event| {
            let data: Vec<&str> = event
                .lines()
                .filter_map(|line| line.strip_prefix("data:"))
                .map(str::trim_start)
                .collect();
            serde_json::from_str::<Value>(&data.join("\n")).ok()
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn stdio_server_lists_and_calls_tools() {
        if !resolve_program("node").is_file() {
            return;
        }
        let script = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/mcp-echo.mjs");
        let config = TransportConfig::Stdio {
            command: "node".into(),
            args: vec![script.to_string_lossy().into_owned()],
            env: BTreeMap::new(),
        };
        let (events, _incoming) = mpsc::unbounded_channel();

        let client = McpClient::connect(&config, events).await.unwrap();
        let tools = client.list_tools().await.unwrap();
        let result = client
            .call_tool("echo", json!({ "text": "hi" }), Duration::from_secs(10))
            .await
            .unwrap();
        client.shutdown().await;

        assert_eq!(tools[0].name, "echo");
        assert!(tools[0].read_only);
        assert_eq!(result["content"][0]["text"], "echo: hi");
    }

    #[test]
    fn sse_body_yields_json_messages() {
        let body =
            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"ok\":true}}\n\n";

        let messages = sse_messages(body);

        assert_eq!(messages[0]["id"], 3);
        assert_eq!(response_result(&messages[0]).unwrap()["ok"], true);
    }

    #[test]
    fn errors_and_tool_annotations_are_read() {
        let error =
            json!({ "jsonrpc": "2.0", "id": 1, "error": { "code": -1, "message": "boom" } });
        assert_eq!(response_result(&error).unwrap_err(), "boom");

        let tool = tool_info(&json!({
            "name": "search",
            "annotations": { "readOnlyHint": true },
        }))
        .unwrap();
        assert!(tool.read_only);
        assert_eq!(tool.input_schema["type"], "object");
    }
}
