mod command;
mod files;
mod paths;
mod search;

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Instant;

use serde_json::{Map, Value, json};
use tokio::sync::watch;

pub struct Request {
    pub operation: String,
    pub args: Value,
    pub dry_run: bool,
    pub timeout_ms: u64,
}

impl Request {
    fn str_arg(&self, name: &str) -> Option<&str> {
        self.args.get(name).and_then(Value::as_str)
    }

    fn required_str(&self, name: &str) -> Result<&str, ExecError> {
        self.str_arg(name).ok_or_else(|| {
            ExecError::rejected("invalid_arguments", format!("Missing argument {name}"))
        })
    }

    fn u64_arg(&self, name: &str) -> Option<u64> {
        self.args.get(name).and_then(Value::as_u64)
    }

    fn bool_arg(&self, name: &str) -> Option<bool> {
        self.args.get(name).and_then(Value::as_bool)
    }
}

#[derive(Debug)]
pub struct ExecOutcome {
    pub result: Value,
    pub file_changes: Vec<Value>,
}

impl ExecOutcome {
    fn result(result: Value) -> Self {
        Self {
            result,
            file_changes: Vec::new(),
        }
    }
}

#[derive(Debug)]
pub struct ExecError {
    pub status: &'static str,
    pub code: String,
    pub message: String,
}

impl ExecError {
    fn failed(code: impl Into<String>, message: impl Into<String>) -> Self {
        Self::with_status("failed", code, message)
    }

    fn rejected(code: impl Into<String>, message: impl Into<String>) -> Self {
        Self::with_status("rejected", code, message)
    }

    fn with_status(
        status: &'static str,
        code: impl Into<String>,
        message: impl Into<String>,
    ) -> Self {
        Self {
            status,
            code: code.into(),
            message: message.into(),
        }
    }
}

pub type Emit = Arc<dyn Fn(&str, Value) + Send + Sync>;

#[derive(Default)]
pub struct Executor {
    running: Mutex<HashMap<String, watch::Sender<bool>>>,
}

impl Executor {
    pub fn handle(self: &Arc<Self>, payload: Value, root: Option<PathBuf>, emit: Emit) {
        let call_id = payload["callId"].as_str().unwrap_or_default().to_string();
        let (cancel_sender, cancel) = watch::channel(false);
        if let Ok(mut running) = self.running.lock() {
            running.insert(call_id.clone(), cancel_sender);
        }
        let executor = self.clone();
        tauri::async_runtime::spawn(async move {
            let started = Instant::now();
            let request = Request {
                operation: payload["operation"]
                    .as_str()
                    .unwrap_or_default()
                    .to_string(),
                args: payload["arguments"].clone(),
                dry_run: payload["dryRun"].as_bool().unwrap_or(false),
                timeout_ms: payload["timeoutMs"].as_u64().unwrap_or(30_000),
            };
            let outcome = match root {
                Some(root) => execute(root, request, &call_id, emit.clone(), cancel).await,
                None => Err(ExecError::rejected(
                    "project_not_mapped",
                    "The project is not linked to a folder on this device",
                )),
            };
            emit("exec.result", result_payload(&call_id, outcome, started));
            executor.forget(&call_id);
        });
    }

    pub fn cancel(&self, call_id: &str) {
        if let Some(sender) = self
            .running
            .lock()
            .ok()
            .and_then(|running| running.get(call_id).cloned())
        {
            let _ = sender.send(true);
        }
    }

    fn forget(&self, call_id: &str) {
        if let Ok(mut running) = self.running.lock() {
            running.remove(call_id);
        }
    }
}

async fn execute(
    root: PathBuf,
    request: Request,
    call_id: &str,
    emit: Emit,
    mut cancel: watch::Receiver<bool>,
) -> Result<ExecOutcome, ExecError> {
    if request.operation == "run_command" {
        let call_id = call_id.to_string();
        return command::run_command(
            &root,
            &request,
            move |stream, data| {
                emit(
                    "exec.output",
                    json!({ "callId": call_id, "stream": stream, "data": data }),
                )
            },
            cancel,
        )
        .await;
    }
    let operation = request.operation.clone();
    let task = tokio::task::spawn_blocking(move || match operation.as_str() {
        "list_directory" => files::list_directory(&root, &request),
        "read_file" => files::read_file(&root, &request),
        "write_file" => files::write_file(&root, &request),
        "edit_file" => files::edit_file(&root, &request),
        "glob" => search::glob(&root, &request),
        "grep" => search::grep(&root, &request),
        "pointer.project_context" => files::project_context(&root),
        "pointer.restore_file" => files::restore_file(&root, &request),
        other => Err(ExecError::rejected(
            "unknown_operation",
            format!("Unknown operation {other}"),
        )),
    });
    tokio::select! {
        joined = task => joined.unwrap_or_else(|error| Err(ExecError::failed("internal_error", error.to_string()))),
        _ = cancel.wait_for(|cancelled| *cancelled) => Err(ExecError::with_status("cancelled", "cancelled", "Cancelled")),
    }
}

fn result_payload(
    call_id: &str,
    outcome: Result<ExecOutcome, ExecError>,
    started: Instant,
) -> Value {
    let mut payload = Map::new();
    payload.insert("callId".into(), json!(call_id));
    payload.insert(
        "durationMs".into(),
        json!(started.elapsed().as_millis() as u64),
    );
    match outcome {
        Ok(outcome) => {
            payload.insert("status".into(), json!("succeeded"));
            payload.insert("result".into(), outcome.result);
            payload.insert("error".into(), Value::Null);
            payload.insert("fileChanges".into(), Value::Array(outcome.file_changes));
        }
        Err(error) => {
            payload.insert("status".into(), json!(error.status));
            payload.insert("result".into(), json!({}));
            payload.insert(
                "error".into(),
                json!({ "code": error.code, "message": error.message }),
            );
            payload.insert("fileChanges".into(), json!([]));
        }
    }
    Value::Object(payload)
}
