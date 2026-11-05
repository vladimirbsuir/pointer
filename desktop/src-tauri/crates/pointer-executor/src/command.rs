use std::path::Path;
use std::process::Stdio;
use std::time::Duration;

use serde_json::json;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWriteExt};
use tokio::process::Command;
use tokio::sync::{mpsc, watch};

use super::paths::resolve;
use super::{ExecError, ExecOutcome, Request};

const CREATE_NO_WINDOW: u32 = 0x0800_0000;
const MAX_OUTPUT_BYTES: usize = 200 * 1024;
const UTF8_PREAMBLE: &str = "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; $ProgressPreference = 'SilentlyContinue'; ";

pub enum Finish {
    Exited(Option<i32>),
    TimedOut,
    Cancelled,
}

#[derive(Default)]
pub struct Tail {
    text: String,
    dropped: usize,
}

impl Tail {
    fn push(&mut self, text: &str) {
        self.text.push_str(text);
        if self.text.len() > MAX_OUTPUT_BYTES {
            let mut cut = self.text.len() - MAX_OUTPUT_BYTES;
            while !self.text.is_char_boundary(cut) {
                cut += 1;
            }
            self.dropped += cut;
            self.text.drain(..cut);
        }
    }

    pub fn truncated(&self) -> bool {
        self.dropped > 0
    }

    pub fn into_text(self) -> String {
        if self.dropped > 0 {
            format!(
                "[{} bytes of earlier output omitted]
{}",
                self.dropped, self.text
            )
        } else {
            self.text
        }
    }
}

pub struct ProcessRun {
    pub finish: Finish,
    pub combined: Tail,
    pub stdout: Tail,
    pub stderr: Tail,
}

pub async fn run_command(
    root: &Path,
    request: &Request,
    output: impl FnMut(&str, String),
    cancel: watch::Receiver<bool>,
) -> Result<ExecOutcome, ExecError> {
    let command = request.required_str("command")?;
    let directory = resolve(root, request.str_arg("workingDirectory").unwrap_or("."))?;
    let mut process = shell_command(command);
    process.current_dir(&directory);
    let run = run_process(process, None, request.timeout_ms, output, cancel).await?;
    let truncated = run.combined.truncated();
    let output_text = run.combined.into_text();
    match run.finish {
        Finish::Exited(code) => Ok(ExecOutcome::result(json!({
            "exitCode": code,
            "output": output_text,
            "truncated": truncated,
        }))),
        Finish::TimedOut => Err(timed_out(request.timeout_ms, &output_text)),
        Finish::Cancelled => Err(cancelled()),
    }
}

#[cfg(windows)]
pub const OS_NAME: &str = "Windows";
#[cfg(windows)]
pub const SHELL_NAME: &str = "PowerShell";
#[cfg(not(windows))]
pub const OS_NAME: &str = "Linux";
#[cfg(not(windows))]
pub const SHELL_NAME: &str = "bash";

#[cfg(windows)]
fn shell_command(command: &str) -> Command {
    let mut process = Command::new("powershell.exe");
    process
        .args(["-NoLogo", "-NoProfile", "-NonInteractive", "-Command"])
        .arg(format!("{UTF8_PREAMBLE}{command}"));
    process
}

#[cfg(not(windows))]
fn shell_command(command: &str) -> Command {
    let mut process = Command::new("bash");
    process.arg("-c").arg(command);
    process
}

pub fn timed_out(timeout_ms: u64, output: &str) -> ExecError {
    ExecError::with_status(
        "timeout",
        "timeout",
        format!(
            "The command did not finish in {timeout_ms} ms. Output so far:
{output}"
        ),
    )
}

pub fn cancelled() -> ExecError {
    ExecError::with_status("cancelled", "cancelled", "The command was cancelled")
}

