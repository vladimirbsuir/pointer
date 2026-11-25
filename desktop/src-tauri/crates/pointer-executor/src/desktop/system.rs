use std::ffi::OsStr;
use std::path::Path;
use std::process::{Command, Stdio};
use std::time::Duration;

use super::Screen;
use crate::{ExecError, Request};

const DEFAULT_SETTLE_MS: u64 = 300;
const MAX_SETTLE_MS: u64 = 5_000;

pub fn on_path(program: &str) -> bool {
    std::env::var_os("PATH").is_some_and(|path| in_path(&path, program))
}

pub fn in_path(path: &OsStr, program: &str) -> bool {
    std::env::split_paths(path).any(|directory| is_executable(&directory.join(program)))
}

fn is_executable(path: &Path) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        path.metadata()
            .is_ok_and(|metadata| metadata.is_file() && metadata.permissions().mode() & 0o111 != 0)
    }
    #[cfg(not(unix))]
    {
        path.is_file()
    }
}

pub fn settle(request: &Request) {
    let delay = request
        .u64_arg("settleMs")
        .unwrap_or(DEFAULT_SETTLE_MS)
        .min(MAX_SETTLE_MS);
    std::thread::sleep(Duration::from_millis(delay));
}

pub fn tool(screen: &Screen, program: &str) -> Command {
    let mut command = Command::new(program);
    command
        .envs(screen.environment())
        .env("LC_ALL", "C.UTF-8")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    command
}

pub fn run(mut command: Command, code: &'static str) -> Result<String, ExecError> {
    let output = command
        .output()
        .map_err(|error| ExecError::failed(code, error.to_string()))?;
    if output.status.success() {
        Ok(String::from_utf8_lossy(&output.stdout).into_owned())
    } else {
        Err(ExecError::failed(
            code,
            String::from_utf8_lossy(&output.stderr).trim().to_string(),
        ))
    }
}

pub fn succeeds(mut command: Command) -> bool {
    command
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .is_ok_and(|status| status.success())
}

pub fn new_process_group(command: &mut Command) {
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        command.process_group(0);
    }
    #[cfg(not(unix))]
    {
        let _ = command;
    }
}

pub fn signal_group(process_group: u32, kill: bool) {
    #[cfg(unix)]
    {
        let signal = if kill { libc::SIGKILL } else { libc::SIGTERM };
        if let Ok(group) = i32::try_from(process_group) {
            unsafe {
                libc::kill(-group, signal);
            }
        }
    }
    #[cfg(not(unix))]
    {
        let _ = (process_group, kill);
    }
}

pub fn signal_process(pid: u32) {
    #[cfg(unix)]
    {
        if let Ok(pid) = i32::try_from(pid) {
            unsafe {
                libc::kill(pid, libc::SIGTERM);
            }
        }
    }
    #[cfg(not(unix))]
    {
        let _ = pid;
    }
}

pub fn is_alive(pid: u32) -> bool {
    Path::new(&format!("/proc/{pid}")).exists()
}
