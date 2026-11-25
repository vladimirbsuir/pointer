use std::io::Write;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use serde_json::{Value, json};

use super::{Desktop, Screen, keys, processes, system};
use crate::{ExecError, Request};

const MAX_TEXT_CHARS: usize = 2_000;
const TYPE_DELAY_MS: &str = "12";
const CLICK_DELAY_MS: &str = "80";
const SCROLL_DELAY_MS: &str = "40";
const DRAG_STEPS: u32 = 10;
const DRAG_STEP_SECONDS: &str = "0.02";
const MAX_SCROLL_TICKS: u64 = 25;
const MAX_PLAIN_WAIT_MS: u64 = 10_000;
const DEFAULT_CONDITION_WAIT_MS: u64 = 60_000;
const MAX_CONDITION_WAIT_MS: u64 = 120_000;
const CONDITION_POLL: Duration = Duration::from_millis(250);
const MODIFIERS: [&str; 4] = ["ctrl", "shift", "alt", "super"];

impl Desktop {
    pub(super) fn move_pointer(&self, request: &Request) -> Result<Value, ExecError> {
        let screen = self.screen()?;
        let (x, y) = point(request, &screen, "x", "y")?;
        xdotool(&screen, &["mousemove".into(), x.to_string(), y.to_string()])?;
        self.observe(request, &screen, json!({ "x": x, "y": y }))
    }

    pub(super) fn click(&self, request: &Request) -> Result<Value, ExecError> {
        let screen = self.screen()?;
        let (x, y) = point(request, &screen, "x", "y")?;
        let button = button(request)?;
        let clicks = request.u64_arg("clicks").unwrap_or(1);
        if !(1..=3).contains(&clicks) {
            return Err(ExecError::rejected(
                "invalid_arguments",
                "clicks must be between 1 and 3",
            ));
        }
        let modifiers = modifiers(request)?;
        let mut arguments = vec!["mousemove".to_string(), x.to_string(), y.to_string()];
        for modifier in &modifiers {
            arguments.extend(["keydown".to_string(), modifier.clone()]);
        }
        arguments.extend([
            "click".to_string(),
            "--repeat".to_string(),
            clicks.to_string(),
            "--delay".to_string(),
            CLICK_DELAY_MS.to_string(),
            button.to_string(),
        ]);
        for modifier in modifiers.iter().rev() {
            arguments.extend(["keyup".to_string(), modifier.clone()]);
        }
        xdotool(&screen, &arguments)?;
        self.observe(request, &screen, json!({ "x": x, "y": y }))
    }

    pub(super) fn drag(&self, request: &Request) -> Result<Value, ExecError> {
        let screen = self.screen()?;
        let (from_x, from_y) = point(request, &screen, "fromX", "fromY")?;
        let (to_x, to_y) = point(request, &screen, "toX", "toY")?;
        let button = button(request)?;
        let mut arguments = vec![
            "mousemove".to_string(),
            from_x.to_string(),
            from_y.to_string(),
            "mousedown".to_string(),
            button.to_string(),
        ];
        for step in 1..=DRAG_STEPS {
            let x = interpolate(from_x, to_x, step);
            let y = interpolate(from_y, to_y, step);
            arguments.extend([
                "sleep".to_string(),
                DRAG_STEP_SECONDS.to_string(),
                "mousemove".to_string(),
                x.to_string(),
                y.to_string(),
            ]);
        }
        arguments.extend([
            "sleep".to_string(),
            DRAG_STEP_SECONDS.to_string(),
            "mouseup".to_string(),
            button.to_string(),
        ]);
        xdotool(&screen, &arguments)?;
        self.observe(
            request,
            &screen,
            json!({ "fromX": from_x, "fromY": from_y, "toX": to_x, "toY": to_y }),
        )
    }

    pub(super) fn scroll(&self, request: &Request) -> Result<Value, ExecError> {
        let screen = self.screen()?;
        let (x, y) = point(request, &screen, "x", "y")?;
        let button = match request.required_str("direction")? {
            "up" => 4,
            "down" => 5,
            "left" => 6,
            "right" => 7,
            _ => {
                return Err(ExecError::rejected(
                    "invalid_arguments",
                    "direction must be up, down, left or right",
                ));
            }
        };
        let amount = request.u64_arg("amount").unwrap_or(3);
        if !(1..=MAX_SCROLL_TICKS).contains(&amount) {
            return Err(ExecError::rejected(
                "invalid_arguments",
                format!("amount must be between 1 and {MAX_SCROLL_TICKS}"),
            ));
        }
        xdotool(
            &screen,
            &[
                "mousemove".into(),
                x.to_string(),
                y.to_string(),
                "click".into(),
                "--repeat".into(),
                amount.to_string(),
                "--delay".into(),
                SCROLL_DELAY_MS.into(),
                button.to_string(),
            ],
        )?;
        self.observe(request, &screen, json!({ "x": x, "y": y }))
    }

