mod capture;
mod display;
mod input;
mod keymap;
mod keys;
mod processes;
mod stream;
mod system;
mod tree;

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};

use serde_json::{Value, json};

use super::{ExecError, ExecOutcome, Request};
use stream::StreamSettings;

pub const STATUS: &str = "pointer.desktop_status";
const DEFAULT_DISPLAY: &str = ":99";
const RUNTIME_DIRECTORY: &str = "/tmp/pointer-desktop";

pub fn is_operation(operation: &str) -> bool {
    operation.starts_with("desktop_") || operation == STATUS
}

pub fn is_supported() -> bool {
    std::env::var_os("PATH").is_some_and(|path| is_supported_in(&path))
}

fn is_supported_in(path: &std::ffi::OsStr) -> bool {
    system::in_path(path, "Xvfb") && system::in_path(path, "xdotool")
}

#[derive(Clone)]
pub struct Desktop {
    state: Arc<Mutex<State>>,
    display: String,
    runtime_dir: PathBuf,
    stream: Option<StreamSettings>,
}

#[derive(Default)]
struct State {
    display: Option<display::Display>,
    processes: HashMap<String, processes::Process>,
    next_process: u64,
    elements: tree::Elements,
}

#[derive(Clone)]
pub struct Screen {
    display: String,
    width: u32,
    height: u32,
    max_image_width: u32,
    max_image_height: u32,
    max_image_bytes: usize,
    bus_address: Option<String>,
}

impl Screen {
    fn environment(&self) -> Vec<(String, String)> {
        let mut environment = vec![("DISPLAY".to_string(), self.display.clone())];
        if let Some(address) = &self.bus_address {
            environment.push(("DBUS_SESSION_BUS_ADDRESS".to_string(), address.clone()));
        }
        environment
    }

    fn image_size(&self) -> (u32, u32) {
        capture::scaled_size(
            self.width,
            self.height,
            self.max_image_width,
            self.max_image_height,
        )
    }
}

impl Default for Desktop {
    fn default() -> Self {
        let display = std::env::var("DISPLAY")
            .ok()
            .filter(|display| display.starts_with(':'))
            .unwrap_or_else(|| DEFAULT_DISPLAY.to_string());
        Self::new(display, PathBuf::from(RUNTIME_DIRECTORY))
            .with_stream(StreamSettings::from_environment())
    }
}

impl Desktop {
    pub fn new(display: impl Into<String>, runtime_dir: PathBuf) -> Self {
        Self {
            state: Arc::new(Mutex::new(State::default())),
            display: display.into(),
            runtime_dir,
            stream: None,
        }
    }

    pub fn with_stream(mut self, stream: Option<StreamSettings>) -> Self {
        self.stream = stream;
        self
    }

    pub fn environment(&self) -> Vec<(String, String)> {
        self.screen()
            .map(|screen| screen.environment())
            .unwrap_or_default()
    }

    pub fn handle(&self, root: &Path, request: &Request) -> Result<ExecOutcome, ExecError> {
        if !is_supported() {
            return Err(ExecError::rejected(
                "desktop_unavailable",
                "This executor has no virtual display",
            ));
        }
        let result = match request.operation.as_str() {
            "desktop_start" => self.start(request),
            STATUS => Ok(self.status()),
            "desktop_launch" => self.launch(root, request),
            "desktop_stop" => self.stop(request),
            "desktop_screenshot" => self.screenshot(request),
            "desktop_move" => self.move_pointer(request),
            "desktop_click" => self.click(request),
            "desktop_drag" => self.drag(request),
            "desktop_scroll" => self.scroll(request),
            "desktop_type" => self.type_text(request),
            "desktop_key" => self.press_keys(request),
            "desktop_wait" => self.wait(request),
            "desktop_tree" => self.tree(request),
            other => Err(ExecError::rejected(
                "unknown_operation",
                format!("Unknown operation {other}"),
            )),
        }?;
        Ok(ExecOutcome::result(result))
    }

    fn lock(&self) -> MutexGuard<'_, State> {
        self.state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn screen(&self) -> Result<Screen, ExecError> {
        let mut state = self.lock();
        display::alive(&mut state)
            .map(|display| display.screen(&self.display))
            .ok_or_else(not_started)
    }

    fn status(&self) -> Value {
        let mut state = self.lock();
        let screen = display::alive(&mut state).map(|display| display.screen(&self.display));
        let stream = state
            .display
            .as_mut()
            .is_some_and(display::Display::is_streaming);
        processes::reap(&mut state.processes);
        let processes: Vec<Value> = processes::list(&state.processes);
        match screen {
            Some(screen) => {
                let (image_width, image_height) = screen.image_size();
                json!({
                    "running": true,
                    "width": screen.width,
                    "height": screen.height,
                    "imageWidth": image_width,
                    "imageHeight": image_height,
                    "stream": stream,
                    "processes": processes,
                })
            }
            None => json!({ "running": false, "stream": false, "processes": processes }),
        }
    }

    fn observe(
        &self,
        request: &Request,
        screen: &Screen,
        mut result: Value,
    ) -> Result<Value, ExecError> {
        if request.bool_arg("observe") == Some(false) {
            return Ok(result);
        }
        system::settle(request);
        let image = capture::capture(screen, &self.runtime_dir, None)?;
        result["images"] = json!([image]);
        Ok(result)
    }
}

fn not_started() -> ExecError {
    ExecError::failed(
        "display_not_started",
        "The display is not running. Call desktop_start first",
    )
}

#[cfg(test)]
mod tests;
