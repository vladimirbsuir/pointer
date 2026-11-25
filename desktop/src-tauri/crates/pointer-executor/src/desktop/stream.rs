use std::fs;
use std::io::Write;
use std::net::{Ipv4Addr, SocketAddr, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

use super::{Screen, system};

const VNC_PORT: u16 = 5900;
const WEBSOCKET_PORT: u16 = 6080;
const VIEW_ONLY_MARKER: &str = "__BEGIN_VIEWONLY__";
const PASSWORD_FILE: &str = "vnc.passwd";
const READY_TIMEOUT: Duration = Duration::from_secs(10);
const CONNECT_TIMEOUT: Duration = Duration::from_millis(200);
const POLL_INTERVAL: Duration = Duration::from_millis(100);

#[derive(Clone)]
pub struct StreamSettings {
    password: String,
    view_password: String,
    vnc_port: u16,
    websocket_port: u16,
}

impl StreamSettings {
    pub fn new(
        password: impl Into<String>,
        view_password: impl Into<String>,
        vnc_port: u16,
        websocket_port: u16,
    ) -> Self {
        Self {
            password: password.into(),
            view_password: view_password.into(),
            vnc_port,
            websocket_port,
        }
    }

    pub fn from_environment() -> Option<Self> {
        let password = non_empty_variable("POINTER_VNC_PASSWORD")?;
        let view_password = non_empty_variable("POINTER_VNC_VIEW_PASSWORD")?;
        Some(Self::new(password, view_password, VNC_PORT, WEBSOCKET_PORT))
    }

    fn is_valid(&self) -> bool {
        [&self.password, &self.view_password]
            .iter()
            .all(|password| {
                password
                    .chars()
                    .all(|character| character.is_ascii_graphic())
            })
    }
}

fn non_empty_variable(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|value| !value.is_empty())
}

pub fn is_available() -> bool {
    system::on_path("x11vnc") && system::on_path("websockify")
}

pub struct Stream {
    server: Child,
    websocket: Child,
}

impl Stream {
    pub fn is_running(&mut self) -> bool {
        matches!(self.server.try_wait(), Ok(None)) && matches!(self.websocket.try_wait(), Ok(None))
    }

    fn shutdown(&mut self) {
        for child in [&mut self.websocket, &mut self.server] {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

impl Drop for Stream {
    fn drop(&mut self) {
        self.shutdown();
    }
}

pub fn start(settings: &StreamSettings, screen: &Screen, runtime_dir: &Path) -> Option<Stream> {
    if !settings.is_valid() || !is_available() {
        return None;
    }
    let password_file = write_password_file(settings, runtime_dir).ok()?;
    let server = start_server(settings, screen, &password_file, runtime_dir)?;
    let mut stream = match start_websocket(settings, runtime_dir) {
        Some(websocket) => Stream { server, websocket },
        None => {
            let mut server = server;
            let _ = server.kill();
            let _ = server.wait();
            return None;
        }
    };
    if await_ready(&mut stream, settings) {
        Some(stream)
    } else {
        None
    }
}

fn write_password_file(settings: &StreamSettings, runtime_dir: &Path) -> std::io::Result<PathBuf> {
    let path = runtime_dir.join(PASSWORD_FILE);
    let _ = fs::remove_file(&path);
    let mut options = fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(&path)?;
    writeln!(
        file,
        "{}\n{VIEW_ONLY_MARKER}\n{}",
        settings.password, settings.view_password
    )?;
    Ok(path)
}

fn start_server(
    settings: &StreamSettings,
    screen: &Screen,
    password_file: &Path,
    runtime_dir: &Path,
) -> Option<Child> {
    let log = fs::File::create(runtime_dir.join("x11vnc.log")).ok()?;
    Command::new("x11vnc")
        .envs(screen.environment())
        .args(["-display", &screen.display])
        .args(["-rfbport", &settings.vnc_port.to_string()])
        .args(["-localhost", "-forever", "-shared", "-viewonly", "-quiet"])
        .arg("-passwdfile")
        .arg(password_file)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(log)
        .spawn()
        .ok()
}

fn start_websocket(settings: &StreamSettings, runtime_dir: &Path) -> Option<Child> {
    let log = fs::File::create(runtime_dir.join("websockify.log")).ok()?;
    Command::new("websockify")
        .arg(settings.websocket_port.to_string())
        .arg(format!("localhost:{}", settings.vnc_port))
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(log)
        .spawn()
        .ok()
}

fn await_ready(stream: &mut Stream, settings: &StreamSettings) -> bool {
    let deadline = Instant::now() + READY_TIMEOUT;
    while Instant::now() < deadline {
        if !stream.is_running() {
            return false;
        }
        if is_listening(settings.vnc_port) && is_listening(settings.websocket_port) {
            return true;
        }
        std::thread::sleep(POLL_INTERVAL);
    }
    false
}

pub fn is_listening(port: u16) -> bool {
    let address = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
    TcpStream::connect_timeout(&address, CONNECT_TIMEOUT).is_ok()
}
