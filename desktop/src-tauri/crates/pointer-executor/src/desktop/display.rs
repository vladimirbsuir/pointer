use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

use serde_json::{Value, json};

use super::{Desktop, Screen, State, stream, system};
use crate::{ExecError, Request};

const DEFAULT_WIDTH: u32 = 1280;
const DEFAULT_HEIGHT: u32 = 800;
const MIN_WIDTH: u32 = 800;
const MIN_HEIGHT: u32 = 600;
const MAX_WIDTH: u32 = 1920;
const MAX_HEIGHT: u32 = 1200;
const DEFAULT_MAX_IMAGE_BYTES: usize = 2 * 1024 * 1024;
const READY_TIMEOUT: Duration = Duration::from_secs(10);
const WINDOW_MANAGER_TIMEOUT: Duration = Duration::from_secs(5);
const POLL_INTERVAL: Duration = Duration::from_millis(100);

pub struct Display {
    width: u32,
    height: u32,
    max_image_width: u32,
    max_image_height: u32,
    max_image_bytes: usize,
    server: Child,
    window_manager: Option<Child>,
    bus_address: Option<String>,
    bus_pid: Option<u32>,
    stream: Option<stream::Stream>,
}

impl Display {
    pub fn is_streaming(&mut self) -> bool {
        self.stream.as_mut().is_some_and(stream::Stream::is_running)
    }

    pub fn screen(&self, display: &str) -> Screen {
        Screen {
            display: display.to_string(),
            width: self.width,
            height: self.height,
            max_image_width: self.max_image_width,
            max_image_height: self.max_image_height,
            max_image_bytes: self.max_image_bytes,
            bus_address: self.bus_address.clone(),
        }
    }

    fn shutdown(&mut self) {
        self.stream = None;
        if let Some(window_manager) = &mut self.window_manager {
            let _ = window_manager.kill();
            let _ = window_manager.wait();
        }
        if let Some(pid) = self.bus_pid {
            system::signal_process(pid);
        }
        let _ = self.server.kill();
        let _ = self.server.wait();
    }
}

impl Drop for Display {
    fn drop(&mut self) {
        self.shutdown();
    }
}

pub fn alive(state: &mut State) -> Option<&Display> {
    let display = state.display.as_mut()?;
    let exited = !matches!(display.server.try_wait(), Ok(None));
    if exited {
        state.display = None;
        return None;
    }
    state.display.as_ref()
}

impl Desktop {
    pub(super) fn start(&self, request: &Request) -> Result<Value, ExecError> {
        let requested_width = dimension(request, "width")?;
        let requested_height = dimension(request, "height")?;
        let mut state = self.lock();
        if let Some(display) = alive(&mut state) {
            let same_width = requested_width.is_none_or(|width| width == display.width);
            let same_height = requested_height.is_none_or(|height| height == display.height);
            if same_width && same_height {
                return Ok(started(
                    &display.screen(&self.display),
                    true,
                    display.window_manager.is_some(),
                ));
            }
            return Err(ExecError::failed(
                "display_running",
                format!(
                    "The display already runs at {}x{} and cannot be resized",
                    display.width, display.height
                ),
            ));
        }
        let width = requested_width.unwrap_or(DEFAULT_WIDTH);
        let height = requested_height.unwrap_or(DEFAULT_HEIGHT);
        if !(MIN_WIDTH..=MAX_WIDTH).contains(&width) || !(MIN_HEIGHT..=MAX_HEIGHT).contains(&height)
        {
            return Err(ExecError::rejected(
                "invalid_arguments",
                format!(
                    "The display size must be between {MIN_WIDTH}x{MIN_HEIGHT} and {MAX_WIDTH}x{MAX_HEIGHT}"
                ),
            ));
        }
        let max_image_width = image_limit(request, "maxImageWidth", DEFAULT_WIDTH);
        let max_image_height = image_limit(request, "maxImageHeight", DEFAULT_HEIGHT);
        let max_image_bytes = request
            .u64_arg("maxImageBytes")
            .and_then(|bytes| usize::try_from(bytes).ok())
            .filter(|bytes| *bytes > 0)
            .unwrap_or(DEFAULT_MAX_IMAGE_BYTES);
        fs::create_dir_all(&self.runtime_dir)
            .map_err(|error| ExecError::failed("display_failed", error.to_string()))?;
        remove_stale_server_files(&self.display);
        let server = start_server(&self.display, width, height, &self.runtime_dir)?;
        let mut display = Display {
            width,
            height,
            max_image_width,
            max_image_height,
            max_image_bytes,
            server,
            window_manager: None,
            bus_address: None,
            bus_pid: None,
            stream: None,
        };
        wait_until_ready(&mut display, &self.display, &self.runtime_dir)?;
        if let Some((address, pid)) = start_bus() {
            display.bus_address = Some(address);
            display.bus_pid = pid;
        }
        let screen = display.screen(&self.display);
        display.window_manager = start_window_manager(&screen, &self.runtime_dir);
        display.stream = self
            .stream
            .as_ref()
            .and_then(|settings| stream::start(settings, &screen, &self.runtime_dir));
        let result = started(&screen, false, display.window_manager.is_some());
        state.display = Some(display);
        Ok(result)
    }
}