    pub(super) fn type_text(&self, request: &Request) -> Result<Value, ExecError> {
        let screen = self.screen()?;
        let text = request.required_str("text")?;
        let typed = text.chars().count();
        if typed > MAX_TEXT_CHARS {
            return Err(ExecError::rejected(
                "text_too_long",
                format!(
                    "Type at most {MAX_TEXT_CHARS} characters at once; put long input in a file"
                ),
            ));
        }
        let method = if request.str_arg("method") == Some("paste") {
            "paste"
        } else {
            "keys"
        };
        if method == "paste" {
            paste(&screen, text)?;
        } else {
            xdotool(
                &screen,
                &[
                    "type".into(),
                    "--delay".into(),
                    TYPE_DELAY_MS.into(),
                    "--".into(),
                    text.to_string(),
                ],
            )?;
        }
        self.observe(
            request,
            &screen,
            json!({ "typed": typed, "method": method }),
        )
    }

    pub(super) fn press_keys(&self, request: &Request) -> Result<Value, ExecError> {
        let screen = self.screen()?;
        let keys = request.required_str("keys")?;
        keys::validate(keys)?;
        xdotool(
            &screen,
            &["key".into(), "--clearmodifiers".into(), keys.to_string()],
        )?;
        self.observe(request, &screen, json!({ "keys": keys }))
    }

    pub(super) fn wait(&self, request: &Request) -> Result<Value, ExecError> {
        let screen = self.screen()?;
        let window = request
            .str_arg("untilWindow")
            .filter(|title| !title.is_empty());
        let log = match request.args.get("untilLog").filter(|log| !log.is_null()) {
            Some(log) => Some(self.log_condition(log)?),
            None => None,
        };
        let started = Instant::now();
        let met = if window.is_none() && log.is_none() {
            let wait = request.u64_arg("ms").ok_or_else(|| {
                ExecError::rejected("invalid_arguments", "Give ms, untilWindow or untilLog")
            })?;
            if wait > MAX_PLAIN_WAIT_MS {
                return Err(ExecError::rejected(
                    "invalid_arguments",
                    format!("A plain wait is at most {MAX_PLAIN_WAIT_MS} ms"),
                ));
            }
            std::thread::sleep(Duration::from_millis(wait));
            true
        } else {
            let limit = Duration::from_millis(
                request
                    .u64_arg("ms")
                    .unwrap_or(DEFAULT_CONDITION_WAIT_MS)
                    .min(MAX_CONDITION_WAIT_MS),
            );
            loop {
                let window_met = window.is_none_or(|title| window_visible(&screen, title));
                let log_met = log
                    .as_ref()
                    .is_none_or(|(path, text)| processes::log_tail_contains(path, text));
                if window_met && log_met {
                    break true;
                }
                if started.elapsed() >= limit {
                    break false;
                }
                std::thread::sleep(CONDITION_POLL);
            }
        };
        let waited = started.elapsed().as_millis() as u64;
        self.observe(request, &screen, json!({ "met": met, "waitedMs": waited }))
    }

    fn log_condition(&self, log: &Value) -> Result<(std::path::PathBuf, String), ExecError> {
        let process_id = log
            .get("processId")
            .and_then(Value::as_str)
            .unwrap_or_default();
        let text = log
            .get("text")
            .and_then(Value::as_str)
            .filter(|text| !text.is_empty())
            .ok_or_else(|| ExecError::rejected("invalid_arguments", "untilLog.text is required"))?;
        let path = processes::log_path(&self.runtime_dir, process_id).ok_or_else(|| {
            ExecError::failed(
                "process_not_found",
                format!("No process {process_id} was launched"),
            )
        })?;
        Ok((path, text.to_string()))
    }
}

fn xdotool(screen: &Screen, arguments: &[String]) -> Result<(), ExecError> {
    let mut command = system::tool(screen, "xdotool");
    command.args(arguments);
    system::run(command, "input_failed").map(|_| ())
}

