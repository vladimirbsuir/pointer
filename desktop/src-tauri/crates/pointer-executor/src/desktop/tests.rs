use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{Duration, Instant};

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use image::{DynamicImage, GenericImageView};
use serde_json::{Value, json};
use tempfile::TempDir;

use super::*;

const TIMEOUT: Duration = Duration::from_secs(20);

fn enabled() -> bool {
    std::env::var_os("POINTER_DESKTOP_TESTS").is_some()
}

struct Fixture {
    desktop: Desktop,
    root: TempDir,
    _runtime: TempDir,
}

impl Fixture {
    fn new(display: &str) -> Self {
        let runtime = tempfile::tempdir().unwrap();
        Self {
            desktop: Desktop::new(display, runtime.path().to_path_buf()),
            root: tempfile::tempdir().unwrap(),
            _runtime: runtime,
        }
    }

    fn call(&self, operation: &str, args: Value) -> Result<Value, ExecError> {
        let request = Request {
            operation: operation.to_string(),
            args,
            dry_run: false,
            timeout_ms: 30_000,
        };
        self.desktop
            .handle(self.root.path(), &request)
            .map(|outcome| outcome.result)
    }

    fn ok(&self, operation: &str, args: Value) -> Value {
        self.call(operation, args)
            .unwrap_or_else(|error| panic!("{operation} failed: {} {}", error.code, error.message))
    }

    fn error(&self, operation: &str, args: Value) -> String {
        match self.call(operation, args) {
            Ok(result) => panic!("{operation} succeeded unexpectedly: {result}"),
            Err(error) => error.code,
        }
    }

    fn start_tk_counter(&self) -> PathBuf {
        let check = self.root.path().join("tk-counter.json");
        let launched = self.ok(
            "desktop_launch",
            json!({ "command": format!("python3 {} {}", apps().join("tk-counter/app.py").display(), check.display()) }),
        );
        let waited = self.ok(
            "desktop_wait",
            json!({ "untilLog": { "processId": launched["processId"], "text": "tk-counter ready" }, "ms": 60_000, "observe": false }),
        );
        let log = launched["logPath"]
            .as_str()
            .and_then(|path| std::fs::read_to_string(path).ok())
            .unwrap_or_default();
        assert_eq!(
            waited["met"], true,
            "tk-counter did not start: {launched} {log}"
        );
        check
    }

    fn window_title(&self, part: &str) -> String {
        let screen = self.desktop.screen().unwrap();
        let mut command = system::tool(&screen, "xdotool");
        command.args([
            "search",
            "--onlyvisible",
            "--name",
            part,
            "getwindowname",
            "%@",
        ]);
        system::run(command, "input_failed").unwrap_or_default()
    }
}

fn apps() -> PathBuf {
    std::env::var_os("POINTER_DESKTOP_APPS")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../../infra/bench/desktop/apps")
        })
}

fn decode(result: &Value) -> DynamicImage {
    let image = &result["images"][0];
    let bytes = STANDARD.decode(image["data"].as_str().unwrap()).unwrap();
    image::load_from_memory(&bytes).unwrap()
}

fn read_state(path: &Path) -> Value {
    std::fs::read_to_string(path)
        .ok()
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or(Value::Null)
}

fn await_state(path: &Path, condition: impl Fn(&Value) -> bool) -> Value {
    let deadline = Instant::now() + TIMEOUT;
    loop {
        let state = read_state(path);
        if condition(&state) {
            return state;
        }
        if Instant::now() > deadline {
            panic!("The state did not reach the condition: {state}");
        }
        std::thread::sleep(Duration::from_millis(100));
    }
}

fn center(bounds: &Value) -> (u64, u64) {
    let x = bounds["x"].as_u64().unwrap() + bounds["width"].as_u64().unwrap() / 2;
    let y = bounds["y"].as_u64().unwrap() + bounds["height"].as_u64().unwrap() / 2;
    (x, y)
}

fn color_center(image: &DynamicImage, color: [u8; 3]) -> Option<(u32, u32)> {
    let (mut sum_x, mut sum_y, mut count) = (0u64, 0u64, 0u64);
    for (x, y, pixel) in image.pixels() {
        let [red, green, blue, _] = pixel.0;
        let close = |value: u8, target: u8| value.abs_diff(target) < 24;
        if close(red, color[0]) && close(green, color[1]) && close(blue, color[2]) {
            sum_x += u64::from(x);
            sum_y += u64::from(y);
            count += 1;
        }
    }
    (count > 200).then(|| ((sum_x / count) as u32, (sum_y / count) as u32))
}