fn started(screen: &Screen, already_running: bool, window_manager: bool) -> Value {
    let (image_width, image_height) = screen.image_size();
    json!({
        "display": screen.display,
        "width": screen.width,
        "height": screen.height,
        "imageWidth": image_width,
        "imageHeight": image_height,
        "windowManager": window_manager.then_some("openbox"),
        "alreadyRunning": already_running,
    })
}

fn dimension(request: &Request, name: &str) -> Result<Option<u32>, ExecError> {
    match request.args.get(name) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => value
            .as_u64()
            .and_then(|value| u32::try_from(value).ok())
            .map(Some)
            .ok_or_else(|| {
                ExecError::rejected(
                    "invalid_arguments",
                    format!("{name} must be a positive integer"),
                )
            }),
    }
}

fn image_limit(request: &Request, name: &str, default: u32) -> u32 {
    request
        .u64_arg(name)
        .and_then(|value| u32::try_from(value).ok())
        .filter(|value| *value >= 100)
        .unwrap_or(default)
}

fn display_number(display: &str) -> &str {
    display
        .trim_start_matches(':')
        .split('.')
        .next()
        .unwrap_or_default()
}

fn remove_stale_server_files(display: &str) {
    let number = display_number(display);
    let lock = PathBuf::from(format!("/tmp/.X{number}-lock"));
    let owner = fs::read_to_string(&lock)
        .ok()
        .and_then(|content| content.trim().parse::<u32>().ok());
    if owner.is_some_and(system::is_alive) {
        return;
    }
    let _ = fs::remove_file(&lock);
    let _ = fs::remove_file(format!("/tmp/.X11-unix/X{number}"));
}

fn start_server(
    display: &str,
    width: u32,
    height: u32,
    runtime_dir: &Path,
) -> Result<Child, ExecError> {
    let log = fs::File::create(runtime_dir.join("xvfb.log"))
        .map_err(|error| ExecError::failed("display_failed", error.to_string()))?;
    Command::new("Xvfb")
        .arg(display)
        .args([
            "-screen",
            "0",
            &format!("{width}x{height}x24"),
            "-nolisten",
            "tcp",
        ])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(log)
        .spawn()
        .map_err(|error| {
            ExecError::failed("display_failed", format!("Xvfb did not start: {error}"))
        })
}

fn wait_until_ready(
    display: &mut Display,
    name: &str,
    runtime_dir: &Path,
) -> Result<(), ExecError> {
    let deadline = Instant::now() + READY_TIMEOUT;
    loop {
        if !matches!(display.server.try_wait(), Ok(None)) {
            let log = fs::read_to_string(runtime_dir.join("xvfb.log")).unwrap_or_default();
            return Err(ExecError::failed(
                "display_failed",
                format!("Xvfb exited: {}", log.trim()),
            ));
        }
        let mut probe = Command::new("xdpyinfo");
        probe.args(["-display", name]);
        if system::succeeds(probe) {
            return Ok(());
        }
        if Instant::now() > deadline {
            return Err(ExecError::failed(
                "display_failed",
                "The display did not become ready in 10 seconds",
            ));
        }
        std::thread::sleep(POLL_INTERVAL);
    }
}

fn start_bus() -> Option<(String, Option<u32>)> {
    let output = Command::new("dbus-daemon")
        .args([
            "--session",
            "--fork",
            "--print-address=1",
            "--print-pid=1",
            "--nopidfile",
        ])
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
        .ok()
        .filter(|output| output.status.success())?;
    let text = String::from_utf8_lossy(&output.stdout);
    let mut lines = text.lines().map(str::trim).filter(|line| !line.is_empty());
    let address = lines.next()?.to_string();
    let pid = lines.next().and_then(|line| line.parse().ok());
    Some((address, pid))
}

fn start_window_manager(screen: &Screen, runtime_dir: &Path) -> Option<Child> {
    if !system::on_path("openbox") {
        return None;
    }
    let log = fs::File::create(runtime_dir.join("openbox.log")).ok()?;
    let mut child = Command::new("openbox")
        .envs(screen.environment())
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(log)
        .spawn()
        .ok()?;
    let deadline = Instant::now() + WINDOW_MANAGER_TIMEOUT;
    while Instant::now() < deadline {
        if !matches!(child.try_wait(), Ok(None)) {
            return None;
        }
        let mut probe = system::tool(screen, "xprop");
        probe.args(["-root", "_NET_CLIENT_LIST"]);
        if system::run(probe, "display_failed")
            .is_ok_and(|output| output.starts_with("_NET_CLIENT_LIST(WINDOW)"))
        {
            break;
        }
        std::thread::sleep(POLL_INTERVAL);
    }
    Some(child)
}
