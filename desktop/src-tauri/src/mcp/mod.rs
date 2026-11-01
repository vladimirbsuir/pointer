mod client;
mod config;

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use serde::Serialize;
use serde_json::{Value, json};
use tauri::{AppHandle, Emitter, Manager};
use tokio::sync::mpsc;

use crate::error::{AppError, AppResult};
use crate::realtime::Realtime;
use client::{ClientEvent, McpClient, ToolInfo};
pub use config::ServerConfig;

const CHANGED_EVENT: &str = "mcp://changed";

#[derive(Clone, Copy, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
enum Status {
    Disabled,
    Starting,
    Running,
    Failed,
}

struct Runtime {
    status: Status,
    error: Option<String>,
    tools: Vec<ToolInfo>,
    client: Option<Arc<McpClient>>,
    generation: u64,
}

impl Runtime {
    fn new(status: Status) -> Self {
        Self {
            status,
            error: None,
            tools: Vec::new(),
            client: None,
            generation: 0,
        }
    }
}

struct State {
    configs: Vec<ServerConfig>,
    runtimes: HashMap<String, Runtime>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerView {
    #[serde(flatten)]
    config: ServerConfig,
    status: Status,
    error: Option<String>,
    tools: Vec<String>,
}

pub struct McpHub {
    file: PathBuf,
    state: Mutex<State>,
}

impl McpHub {
    pub fn load(data_dir: &Path) -> Arc<Self> {
        let file = config::config_path(data_dir);
        let configs = config::load(&file);
        Arc::new(Self {
            file,
            state: Mutex::new(State {
                configs,
                runtimes: HashMap::new(),
            }),
        })
    }

    pub fn start_all(self: &Arc<Self>, app: &AppHandle) {
        let names: Vec<String> = self
            .lock()
            .map(|state| {
                state
                    .configs
                    .iter()
                    .map(|config| config.name.clone())
                    .collect()
            })
            .unwrap_or_default();
        for name in names {
            self.start(app, &name);
        }
    }

    pub fn servers(&self) -> Vec<ServerView> {
        let Ok(state) = self.lock() else {
            return Vec::new();
        };
        state
            .configs
            .iter()
            .map(|config| {
                let runtime = state.runtimes.get(&config.name);
                ServerView {
                    config: config.clone(),
                    status: runtime.map_or(Status::Disabled, |runtime| runtime.status),
                    error: runtime.and_then(|runtime| runtime.error.clone()),
                    tools: runtime
                        .map(|runtime| runtime.tools.iter().map(|tool| tool.name.clone()).collect())
                        .unwrap_or_default(),
                }
            })
            .collect()
    }

    pub fn reported_tools(&self) -> Value {
        let Ok(state) = self.lock() else {
            return json!([]);
        };
        let tools: Vec<Value> = state
            .runtimes
            .iter()
            .filter(|(_, runtime)| runtime.status == Status::Running)
            .flat_map(|(server, runtime)| {
                runtime.tools.iter().map(move |tool| {
                    json!({
                        "server": server,
                        "name": tool.name,
                        "description": tool.description,
                        "inputSchema": tool.input_schema,
                        "readOnly": tool.read_only,
                    })
                })
            })
            .collect();
        json!(tools)
    }

    pub fn save(
        self: &Arc<Self>,
        app: &AppHandle,
        server: ServerConfig,
        previous_name: Option<String>,
    ) -> AppResult<()> {
        config::validate(&server)?;
        {
            let mut state = self.lock()?;
            let replaced = previous_name.as_deref().unwrap_or(&server.name).to_string();
            if replaced != server.name
                && state
                    .configs
                    .iter()
                    .any(|config| config.name == server.name)
            {
                return Err(AppError::new(
                    "mcp_name_taken",
                    "A server with this name already exists",
                ));
            }
            match state
                .configs
                .iter_mut()
                .find(|config| config.name == replaced)
            {
                Some(existing) => *existing = server.clone(),
                None => state.configs.push(server.clone()),
            }
            config::save(&self.file, &state.configs)?;
            if replaced != server.name {
                self.stop_locked(&mut state, &replaced);
                state.runtimes.remove(&replaced);
            }
        }
        self.start(app, &server.name);
        Ok(())
    }

    pub fn remove(self: &Arc<Self>, app: &AppHandle, name: &str) -> AppResult<()> {
        {
            let mut state = self.lock()?;
            state.configs.retain(|config| config.name != name);
            config::save(&self.file, &state.configs)?;
            self.stop_locked(&mut state, name);
            state.runtimes.remove(name);
        }
        self.changed(app);
        Ok(())
    }