#[test]
fn reports_support_only_when_the_display_tools_are_on_the_path() {
    assert!(!is_supported_in(std::ffi::OsStr::new("")));
    if enabled() {
        assert!(is_supported());
    }
}

#[test]
fn display_lifecycle_is_idempotent_and_bounded() {
    if !enabled() {
        return;
    }
    let fixture = Fixture::new(":101");
    assert_eq!(
        fixture.error("desktop_screenshot", json!({})),
        "display_not_started"
    );
    assert_eq!(
        fixture.error("desktop_start", json!({ "width": 640, "height": 480 })),
        "invalid_arguments"
    );
    assert_eq!(fixture.ok(STATUS, json!({}))["running"], false);

    let started = fixture.ok("desktop_start", json!({}));
    assert_eq!(started["width"], 1280);
    assert_eq!(started["height"], 800);
    assert_eq!(started["imageWidth"], 1280);
    assert_eq!(started["windowManager"], "openbox");
    assert_eq!(started["alreadyRunning"], false);

    let again = fixture.ok("desktop_start", json!({}));
    assert_eq!(again["alreadyRunning"], true);
    let same = fixture.ok("desktop_start", json!({ "width": 1280, "height": 800 }));
    assert_eq!(same["alreadyRunning"], true);
    assert_eq!(
        fixture.error("desktop_start", json!({ "width": 1024, "height": 768 })),
        "display_running"
    );
    let status = fixture.ok(STATUS, json!({}));
    assert_eq!(status["running"], true);
    assert_eq!(status["width"], 1280);

    let environment = fixture.desktop.environment();
    assert!(
        environment
            .iter()
            .any(|(key, value)| key == "DISPLAY" && value == ":101")
    );
    let bus = environment
        .iter()
        .find(|(key, _)| key == "DBUS_SESSION_BUS_ADDRESS")
        .map(|(_, value)| value.clone())
        .expect("the session bus is started with the display");
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let (_cancel, receiver) = tokio::sync::watch::channel(false);
    let request = Request {
        operation: "run_command".into(),
        args: json!({ "command": "echo \"$DBUS_SESSION_BUS_ADDRESS\"" }),
        dry_run: false,
        timeout_ms: 10_000,
    };
    let outcome = runtime
        .block_on(crate::command::run_command(
            fixture.root.path(),
            &request,
            &environment,
            |_, _| {},
            receiver,
        ))
        .unwrap();
    assert_eq!(outcome.result["output"].as_str().unwrap().trim(), bus);
}

#[test]
fn launched_processes_report_exits_stop_and_are_limited() {
    if !enabled() {
        return;
    }
    let fixture = Fixture::new(":102");
    assert_eq!(
        fixture.error("desktop_launch", json!({ "command": "sleep 30" })),
        "display_not_started"
    );
    fixture.ok("desktop_start", json!({}));

    let alive = fixture.ok(
        "desktop_launch",
        json!({ "command": "echo started; sleep 30" }),
    );
    assert!(alive.get("exited").is_none());
    assert!(alive["pid"].as_u64().unwrap() > 0);
    let log = PathBuf::from(alive["logPath"].as_str().unwrap());
    assert_eq!(std::fs::read_to_string(&log).unwrap().trim(), "started");

    let exited = fixture.ok(
        "desktop_launch",
        json!({ "command": "echo broken >&2; exit 3" }),
    );
    assert_eq!(exited["exited"], true);
    assert_eq!(exited["exitCode"], 3);
    assert_eq!(exited["logTail"].as_str().unwrap().trim(), "broken");

    let stopped = fixture.ok("desktop_stop", json!({ "processId": alive["processId"] }));
    assert_eq!(stopped["stopped"], true);
    assert!(!system::is_alive(alive["pid"].as_u64().unwrap() as u32));
    assert_eq!(
        fixture.error("desktop_stop", json!({ "processId": alive["processId"] })),
        "process_not_found"
    );

    let group = fixture.ok(
        "desktop_launch",
        json!({ "command": "sleep 31 & sleep 32 & wait" }),
    );
    fixture.ok("desktop_stop", json!({ "processId": group["processId"] }));
    let survivors = Command::new("pgrep")
        .args(["-f", "sleep 3[12]"])
        .output()
        .unwrap();
    assert!(survivors.stdout.is_empty(), "the process group survived");

    let mut launched = Vec::new();
    for _ in 0..8 {
        launched.push(fixture.ok(
            "desktop_launch",
            json!({ "command": "sleep 30", "graceMs": 0 }),
        ));
    }
    assert_eq!(
        fixture.error("desktop_launch", json!({ "command": "sleep 30" })),
        "too_many_processes"
    );
    assert_eq!(
        fixture.ok(STATUS, json!({}))["processes"]
            .as_array()
            .unwrap()
            .len(),
        8
    );
    for process in launched {
        fixture.ok("desktop_stop", json!({ "processId": process["processId"] }));
    }
}

