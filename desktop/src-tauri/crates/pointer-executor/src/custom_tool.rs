use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::time::Duration;

use serde_json::json;
use tokio::process::Command;
use tokio::sync::watch;

use super::command::{self, Finish};
use super::{ExecError, ExecOutcome, Request};

const PROBE_TIMEOUT: Duration = Duration::from_secs(10);
const MAX_ERROR_OUTPUT_CHARS: usize = 8_000;
const POWERSHELL_PREAMBLE: &str = "$OutputEncoding = [Console]::InputEncoding = [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false; $ProgressPreference = 'SilentlyContinue'";

#[derive(Clone, Debug)]
struct Launcher {
    program: PathBuf,
    args: Vec<String>,
}

#[derive(Clone, Debug, Default)]
pub struct Interpreters {
    launchers: BTreeMap<&'static str, Launcher>,
}

impl Interpreters {
    pub async fn probe() -> Self {
        let mut launchers = BTreeMap::new();
        for (name, candidates) in candidates() {
            for candidate in candidates {
                if responds(&candidate).await {
                    launchers.insert(name, candidate);
                    break;
                }
            }
        }
        Self { launchers }
    }

    pub fn names(&self) -> Vec<&'static str> {
        self.launchers.keys().copied().collect()
    }

    fn launcher(&self, name: &str) -> Option<&Launcher> {
        self.launchers.get(name)
    }
}

fn candidates() -> Vec<(&'static str, Vec<Launcher>)> {
    vec![
        (
            "python",
            vec![
                launcher("python", &[]),
                launcher("py", &["-3"]),
                launcher("python3", &[]),
            ],
        ),
        ("node", vec![launcher("node", &[])]),
        (
            "powershell",
            vec![launcher(
                "powershell.exe",
                &[
                    "-NoLogo",
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                ],
            )],
        ),
        ("bash", bash_candidates()),
    ]
}

#[cfg(windows)]
fn bash_candidates() -> Vec<Launcher> {
    ["ProgramFiles", "ProgramW6432", "LOCALAPPDATA"]
        .iter()
        .filter_map(std::env::var_os)
        .flat_map(|base| {
            let base = PathBuf::from(base);
            [
                base.join("Git").join("bin").join("bash.exe"),
                base.join("Programs")
                    .join("Git")
                    .join("bin")
                    .join("bash.exe"),
            ]
        })
        .filter(|path| path.is_file())
        .map(|program| Launcher {
            program,
            args: Vec::new(),
        })
        .collect()
}

#[cfg(not(windows))]
fn bash_candidates() -> Vec<Launcher> {
    vec![launcher("bash", &[])]
}

fn launcher(program: &str, args: &[&str]) -> Launcher {
    Launcher {
        program: PathBuf::from(program),
        args: args.iter().map(|arg| arg.to_string()).collect(),
    }
}

async fn responds(launcher: &Launcher) -> bool {
    let mut process = Command::new(&launcher.program);
    if launcher.program.ends_with("powershell.exe") {
        process.args([
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            "exit 0",
        ]);
    } else {
        process.args(&launcher.args).arg("--version");
    }
    process
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .kill_on_drop(true);
    #[cfg(windows)]
    process.creation_flags(0x0800_0000);
    match tokio::time::timeout(PROBE_TIMEOUT, process.status()).await {
        Ok(Ok(status)) => status.success(),
        _ => false,
    }
}

pub async fn run(
    root: &Path,
    request: &Request,
    call_id: &str,
    interpreters: &Interpreters,
    output: impl FnMut(&str, String),
    cancel: watch::Receiver<bool>,
) -> Result<ExecOutcome, ExecError> {
    let interpreter = request.required_str("interpreter")?;
    let body = request.required_str("body")?;
    let launcher = interpreters.launcher(interpreter).ok_or_else(|| {
        ExecError::rejected(
            "interpreter_unavailable",
            format!("The interpreter {interpreter} is not available on this device"),
        )
    })?;
    let arguments = request
        .args
        .get("arguments")
        .filter(|arguments| arguments.is_object())
        .cloned()
        .unwrap_or_else(|| json!({}));
    let script = ScriptFile::write(call_id, interpreter, body)?;
    let mut process = Command::new(&launcher.program);
    process
        .args(&launcher.args)
        .arg(&script.path)
        .current_dir(root)
        .env("POINTER_PROJECT_ROOT", root)
        .env("PYTHONUTF8", "1")
        .env("PYTHONIOENCODING", "utf-8");
    let input = serde_json::to_vec(&arguments)
        .map_err(|error| ExecError::failed("internal_error", error.to_string()))?;
    let run =
        command::run_process(process, Some(input), request.timeout_ms, output, cancel).await?;
    match run.finish {
        Finish::Exited(Some(0)) => {
            let truncated = run.stdout.truncated();
            let stderr = run.stderr.into_text();
            let mut result = json!({
                "output": run.stdout.into_text(),
                "truncated": truncated,
            });
            if !stderr.trim().is_empty() {
                result["stderr"] = json!(stderr);
            }
            Ok(ExecOutcome::result(result))
        }
        Finish::Exited(code) => Err(ExecError::failed(
            "tool_failed",
            failure_message(code, run.stderr.into_text(), run.stdout.into_text()),
        )),
        Finish::TimedOut => Err(command::timed_out(
            request.timeout_ms,
            &run.combined.into_text(),
        )),
        Finish::Cancelled => Err(command::cancelled()),
    }
}