    pub fn start(self: &Arc<Self>, app: &AppHandle, name: &str) {
        let (config, generation) = {
            let Ok(mut state) = self.lock() else {
                return;
            };
            let Some(config) = state
                .configs
                .iter()
                .find(|config| config.name == name)
                .cloned()
            else {
                return;
            };
            self.stop_locked(&mut state, name);
            let runtime = state
                .runtimes
                .entry(name.to_string())
                .or_insert_with(|| Runtime::new(Status::Disabled));
            runtime.generation += 1;
            runtime.error = None;
            runtime.tools.clear();
            runtime.status = if config.enabled {
                Status::Starting
            } else {
                Status::Disabled
            };
            (config, runtime.generation)
        };
        self.changed(app);
        if !config.enabled {
            return;
        }
        let hub = self.clone();
        let app = app.clone();
        tauri::async_runtime::spawn(async move {
            let (events, mut incoming) = mpsc::unbounded_channel();
            let outcome = match McpClient::connect(&config.transport, events).await {
                Ok(client) => match client.list_tools().await {
                    Ok(tools) => Ok((Arc::new(client), tools)),
                    Err(error) => {
                        client.shutdown().await;
                        Err(error)
                    }
                },
                Err(error) => Err(error),
            };
            let client = hub.finish_start(&config.name, generation, outcome).await;
            hub.changed(&app);
            let Some(client) = client else {
                return;
            };
            while let Some(event) = incoming.recv().await {
                match event {
                    ClientEvent::ToolsChanged => {
                        if let Ok(tools) = client.list_tools().await {
                            hub.update_tools(&config.name, generation, tools);
                            hub.changed(&app);
                        }
                    }
                    ClientEvent::Exited(stderr) => {
                        hub.mark_exited(&config.name, generation, stderr);
                        hub.changed(&app);
                        break;
                    }
                }
            }
        });
    }

    pub async fn call(
        &self,
        server: &str,
        tool: &str,
        arguments: Value,
        timeout: Duration,
    ) -> Result<Value, String> {
        let client = self
            .lock()
            .ok()
            .and_then(|state| {
                state
                    .runtimes
                    .get(server)
                    .and_then(|runtime| runtime.client.clone())
            })
            .ok_or_else(|| format!("The MCP server {server} is not running"))?;
        client.call_tool(tool, arguments, timeout).await
    }

    pub fn shutdown_all(&self) {
        let clients: Vec<Arc<McpClient>> = self
            .lock()
            .map(|mut state| {
                state
                    .runtimes
                    .values_mut()
                    .filter_map(|runtime| runtime.client.take())
                    .collect()
            })
            .unwrap_or_default();
        tauri::async_runtime::block_on(async {
            for client in clients {
                client.shutdown().await;
            }
        });
    }

    async fn finish_start(
        &self,
        name: &str,
        generation: u64,
        outcome: Result<(Arc<McpClient>, Vec<ToolInfo>), String>,
    ) -> Option<Arc<McpClient>> {
        let stale = {
            let Ok(mut state) = self.lock() else {
                return None;
            };
            match state.runtimes.get_mut(name) {
                Some(runtime) if runtime.generation == generation => {
                    match &outcome {
                        Ok((client, tools)) => {
                            runtime.status = Status::Running;
                            runtime.tools = tools.clone();
                            runtime.client = Some(client.clone());
                        }
                        Err(error) => {
                            runtime.status = Status::Failed;
                            runtime.error = Some(error.clone());
                        }
                    }
                    false
                }
                _ => true,
            }
        };
        match outcome {
            Ok((client, _)) if stale => {
                client.shutdown().await;
                None
            }
            Ok((client, _)) => Some(client),
            Err(_) => None,
        }
    }

    fn update_tools(&self, name: &str, generation: u64, tools: Vec<ToolInfo>) {
        if let Ok(mut state) = self.lock()
            && let Some(runtime) = state.runtimes.get_mut(name)
            && runtime.generation == generation
        {
            runtime.tools = tools;
        }
    }

    fn mark_exited(&self, name: &str, generation: u64, stderr: String) {
        if let Ok(mut state) = self.lock()
            && let Some(runtime) = state.runtimes.get_mut(name)
            && runtime.generation == generation
        {
            runtime.status = Status::Failed;
            runtime.client = None;
            runtime.tools.clear();
            runtime.error = Some(if stderr.is_empty() {
                "The server exited".into()
            } else {
                format!("The server exited\n{stderr}")
            });
        }
    }

    fn stop_locked(&self, state: &mut State, name: &str) {
        if let Some(client) = state
            .runtimes
            .get_mut(name)
            .and_then(|runtime| runtime.client.take())
        {
            tauri::async_runtime::spawn(async move { client.shutdown().await });
        }
    }

    fn changed(&self, app: &AppHandle) {
        let _ = app.emit(CHANGED_EVENT, ());
        app.state::<Realtime>().report_tools(self.reported_tools());
    }

    fn lock(&self) -> AppResult<MutexGuard<'_, State>> {
        self.state
            .lock()
            .map_err(|_| AppError::new("internal_error", "MCP servers are unavailable"))
    }
}