#[test]
fn input_and_capture_drive_a_tk_application() {
    if !enabled() {
        return;
    }
    let fixture = Fixture::new(":103");
    fixture.ok("desktop_start", json!({}));
    let check = fixture.start_tk_counter();
    let state = await_state(&check, |state| state["button"]["width"].as_u64() > Some(1));

    let before = fixture.ok("desktop_screenshot", json!({}));
    assert_eq!(before["width"], 1280);
    let image = &before["images"][0];
    assert_eq!(image["mediaType"], "image/png");
    assert_eq!(
        (image["width"].as_u64(), image["height"].as_u64()),
        (Some(1280), Some(800))
    );
    let bytes = STANDARD.decode(image["data"].as_str().unwrap()).unwrap();
    assert_eq!(&bytes[..8], b"\x89PNG\r\n\x1a\n");

    let (x, y) = center(&state["button"]);
    let clicked = fixture.ok("desktop_click", json!({ "x": x, "y": y }));
    assert_eq!(clicked["x"], x);
    await_state(&check, |state| state["count"] == 1);
    assert_ne!(clicked["images"][0]["data"], image["data"]);

    let double = fixture.ok(
        "desktop_click",
        json!({ "x": x, "y": y, "clicks": 2, "observe": false }),
    );
    assert!(double.get("images").is_none());
    await_state(&check, |state| state["count"] == 3);

    let (entry_x, entry_y) = center(&state["entry"]);
    fixture.ok(
        "desktop_click",
        json!({ "x": entry_x, "y": entry_y, "observe": false }),
    );
    let typed = fixture.ok(
        "desktop_type",
        json!({ "text": "Hello, world 1!", "observe": false }),
    );
    assert_eq!(typed["method"], "keys");
    assert_eq!(typed["typed"], 15);
    await_state(&check, |state| state["text"] == "Hello, world 1!");

    for text in [" Привет", " café", " 😀"] {
        let expected = format!("{}{text}", read_state(&check)["text"].as_str().unwrap());
        let typed = fixture.ok("desktop_type", json!({ "text": text, "observe": false }));
        assert_eq!(typed["method"], "keys");
        await_state(&check, |state| state["text"] == expected.as_str());
    }
    let pasted = fixture.ok(
        "desktop_type",
        json!({ "text": "Ж", "method": "paste", "observe": false }),
    );
    assert_eq!(pasted["method"], "paste");
    await_state(&check, |state| {
        state["text"]
            .as_str()
            .is_some_and(|text| text.ends_with('Ж'))
    });

    fixture.ok("desktop_key", json!({ "keys": "ctrl+s" }));
    await_state(&check, |state| state["saved"] == 1);
    assert_eq!(
        fixture.error("desktop_key", json!({ "keys": "ctrl+foo" })),
        "invalid_key"
    );
    assert_eq!(
        fixture.error("desktop_key", json!({ "keys": "ctrl+" })),
        "invalid_key"
    );
    let screen = fixture.desktop.screen().unwrap();
    let mut unchecked = system::tool(&screen, "xdotool");
    unchecked.args(["key", "--clearmodifiers", "foo"]);
    assert!(
        system::run(unchecked, "input_failed").is_ok(),
        "xdotool alone accepts unknown names"
    );

    let (list_x, list_y) = center(&state["list"]);
    fixture.ok(
        "desktop_scroll",
        json!({ "x": list_x, "y": list_y, "direction": "down", "amount": 5 }),
    );
    await_state(&check, |state| state["top"].as_u64() > Some(0));

    fixture.ok(
        "desktop_move",
        json!({ "x": 10, "y": 10, "observe": false }),
    );
    let dragged = fixture.ok(
        "desktop_drag",
        json!({ "fromX": 5, "fromY": 5, "toX": 50, "toY": 60, "observe": false }),
    );
    assert_eq!(dragged["toY"], 60);

    let zoomed = fixture.ok(
        "desktop_screenshot",
        json!({ "zoom": { "x": 0, "y": 0, "width": 320, "height": 200 } }),
    );
    let zoom_image = &zoomed["images"][0];
    assert_eq!(
        (zoom_image["width"].as_u64(), zoom_image["height"].as_u64()),
        (Some(1280), Some(800))
    );
    assert_eq!(zoom_image["screenWidth"], 320);
    assert_eq!(zoomed["zoom"]["width"], 320);

    assert_eq!(
        fixture.error("desktop_click", json!({ "x": 1280, "y": 10 })),
        "invalid_coordinates"
    );
    assert_eq!(
        fixture.error("desktop_click", json!({ "x": -1, "y": 10 })),
        "invalid_coordinates"
    );
    assert_eq!(
        fixture.error("desktop_move", json!({ "x": 10 })),
        "invalid_coordinates"
    );
    assert_eq!(
        fixture.error(
            "desktop_screenshot",
            json!({ "zoom": { "x": 1200, "y": 0, "width": 200, "height": 100 } })
        ),
        "invalid_coordinates"
    );
    assert_eq!(
        fixture.error("desktop_type", json!({ "text": "x".repeat(2_001) })),
        "text_too_long"
    );
}