pub async fn run_process(
    mut process: Command,
    stdin: Option<Vec<u8>>,
    timeout_ms: u64,
    mut output: impl FnMut(&str, String),
    mut cancel: watch::Receiver<bool>,
) -> Result<ProcessRun, ExecError> {
    process
        .stdin(if stdin.is_some() {
            Stdio::piped()
        } else {
            Stdio::null()
        })
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    #[cfg(windows)]
    process.creation_flags(CREATE_NO_WINDOW);
    #[cfg(not(windows))]
    process.process_group(0);
    let mut child = process
        .spawn()
        .map_err(|error| ExecError::failed("spawn_failed", error.to_string()))?;
    let pid = child.id();

    if let (Some(mut writer), Some(input)) = (child.stdin.take(), stdin) {
        tokio::spawn(async move {
            let _ = writer.write_all(&input).await;
            let _ = writer.shutdown().await;
        });
    }
    let (sender, mut receiver) = mpsc::unbounded_channel::<(&'static str, String)>();
    if let Some(stdout) = child.stdout.take() {
        tokio::spawn(pump(stdout, "stdout", sender.clone()));
    }
    if let Some(stderr) = child.stderr.take() {
        tokio::spawn(pump(stderr, "stderr", sender.clone()));
    }
    drop(sender);

    let timeout = tokio::time::sleep(Duration::from_millis(timeout_ms));
    tokio::pin!(timeout);
    let mut combined = Tail::default();
    let mut stdout = Tail::default();
    let mut stderr = Tail::default();
    let mut streams_open = true;
    let mut exit = None;

    let finish = loop {
        tokio::select! {
            chunk = receiver.recv(), if streams_open => match chunk {
                Some((stream, text)) => {
                    combined.push(&text);
                    if stream == "stdout" {
                        stdout.push(&text);
                    } else {
                        stderr.push(&text);
                    }
                    output(stream, text);
                }
                None => streams_open = false,
            },
            status = child.wait(), if exit.is_none() => {
                exit = Some(status.ok().and_then(|status| status.code()));
            },
            _ = &mut timeout => break Finish::TimedOut,
            changed = cancel.changed() => {
                if changed.is_err() || *cancel.borrow() {
                    break Finish::Cancelled;
                }
            },
        }
        if let (Some(code), false) = (exit, streams_open) {
            break Finish::Exited(code);
        }
    };

    if !matches!(finish, Finish::Exited(_)) {
        kill_tree(pid).await;
        let _ = child.kill().await;
    }
    Ok(ProcessRun {
        finish,
        combined,
        stdout,
        stderr,
    })
}

async fn pump(
    mut reader: impl AsyncRead + Unpin,
    stream: &'static str,
    sender: mpsc::UnboundedSender<(&'static str, String)>,
) {
    let mut buffer = vec![0u8; 8192];
    let mut pending = Vec::new();
    while let Ok(read) = reader.read(&mut buffer).await {
        if read == 0 {
            break;
        }
        pending.extend_from_slice(&buffer[..read]);
        let valid_up_to = match std::str::from_utf8(&pending) {
            Ok(_) => pending.len(),
            Err(error) => error.valid_up_to(),
        };
        if valid_up_to > 0 {
            let text = String::from_utf8_lossy(&pending[..valid_up_to]).into_owned();
            pending.drain(..valid_up_to);
            if sender.send((stream, text)).is_err() {
                break;
            }
        }
    }
    if !pending.is_empty() {
        let _ = sender.send((stream, String::from_utf8_lossy(&pending).into_owned()));
    }
}

#[cfg(not(windows))]
async fn kill_tree(pid: Option<u32>) {
    let Some(pid) = pid else {
        return;
    };
    let _ = Command::new("kill")
        .args(["-KILL", &format!("-{pid}")])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .await;
}

#[cfg(windows)]
async fn kill_tree(pid: Option<u32>) {
    let Some(pid) = pid else {
        return;
    };
    let mut taskkill = Command::new("taskkill");
    taskkill
        .args(["/PID", &pid.to_string(), "/T", "/F"])
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    #[cfg(windows)]
    taskkill.creation_flags(CREATE_NO_WINDOW);
    let _ = taskkill.status().await;
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{Value, json};

    #[cfg(windows)]
    const PRINT_AND_EXIT: &str = "Write-Output 'привет'; exit 3";
    #[cfg(not(windows))]
    const PRINT_AND_EXIT: &str = "echo 'привет'; exit 3";
    #[cfg(windows)]
    const SLEEP: &str = "Start-Sleep -Seconds 30";
    #[cfg(not(windows))]
    const SLEEP: &str = "sleep 30";

    fn request(args: Value, timeout_ms: u64) -> Request {
        Request {
            operation: "run_command".into(),
            args,
            dry_run: false,
            timeout_ms,
        }
    }

    #[tokio::test]
    async fn captures_exit_code_and_streams_output() {
        let root = tempfile::tempdir().unwrap();
        let (_cancel_sender, cancel) = watch::channel(false);
        let mut streamed = String::new();

        let outcome = run_command(
            root.path(),
            &request(json!({"command": PRINT_AND_EXIT}), 30_000),
            |_, text| streamed.push_str(&text),
            cancel,
        )
        .await
        .unwrap();

        assert_eq!(outcome.result["exitCode"], 3);
        assert!(
            outcome.result["output"]
                .as_str()
                .unwrap()
                .contains("привет")
        );
        assert!(streamed.contains("привет"));
    }

    #[tokio::test]
    async fn times_out_long_commands() {
        let root = tempfile::tempdir().unwrap();
        let (_cancel_sender, cancel) = watch::channel(false);

        let error = run_command(
            root.path(),
            &request(json!({"command": SLEEP}), 1_500),
            |_, _| {},
            cancel,
        )
        .await
        .unwrap_err();

        assert_eq!(error.status, "timeout");
    }

    #[tokio::test]
    async fn cancellation_stops_the_command() {
        let root = tempfile::tempdir().unwrap();
        let (cancel_sender, cancel) = watch::channel(false);
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(800)).await;
            let _ = cancel_sender.send(true);
        });

        let started = std::time::Instant::now();
        let error = run_command(
            root.path(),
            &request(json!({"command": SLEEP}), 60_000),
            |_, _| {},
            cancel,
        )
        .await
        .unwrap_err();

        assert_eq!(error.status, "cancelled");
        assert!(started.elapsed() < Duration::from_secs(10));
    }
}