fn paste(screen: &Screen, text: &str) -> Result<(), ExecError> {
    let mut clipboard = Command::new("xclip")
        .args(["-selection", "clipboard", "-in"])
        .envs(screen.environment())
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|error| ExecError::failed("input_failed", format!("xclip: {error}")))?;
    if let Some(mut input) = clipboard.stdin.take() {
        input
            .write_all(text.as_bytes())
            .map_err(|error| ExecError::failed("input_failed", error.to_string()))?;
    }
    clipboard
        .wait()
        .map_err(|error| ExecError::failed("input_failed", error.to_string()))?;
    xdotool(
        screen,
        &["key".into(), "--clearmodifiers".into(), "ctrl+v".into()],
    )
}

fn window_visible(screen: &Screen, title: &str) -> bool {
    let mut command = system::tool(screen, "xdotool");
    command.args([
        "search",
        "--onlyvisible",
        "--name",
        &case_insensitive_pattern(title),
    ]);
    system::succeeds(command)
}

pub fn case_insensitive_pattern(text: &str) -> String {
    let mut pattern = String::new();
    for character in text.chars() {
        let lower: Vec<char> = character.to_lowercase().collect();
        let upper: Vec<char> = character.to_uppercase().collect();
        if lower.len() == 1 && upper.len() == 1 && lower[0] != upper[0] {
            pattern.push('[');
            pattern.push(lower[0]);
            pattern.push(upper[0]);
            pattern.push(']');
        } else if ".[]{}()\\*+?|^$".contains(character) {
            pattern.push('\\');
            pattern.push(character);
        } else {
            pattern.push(character);
        }
    }
    pattern
}

fn point(
    request: &Request,
    screen: &Screen,
    x_name: &str,
    y_name: &str,
) -> Result<(u32, u32), ExecError> {
    let x = coordinate(request, x_name, screen.width)?;
    let y = coordinate(request, y_name, screen.height)?;
    Ok((x, y))
}

fn coordinate(request: &Request, name: &str, limit: u32) -> Result<u32, ExecError> {
    let value = request
        .args
        .get(name)
        .and_then(Value::as_f64)
        .filter(|value| value.is_finite())
        .ok_or_else(|| ExecError::rejected("invalid_coordinates", format!("{name} is required")))?;
    let rounded = value.round();
    if rounded < 0.0 || rounded >= f64::from(limit) {
        return Err(ExecError::rejected(
            "invalid_coordinates",
            format!(
                "{name} = {value} is outside the screen (0 to {})",
                limit - 1
            ),
        ));
    }
    Ok(rounded as u32)
}

fn button(request: &Request) -> Result<u8, ExecError> {
    match request.str_arg("button").unwrap_or("left") {
        "left" => Ok(1),
        "middle" => Ok(2),
        "right" => Ok(3),
        _ => Err(ExecError::rejected(
            "invalid_arguments",
            "button must be left, middle or right",
        )),
    }
}

fn modifiers(request: &Request) -> Result<Vec<String>, ExecError> {
    let Some(values) = request
        .args
        .get("modifiers")
        .filter(|value| !value.is_null())
    else {
        return Ok(Vec::new());
    };
    let values = values
        .as_array()
        .ok_or_else(|| ExecError::rejected("invalid_arguments", "modifiers must be a list"))?;
    values
        .iter()
        .map(|value| {
            value
                .as_str()
                .filter(|name| MODIFIERS.contains(name))
                .map(str::to_string)
                .ok_or_else(|| {
                    ExecError::rejected(
                        "invalid_arguments",
                        "modifiers may contain ctrl, shift, alt and super",
                    )
                })
        })
        .collect()
}

fn interpolate(from: u32, to: u32, step: u32) -> u32 {
    let from = f64::from(from);
    let to = f64::from(to);
    (from + (to - from) * f64::from(step) / f64::from(DRAG_STEPS)).round() as u32
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_case_insensitive_window_patterns() {
        assert_eq!(case_insensitive_pattern("Tk 1.0"), "[tT][kK] 1\\.0");
        assert_eq!(case_insensitive_pattern("Счёт"), "[сС][чЧ][ёЁ][тТ]");
    }

    #[test]
    fn interpolates_drag_steps_to_the_end_point() {
        assert_eq!(interpolate(0, 100, 1), 10);
        assert_eq!(interpolate(100, 0, 10), 0);
        assert_eq!(interpolate(5, 5, 4), 5);
    }
}