#[test]
fn waits_for_windows_and_log_lines() {
    if !enabled() {
        return;
    }
    let fixture = Fixture::new(":104");
    fixture.ok("desktop_start", json!({}));
    let missing = fixture.ok(
        "desktop_wait",
        json!({ "untilWindow": "No Such Window", "ms": 800 }),
    );
    assert_eq!(missing["met"], false);
    assert!(missing["waitedMs"].as_u64().unwrap() >= 800);
    assert!(missing["images"].is_array());

    fixture.start_tk_counter();
    let window = fixture.ok(
        "desktop_wait",
        json!({ "untilWindow": "tk counter", "ms": 5_000, "observe": false }),
    );
    assert_eq!(window["met"], true);
    let plain = fixture.ok("desktop_wait", json!({ "ms": 200, "observe": false }));
    assert_eq!(plain["met"], true);
    assert_eq!(
        fixture.error("desktop_wait", json!({ "ms": 10_001 })),
        "invalid_arguments"
    );
    assert_eq!(
        fixture.error(
            "desktop_wait",
            json!({ "untilLog": { "processId": "p99", "text": "x" } })
        ),
        "process_not_found"
    );
}

#[test]
fn large_displays_are_scaled_to_the_image_limit() {
    if !enabled() {
        return;
    }
    let fixture = Fixture::new(":105");
    let started = fixture.ok("desktop_start", json!({ "width": 1920, "height": 1200 }));
    assert_eq!(
        (
            started["imageWidth"].as_u64(),
            started["imageHeight"].as_u64()
        ),
        (Some(1280), Some(800))
    );
    let shot = fixture.ok("desktop_screenshot", json!({}));
    let image = &shot["images"][0];
    assert_eq!(
        (image["width"].as_u64(), image["height"].as_u64()),
        (Some(1280), Some(800))
    );
    assert_eq!(
        (
            image["screenWidth"].as_u64(),
            image["screenHeight"].as_u64()
        ),
        (Some(1920), Some(1200))
    );
    assert_eq!(decode(&shot).dimensions(), (1280, 800));
}

