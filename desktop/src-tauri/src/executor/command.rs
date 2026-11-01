use std::path::Path;
use std::process::Stdio;
use std::time::Duration;

use serde_json::json;
use tokio::io::{AsyncRead, AsyncReadExt};
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

pub async fn run_command(
    root: &Path,
    request: &Request,
    mut output: impl FnMut(&str, String),
    mut cancel: watch::Receiver<bool>,
) -> Result<ExecOutcome, ExecError> {
    let command = request.required_str("command")?;
    let directory = resolve(root, request.str_arg("workingDirectory").unwrap_or("."))?;
    let mut process = Command::new("powershell.exe");
    process
        .args(["-NoLogo", "-NoProfile", "-NonInteractive", "-Command"])
        .arg(format!("{UTF8_PREAMBLE}{command}"))
        .current_dir(&directory)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    #[cfg(windows)]
    process.creation_flags(CREATE_NO_WINDOW);
    let mut child = process
        .spawn()
        .map_err(|error| ExecError::failed("spawn_failed", error.to_string()))?;
    let pid = child.id();

    let (sender, mut receiver) = mpsc::unbounded_channel::<(&'static str, String)>();
    if let Some(stdout) = child.stdout.take() {
        tokio::spawn(pump(stdout, "stdout", sender.clone()));
    }
    if let Some(stderr) = child.stderr.take() {
        tokio::spawn(pump(stderr, "stderr", sender.clone()));
    }
    drop(sender);

    let timeout = tokio::time::sleep(Duration::from_millis(request.timeout_ms));
    tokio::pin!(timeout);
    let mut combined = String::new();
    let mut dropped = 0usize;
    let mut streams_open = true;
    let mut exit = None;

    let finish = loop {
        tokio::select! {
            chunk = receiver.recv(), if streams_open => match chunk {
                Some((stream, text)) => {
                    append_tail(&mut combined, &mut dropped, &text);
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
    let output_text = if dropped > 0 {
        format!("[{dropped} bytes of earlier output omitted]\n{combined}")
    } else {
        combined
    };
    match finish {
        Finish::Exited(code) => Ok(ExecOutcome::result(json!({
            "exitCode": code,
            "output": output_text,
            "truncated": dropped > 0,
        }))),
        Finish::TimedOut => Err(ExecError::with_status(
            "timeout",
            "timeout",
            format!(
                "The command did not finish in {} ms. Output so far:\n{output_text}",
                request.timeout_ms
            ),
        )),
        Finish::Cancelled => Err(ExecError::with_status(
            "cancelled",
            "cancelled",
            "The command was cancelled",
        )),
    }
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

fn append_tail(combined: &mut String, dropped: &mut usize, text: &str) {
    combined.push_str(text);
    if combined.len() > MAX_OUTPUT_BYTES {
        let mut cut = combined.len() - MAX_OUTPUT_BYTES;
        while !combined.is_char_boundary(cut) {
            cut += 1;
        }
        *dropped += cut;
        combined.drain(..cut);
    }
}

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
            &request(json!({"command": "Write-Output 'привет'; exit 3"}), 30_000),
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
            &request(json!({"command": "Start-Sleep -Seconds 30"}), 1_500),
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
            &request(json!({"command": "Start-Sleep -Seconds 30"}), 60_000),
            |_, _| {},
            cancel,
        )
        .await
        .unwrap_err();

        assert_eq!(error.status, "cancelled");
        assert!(started.elapsed() < Duration::from_secs(10));
    }
}
