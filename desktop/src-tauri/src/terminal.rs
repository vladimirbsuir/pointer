use std::collections::HashMap;
use std::io::{Read, Write};
use std::path::Path;
use std::sync::{Mutex, MutexGuard};

use portable_pty::{ChildKiller, CommandBuilder, MasterPty, PtySize, native_pty_system};
use serde::Serialize;
use tauri::ipc::Channel;
use uuid::Uuid;

use crate::error::{AppError, AppResult};

const SHELL: &str = "powershell.exe";
const READ_BUFFER_BYTES: usize = 8192;

#[derive(Clone, Serialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum TerminalEvent {
    Output { data: String },
    Exit { code: Option<u32> },
}

struct Terminal {
    master: Box<dyn MasterPty + Send>,
    writer: Box<dyn Write + Send>,
    killer: Box<dyn ChildKiller + Send + Sync>,
}

#[derive(Default)]
pub struct Terminals {
    open: Mutex<HashMap<String, Terminal>>,
}

impl Terminals {
    pub fn open(
        &self,
        cwd: &Path,
        cols: u16,
        rows: u16,
        events: Channel<TerminalEvent>,
    ) -> AppResult<String> {
        let pty = native_pty_system()
            .openpty(size(cols, rows))
            .map_err(terminal_error)?;
        let mut command = CommandBuilder::new(SHELL);
        command.arg("-NoLogo");
        command.cwd(cwd);
        let mut child = pty.slave.spawn_command(command).map_err(terminal_error)?;
        drop(pty.slave);
        let reader = pty.master.try_clone_reader().map_err(terminal_error)?;
        let writer = pty.master.take_writer().map_err(terminal_error)?;
        let killer = child.clone_killer();

        stream_output(reader, events.clone());
        std::thread::spawn(move || {
            let code = child.wait().ok().map(|status| status.exit_code());
            let _ = events.send(TerminalEvent::Exit { code });
        });

        let id = Uuid::now_v7().to_string();
        self.lock()?.insert(
            id.clone(),
            Terminal {
                master: pty.master,
                writer,
                killer,
            },
        );
        Ok(id)
    }

    pub fn write(&self, id: &str, data: &str) -> AppResult<()> {
        let mut open = self.lock()?;
        let terminal = open.get_mut(id).ok_or_else(not_found)?;
        terminal.writer.write_all(data.as_bytes())?;
        terminal.writer.flush()?;
        Ok(())
    }

    pub fn resize(&self, id: &str, cols: u16, rows: u16) -> AppResult<()> {
        let open = self.lock()?;
        let terminal = open.get(id).ok_or_else(not_found)?;
        terminal
            .master
            .resize(size(cols, rows))
            .map_err(terminal_error)
    }

    pub fn close(&self, id: &str) -> AppResult<()> {
        if let Some(mut terminal) = self.lock()?.remove(id) {
            let _ = terminal.killer.kill();
        }
        Ok(())
    }

    pub fn close_all(&self) {
        if let Ok(mut open) = self.open.lock() {
            for (_, mut terminal) in open.drain() {
                let _ = terminal.killer.kill();
            }
        }
    }

    fn lock(&self) -> AppResult<MutexGuard<'_, HashMap<String, Terminal>>> {
        self.open
            .lock()
            .map_err(|_| AppError::new("internal_error", "Terminals are unavailable"))
    }
}

fn stream_output(mut reader: Box<dyn Read + Send>, events: Channel<TerminalEvent>) {
    std::thread::spawn(move || {
        let mut buffer = [0u8; READ_BUFFER_BYTES];
        let mut pending = Vec::new();
        loop {
            match reader.read(&mut buffer) {
                Ok(0) | Err(_) => break,
                Ok(count) => {
                    pending.extend_from_slice(&buffer[..count]);
                    let data = take_complete_utf8(&mut pending);
                    if !data.is_empty() && events.send(TerminalEvent::Output { data }).is_err() {
                        break;
                    }
                }
            }
        }
    });
}

fn take_complete_utf8(pending: &mut Vec<u8>) -> String {
    let complete = match std::str::from_utf8(pending) {
        Err(error) if error.error_len().is_none() => error.valid_up_to(),
        _ => pending.len(),
    };
    let rest = pending.split_off(complete);
    let text = String::from_utf8_lossy(pending).into_owned();
    *pending = rest;
    text
}

fn size(cols: u16, rows: u16) -> PtySize {
    PtySize {
        rows: rows.max(1),
        cols: cols.max(1),
        pixel_width: 0,
        pixel_height: 0,
    }
}

fn terminal_error(error: impl std::fmt::Display) -> AppError {
    AppError::new("terminal_error", error.to_string())
}

fn not_found() -> AppError {
    AppError::new("terminal_not_found", "The terminal is closed")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn incomplete_multibyte_characters_wait_for_the_next_read() {
        let mut pending = "Привет".as_bytes().to_vec();
        let tail = pending.split_off(3);

        assert_eq!(take_complete_utf8(&mut pending), "П");
        assert_eq!(pending.len(), 1);

        pending.extend_from_slice(&tail);
        assert_eq!(take_complete_utf8(&mut pending), "ривет");
        assert!(pending.is_empty());
    }

    #[cfg(windows)]
    #[test]
    fn shell_runs_commands_in_the_project_folder() {
        use std::sync::Arc;
        use std::time::{Duration, Instant};
        use tauri::ipc::InvokeResponseBody;

        let root = tempfile::tempdir().unwrap();
        std::fs::write(root.path().join("marker-file.txt"), "x").unwrap();
        let output = Arc::new(Mutex::new(String::new()));
        let sink = output.clone();
        let channel = Channel::new(move |body| {
            if let InvokeResponseBody::Json(json) = body {
                sink.lock().unwrap().push_str(&json);
            }
            Ok(())
        });
        let terminals = Terminals::default();

        let id = terminals.open(root.path(), 120, 30, channel).unwrap();
        terminals.write(&id, "\u{1b}[1;1R").unwrap();
        terminals.write(&id, "Get-ChildItem -Name\r").unwrap();
        let deadline = Instant::now() + Duration::from_secs(20);
        while !output.lock().unwrap().contains("marker-file.txt") && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(100));
        }
        terminals.close(&id).unwrap();

        let captured = output.lock().unwrap().clone();
        assert!(captured.contains("marker-file.txt"), "{captured}");
    }

    #[test]
    fn invalid_bytes_are_replaced_instead_of_blocking_output() {
        let mut pending = vec![b'a', 0xFF, b'b'];

        assert_eq!(take_complete_utf8(&mut pending), "a\u{FFFD}b");
        assert!(pending.is_empty());
    }
}
