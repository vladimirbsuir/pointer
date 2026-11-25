use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

use serde_json::{Value, json};

use super::{Desktop, system};
use crate::paths::resolve;
use crate::{ExecError, Request};

const MAX_PROCESSES: usize = 8;
const DEFAULT_GRACE_MS: u64 = 1_000;
const MAX_GRACE_MS: u64 = 10_000;
const STOP_TIMEOUT: Duration = Duration::from_secs(3);
const POLL_INTERVAL: Duration = Duration::from_millis(50);
const LOG_TAIL_BYTES: usize = 4_000;
const LOG_SEARCH_BYTES: usize = 1024 * 1024;

pub struct Process {
    id: String,
    pid: u32,
    command: String,
    log_path: PathBuf,
    child: Child,
}

impl Drop for Process {
    fn drop(&mut self) {
        if matches!(self.child.try_wait(), Ok(None)) {
            system::signal_group(self.pid, true);
            let _ = self.child.wait();
        }
    }
}

pub fn reap(processes: &mut HashMap<String, Process>) {
    processes.retain(|_, process| matches!(process.child.try_wait(), Ok(None)));
}

pub fn list(processes: &HashMap<String, Process>) -> Vec<Value> {
    let mut entries: Vec<&Process> = processes.values().collect();
    entries.sort_by_key(|process| process.pid);
    entries
        .into_iter()
        .map(|process| {
            json!({
                "processId": process.id,
                "pid": process.pid,
                "command": process.command,
                "logPath": process.log_path.to_string_lossy(),
            })
        })
        .collect()
}

pub fn log_path(runtime_dir: &Path, process_id: &str) -> Option<PathBuf> {
    let valid = process_id.len() > 1
        && process_id.starts_with('p')
        && process_id[1..]
            .chars()
            .all(|character| character.is_ascii_digit());
    valid
        .then(|| runtime_dir.join(format!("{process_id}.log")))
        .filter(|path| path.is_file())
}

pub fn log_tail(path: &Path) -> String {
    tail(path, LOG_TAIL_BYTES)
}

pub fn log_tail_contains(path: &Path, text: &str) -> bool {
    tail(path, LOG_SEARCH_BYTES).contains(text)
}

fn tail(path: &Path, bytes: usize) -> String {
    let content = fs::read(path).unwrap_or_default();
    let start = content.len().saturating_sub(bytes);
    String::from_utf8_lossy(&content[start..]).into_owned()
}

impl Desktop {
    pub(super) fn launch(&self, root: &Path, request: &Request) -> Result<Value, ExecError> {
        let screen = self.screen()?;
        let command = request.required_str("command")?.to_string();
        let directory = resolve(root, request.str_arg("workingDirectory").unwrap_or("."))?;
        let grace = Duration::from_millis(
            request
                .u64_arg("graceMs")
                .unwrap_or(DEFAULT_GRACE_MS)
                .min(MAX_GRACE_MS),
        );
        let (process_id, pid, log_path) = {
            let mut state = self.lock();
            reap(&mut state.processes);
            if state.processes.len() >= MAX_PROCESSES {
                return Err(ExecError::failed(
                    "too_many_processes",
                    format!("At most {MAX_PROCESSES} processes can run at once; stop one first"),
                ));
            }
            state.next_process += 1;
            let process_id = format!("p{}", state.next_process);
            let log_path = self.runtime_dir.join(format!("{process_id}.log"));
            let log = fs::File::create(&log_path)
                .map_err(|error| ExecError::failed("launch_failed", error.to_string()))?;
            let errors = log
                .try_clone()
                .map_err(|error| ExecError::failed("launch_failed", error.to_string()))?;
            let mut process = Command::new("bash");
            process
                .arg("-c")
                .arg(&command)
                .current_dir(&directory)
                .envs(screen.environment())
                .stdin(Stdio::null())
                .stdout(log)
                .stderr(errors);
            system::new_process_group(&mut process);
            let child = process
                .spawn()
                .map_err(|error| ExecError::failed("launch_failed", error.to_string()))?;
            let pid = child.id();
            state.processes.insert(
                process_id.clone(),
                Process {
                    id: process_id.clone(),
                    pid,
                    command: command.clone(),
                    log_path: log_path.clone(),
                    child,
                },
            );
            (process_id, pid, log_path)
        };
        let deadline = Instant::now() + grace;
        loop {
            let exit = {
                let mut state = self.lock();
                match state.processes.get_mut(&process_id) {
                    Some(process) => process.child.try_wait().ok().flatten(),
                    None => None,
                }
            };
            if let Some(status) = exit {
                self.lock().processes.remove(&process_id);
                return Ok(json!({
                    "processId": process_id,
                    "pid": pid,
                    "logPath": log_path.to_string_lossy(),
                    "exited": true,
                    "exitCode": status.code(),
                    "logTail": log_tail(&log_path),
                }));
            }
            if Instant::now() >= deadline {
                break;
            }
            std::thread::sleep(POLL_INTERVAL);
        }
        Ok(json!({
            "processId": process_id,
            "pid": pid,
            "logPath": log_path.to_string_lossy(),
        }))
    }

    pub(super) fn stop(&self, request: &Request) -> Result<Value, ExecError> {
        let process_id = request.required_str("processId")?;
        let mut process = self.lock().processes.remove(process_id).ok_or_else(|| {
            ExecError::failed(
                "process_not_found",
                format!("No running process {process_id}"),
            )
        })?;
        system::signal_group(process.pid, false);
        let deadline = Instant::now() + STOP_TIMEOUT;
        let mut status = None;
        while Instant::now() < deadline {
            if let Ok(Some(exit)) = process.child.try_wait() {
                status = Some(exit);
                break;
            }
            std::thread::sleep(POLL_INTERVAL);
        }
        system::signal_group(process.pid, true);
        let status = match status {
            Some(status) => Some(status),
            None => process.child.wait().ok(),
        };
        Ok(json!({
            "stopped": true,
            "exitCode": status.and_then(|status| status.code()),
        }))
    }
}