fn failure_message(code: Option<i32>, stderr: String, stdout: String) -> String {
    let code = code.map_or_else(|| "none".to_string(), |code| code.to_string());
    let mut message = format!("The tool exited with code {code}.");
    for (label, text) in [("stderr", stderr), ("stdout", stdout)] {
        let text = text.trim();
        if !text.is_empty() {
            message.push_str(&format!(
                "\n{label}:\n{}",
                tail(text, MAX_ERROR_OUTPUT_CHARS)
            ));
        }
    }
    message
}

fn tail(text: &str, max_chars: usize) -> &str {
    let count = text.chars().count();
    if count <= max_chars {
        return text;
    }
    let start = text
        .char_indices()
        .nth(count - max_chars)
        .map_or(0, |(index, _)| index);
    &text[start..]
}

struct ScriptFile {
    path: PathBuf,
}

impl ScriptFile {
    fn write(call_id: &str, interpreter: &str, body: &str) -> Result<Self, ExecError> {
        let directory = std::env::temp_dir().join("pointer-tools");
        std::fs::create_dir_all(&directory)
            .map_err(|error| ExecError::failed("io_error", error.to_string()))?;
        let file_name: String = call_id
            .chars()
            .filter(|character| character.is_ascii_alphanumeric() || *character == '-')
            .collect();
        let path = directory.join(format!("{}.{}", file_name, extension(interpreter)));
        std::fs::write(&path, contents(interpreter, body))
            .map_err(|error| ExecError::failed("io_error", error.to_string()))?;
        Ok(Self { path })
    }
}

impl Drop for ScriptFile {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
    }
}

fn extension(interpreter: &str) -> &'static str {
    match interpreter {
        "python" => "py",
        "node" => "js",
        "powershell" => "ps1",
        _ => "sh",
    }
}

fn contents(interpreter: &str, body: &str) -> Vec<u8> {
    if interpreter == "powershell" {
        let mut bytes = vec![0xEF, 0xBB, 0xBF];
        bytes.extend_from_slice(POWERSHELL_PREAMBLE.as_bytes());
        bytes.push(b'\n');
        bytes.extend_from_slice(body.as_bytes());
        bytes
    } else {
        body.replace("\r\n", "\n").into_bytes()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{Value, json};

    fn request(args: Value) -> Request {
        Request {
            operation: "custom_tool.run".into(),
            args,
            dry_run: false,
            timeout_ms: 30_000,
        }
    }

    async fn run_script(interpreter: &str, body: &str) -> Result<ExecOutcome, ExecError> {
        let root = tempfile::tempdir().unwrap();
        let (_cancel_sender, cancel) = watch::channel(false);
        let interpreters = Interpreters::probe().await;
        run(
            root.path(),
            &request(json!({
                "interpreter": interpreter,
                "body": body,
                "arguments": { "name": "мир" },
            })),
            &uuid::Uuid::now_v7().to_string(),
            &interpreters,
            |_, _| {},
            cancel,
        )
        .await
    }

    #[cfg(windows)]
    #[tokio::test]
    async fn powershell_script_reads_arguments_from_stdin() {
        let outcome = run_script(
            "powershell",
            "$arguments = [Console]::In.ReadToEnd() | ConvertFrom-Json\nWrite-Output \"hello $($arguments.name)\"",
        )
        .await
        .unwrap();

        assert_eq!(
            outcome.result["output"].as_str().unwrap().trim(),
            "hello мир"
        );
    }

    #[cfg(windows)]
    #[tokio::test]
    async fn non_zero_exit_is_a_failure_with_stderr() {
        let error = run_script("powershell", "[Console]::Error.WriteLine('broken'); exit 4")
            .await
            .unwrap_err();

        assert_eq!(error.code, "tool_failed");
        assert!(error.message.contains("code 4"));
        assert!(error.message.contains("broken"));
    }

    #[tokio::test]
    async fn unknown_interpreter_is_rejected() {
        let error = run_script("ruby", "puts 1").await.unwrap_err();

        assert_eq!(error.status, "rejected");
        assert_eq!(error.code, "interpreter_unavailable");
    }
}