#[test]
fn chromium_renders_and_html_drag_needs_intermediate_moves() {
    if !enabled() {
        return;
    }
    let fixture = Fixture::new(":106");
    fixture.ok("desktop_start", json!({}));
    let page = apps().join("form-web/index.html").canonicalize().unwrap();
    let profile = fixture.root.path().join("profile");
    fixture.ok(
        "desktop_launch",
        json!({ "command": format!("pointer-chromium --user-data-dir={} --app=file://{}", profile.display(), page.display()) }),
    );
    let window = fixture.ok(
        "desktop_wait",
        json!({ "untilWindow": "form-web", "ms": 30_000, "observe": false }),
    );
    assert_eq!(window["met"], true, "Chromium did not open the page");
    std::thread::sleep(Duration::from_secs(2));
    let shot = fixture.ok("desktop_screenshot", json!({}));
    let image = decode(&shot);
    let red = color_center(&image, [255, 0, 0]).expect("item A is visible");
    let blue = color_center(&image, [0, 0, 255]).expect("item C is visible");
    assert!(
        fixture
            .window_title("form-web")
            .contains("\"order\":\"ABCD\"")
    );

    let screen = fixture.desktop.screen().unwrap();
    let mut jump = system::tool(&screen, "xdotool");
    jump.args([
        "mousemove",
        &red.0.to_string(),
        &red.1.to_string(),
        "mousedown",
        "1",
        "mousemove",
        &blue.0.to_string(),
        &(blue.1 + 10).to_string(),
        "mouseup",
        "1",
    ]);
    system::run(jump, "input_failed").unwrap();
    std::thread::sleep(Duration::from_millis(800));
    let after_jump = fixture.window_title("form-web");
    eprintln!("after a jump: {after_jump}");

    fixture.ok(
        "desktop_drag",
        json!({ "fromX": red.0, "fromY": red.1, "toX": blue.0, "toY": blue.1 + 10 }),
    );
    std::thread::sleep(Duration::from_millis(800));
    let after_drag = fixture.window_title("form-web");
    assert!(
        after_drag.contains("\"order\":\"BCAD\""),
        "drag did not reorder: {after_drag}"
    );
    assert!(
        after_jump.contains("\"order\":\"ABCD\""),
        "a jump reordered: {after_jump}"
    );

    let field = color_center(&image, [255, 255, 0]).expect("the text field is visible");
    fixture.ok(
        "desktop_click",
        json!({ "x": field.0, "y": field.1, "observe": false }),
    );
    fixture.ok(
        "desktop_type",
        json!({ "text": "Привет 😀 café", "observe": false }),
    );
    std::thread::sleep(Duration::from_millis(500));
    let typed = fixture.window_title("form-web");
    assert!(
        typed.contains("Привет 😀 café"),
        "the text did not arrive: {typed}"
    );
}

#[test]
fn webkit_renders_with_the_image_variables_and_reports_which_are_needed() {
    let Some(binary) = std::env::var_os("POINTER_WEBKIT_COUNTER") else {
        return;
    };
    if !enabled() {
        return;
    }
    let fixture = Fixture::new(":107");
    fixture.ok("desktop_start", json!({}));
    let variants = [
        (
            "without both",
            "-u WEBKIT_DISABLE_DMABUF_RENDERER -u WEBKIT_DISABLE_COMPOSITING_MODE",
        ),
        ("without DMABUF", "-u WEBKIT_DISABLE_DMABUF_RENDERER"),
        ("without compositing", "-u WEBKIT_DISABLE_COMPOSITING_MODE"),
        ("all variables", ""),
    ];
    let mut outcomes = Vec::new();
    for (label, unset) in variants {
        let launched = fixture.ok(
            "desktop_launch",
            json!({ "command": format!("env {unset} {}", Path::new(&binary).display()) }),
        );
        let opened = fixture.ok(
            "desktop_wait",
            json!({ "untilWindow": "webkit-counter 0", "ms": 60_000, "observe": false }),
        );
        std::thread::sleep(Duration::from_secs(1));
        let image = decode(&fixture.ok("desktop_screenshot", json!({})));
        let painted = color_center(&image, [0x20, 0xa0, 0x60]).is_some();
        let clicked = match color_center(&image, [0xff, 0xcc, 0x00]) {
            Some((x, y)) => {
                fixture.ok("desktop_click", json!({ "x": x, "y": y, "observe": false }));
                fixture.ok(
                    "desktop_wait",
                    json!({ "untilWindow": "webkit-counter 1", "ms": 3_000, "observe": false }),
                )["met"]
                    == true
            }
            None => false,
        };
        let log = launched["logPath"]
            .as_str()
            .and_then(|path| std::fs::read_to_string(path).ok())
            .unwrap_or_default();
        eprintln!(
            "webkit {label}: opened={} painted={painted} clicked={clicked} launch={launched} log={log}",
            opened["met"]
        );
        outcomes.push((label, painted && clicked));
        let _ = fixture.call(
            "desktop_stop",
            json!({ "processId": launched["processId"] }),
        );
    }
    assert!(
        outcomes.iter().all(|(_, works)| *works),
        "WebKitGTK failed in a variant: {outcomes:?}"
    );
}
