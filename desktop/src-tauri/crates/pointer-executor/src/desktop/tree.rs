use std::collections::{HashMap, HashSet};
use std::fs;
use std::path::Path;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use serde_json::{Map, Value, json};

use super::{Desktop, Screen};
use crate::{ExecError, Request};

const READER: &str = include_str!("atspi.py");
const READER_TIMEOUT: Duration = Duration::from_secs(25);
const POLL_INTERVAL: Duration = Duration::from_millis(20);
const DEFAULT_MAX_NODES: u64 = 150;
const MAX_NODES: u64 = 500;
const DEFAULT_MAX_DEPTH: u64 = 30;
const MAX_DEPTH: u64 = 60;
const MAX_REMEMBERED_ELEMENTS: usize = 20_000;
const CHROMIUM_TOOLKIT: &str = "Chromium";

#[derive(Clone, PartialEq, Eq, Hash)]
pub struct ElementKey {
    pid: u64,
    application: String,
    path: Vec<u64>,
    role: String,
    name: String,
}

#[derive(Default)]
pub struct Elements {
    ids: HashMap<ElementKey, u64>,
    keys: HashMap<u64, ElementKey>,
    next: u64,
}

impl Elements {
    pub fn forget(&mut self) {
        self.ids.clear();
        self.keys.clear();
    }

    fn id_for(&mut self, key: ElementKey) -> String {
        if let Some(id) = self.ids.get(&key) {
            return format!("e{id}");
        }
        if self.ids.len() >= MAX_REMEMBERED_ELEMENTS {
            self.forget();
        }
        self.next += 1;
        self.ids.insert(key.clone(), self.next);
        self.keys.insert(self.next, key);
        format!("e{}", self.next)
    }

    fn key(&self, id: &str) -> Option<ElementKey> {
        let number = id.strip_prefix('e')?.parse::<u64>().ok()?;
        self.keys.get(&number).cloned()
    }
}

pub struct Element {
    pub id: String,
    pub role: String,
    pub name: String,
    pub x: u32,
    pub y: u32,
}

impl Desktop {
    pub(super) fn tree(&self, request: &Request) -> Result<Value, ExecError> {
        let screen = self.screen()?;
        let max_nodes = bounded(request, "maxNodes", DEFAULT_MAX_NODES, MAX_NODES)?;
        let max_depth = bounded(request, "maxDepth", DEFAULT_MAX_DEPTH, MAX_DEPTH)?;
        let scope = match request.str_arg("scope").unwrap_or("focused") {
            scope @ ("focused" | "all") => scope,
            _ => {
                return Err(ExecError::rejected(
                    "invalid_arguments",
                    "scope must be focused or all",
                ));
            }
        };
        let read = run_reader(
            &screen,
            &self.runtime_dir,
            "tree",
            json!({
                "screenWidth": screen.width,
                "screenHeight": screen.height,
                "maxNodes": max_nodes,
                "maxDepth": max_depth,
                "scope": scope,
            }),
        )?;
        let raw_nodes = read["nodes"].as_array().cloned().unwrap_or_default();
        let mut nodes = Vec::with_capacity(raw_nodes.len());
        {
            let mut state = self.lock();
            for raw in &raw_nodes {
                if let Some(key) = element_key(raw) {
                    nodes.push(public_node(state.elements.id_for(key), raw));
                }
            }
        }
        let applications = read["applications"].as_array().cloned().unwrap_or_default();
        let mut result = json!({
            "scope": read["scope"],
            "applications": applications
                .iter()
                .map(|application| json!({
                    "name": application["name"],
                    "toolkit": application["toolkit"],
                }))
                .collect::<Vec<_>>(),
            "nodes": nodes,
            "truncated": read["truncated"].as_bool().unwrap_or(false),
        });
        if let Some(note) = note(&applications, &raw_nodes) {
            result["note"] = Value::String(note);
        }
        Ok(result)
    }

    pub(super) fn element(&self, screen: &Screen, id: &str) -> Result<Element, ExecError> {
        let key = self.lock().elements.key(id).ok_or_else(|| {
            ExecError::failed(
                "element_not_found",
                format!("{id} is not an element of a desktop_tree result; call desktop_tree again"),
            )
        })?;
        let resolved = run_reader(
            screen,
            &self.runtime_dir,
            "resolve",
            json!({
                "screenWidth": screen.width,
                "screenHeight": screen.height,
                "pid": key.pid,
                "application": key.application,
                "path": key.path,
                "role": key.role,
                "name": key.name,
            }),
        )?;
        if resolved["found"].as_bool() != Some(true) {
            let reason = match resolved["reason"].as_str() {
                Some("offscreen") => "is outside the screen; scroll it into view",
                Some("hidden") => "is hidden",
                _ => "is no longer there",
            };
            return Err(ExecError::failed(
                "element_not_found",
                format!(
                    "{id} ({} \"{}\") {reason}; call desktop_tree again",
                    key.role, key.name
                ),
            ));
        }
        let center = |start: &Value, length: &Value, limit: u32| {
            let start = start.as_u64().unwrap_or(0);
            let length = length.as_u64().unwrap_or(1).max(1);
            u32::try_from(start + length / 2)
                .unwrap_or(u32::MAX)
                .min(limit.saturating_sub(1))
        };
        Ok(Element {
            id: id.to_string(),
            role: key.role,
            name: key.name,
            x: center(&resolved["x"], &resolved["width"], screen.width),
            y: center(&resolved["y"], &resolved["height"], screen.height),
        })
    }
}

fn bounded(request: &Request, name: &str, default: u64, max: u64) -> Result<u64, ExecError> {
    match request.args.get(name) {
        None | Some(Value::Null) => Ok(default),
        Some(value) => value
            .as_u64()
            .filter(|value| (1..=max).contains(value))
            .ok_or_else(|| {
                ExecError::rejected(
                    "invalid_arguments",
                    format!("{name} must be between 1 and {max}"),
                )
            }),
    }
}

fn element_key(raw: &Value) -> Option<ElementKey> {
    Some(ElementKey {
        pid: raw["pid"].as_u64()?,
        application: raw["application"].as_str()?.to_string(),
        path: raw["path"]
            .as_array()?
            .iter()
            .map(Value::as_u64)
            .collect::<Option<Vec<_>>>()?,
        role: raw["role"].as_str()?.to_string(),
        name: raw["name"].as_str()?.to_string(),
    })
}

fn public_node(id: String, raw: &Value) -> Value {
    let mut node = Map::new();
    node.insert("id".into(), Value::String(id));
    for field in [
        "depth", "role", "name", "text", "value", "states", "x", "y", "width", "height",
    ] {
        if let Some(value) = raw.get(field).filter(|value| !value.is_null()) {
            node.insert(field.into(), value.clone());
        }
    }
    Value::Object(node)
}

fn note(applications: &[Value], nodes: &[Value]) -> Option<String> {
    if nodes.is_empty() {
        return Some(if applications.is_empty() {
            "No application on the display exposes an accessibility tree. Tk, plain X11 and some \
             other toolkits have none: use coordinates from the screenshot."
                .to_string()
        } else {
            "The window exposes no accessible elements: use coordinates from the screenshot, or \
             desktop_tree with scope all to see the other windows."
                .to_string()
        });
    }
    let pids: HashSet<u64> = nodes
        .iter()
        .filter_map(|node| node["pid"].as_u64())
        .collect();
    let frame_only: Vec<&str> = applications
        .iter()
        .filter(|application| application["toolkit"].as_str() == Some(CHROMIUM_TOOLKIT))
        .filter(|application| {
            application["pid"]
                .as_u64()
                .is_some_and(|pid| pids.contains(&pid))
        })
        .filter(|application| {
            let pid = application["pid"].as_u64();
            !nodes.iter().any(|node| {
                node["pid"].as_u64() == pid
                    && node["role"]
                        .as_str()
                        .is_some_and(|role| role.starts_with("document"))
            })
        })
        .filter_map(|application| application["name"].as_str())
        .collect();
    if frame_only.is_empty() {
        return None;
    }
    Some(format!(
        "{} is Chromium-based (Electron or a Chromium browser) and exposes only its window \
         frame: start it with --force-renderer-accessibility to read its content. \
         pointer-chromium already does.",
        frame_only.join(", ")
    ))
}

fn run_reader(
    screen: &Screen,
    runtime_dir: &Path,
    command: &str,
    arguments: Value,
) -> Result<Value, ExecError> {
    let failed = |message: String| ExecError::failed("tree_failed", message);
    fs::create_dir_all(runtime_dir).map_err(|error| failed(error.to_string()))?;
    let script = runtime_dir.join("atspi.py");
    if fs::read_to_string(&script).ok().as_deref() != Some(READER) {
        fs::write(&script, READER).map_err(|error| failed(error.to_string()))?;
    }
    let run = uuid::Uuid::now_v7();
    let output_path = runtime_dir.join(format!("atspi-{run}.json"));
    let error_path = runtime_dir.join(format!("atspi-{run}.err"));
    let output = fs::File::create(&output_path).map_err(|error| failed(error.to_string()))?;
    let errors = fs::File::create(&error_path).map_err(|error| failed(error.to_string()))?;
    let spawned = Command::new("python3")
        .arg("-I")
        .arg(&script)
        .arg(command)
        .arg(arguments.to_string())
        .envs(screen.environment())
        .env("LC_ALL", "C.UTF-8")
        .stdin(Stdio::null())
        .stdout(output)
        .stderr(errors)
        .spawn();
    let outcome = spawned
        .map_err(|error| {
            ExecError::failed(
                "tree_unavailable",
                format!("python3 did not start: {error}"),
            )
        })
        .and_then(|mut child| {
            let deadline = Instant::now() + READER_TIMEOUT;
            loop {
                match child.try_wait() {
                    Ok(Some(status)) => break Ok(status),
                    Ok(None) if Instant::now() > deadline => {
                        let _ = child.kill();
                        let _ = child.wait();
                        break Err(failed(format!(
                            "The accessibility reader did not answer in {} seconds",
                            READER_TIMEOUT.as_secs()
                        )));
                    }
                    Ok(None) => std::thread::sleep(POLL_INTERVAL),
                    Err(error) => break Err(failed(error.to_string())),
                }
            }
        })
        .and_then(|status| {
            let text = fs::read_to_string(&output_path).unwrap_or_default();
            let parsed: Option<Value> = serde_json::from_str(text.trim()).ok();
            match parsed {
                Some(value) if status.success() => match value["error"].as_str() {
                    Some(code) => Err(ExecError::failed(
                        code.to_string(),
                        value["message"].as_str().unwrap_or_default().to_string(),
                    )),
                    None => Ok(value),
                },
                _ => {
                    let errors = fs::read_to_string(&error_path).unwrap_or_default();
                    let last = errors.trim().lines().last().unwrap_or("no output");
                    Err(failed(format!("The accessibility reader failed: {last}")))
                }
            }
        });
    let _ = fs::remove_file(&output_path);
    let _ = fs::remove_file(&error_path);
    outcome
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key(name: &str) -> ElementKey {
        ElementKey {
            pid: 7,
            application: "app".into(),
            path: vec![0, 1],
            role: "push button".into(),
            name: name.into(),
        }
    }

    #[test]
    fn keeps_the_id_of_an_element_across_trees() {
        let mut elements = Elements::default();
        let first = elements.id_for(key("Save"));
        let other = elements.id_for(key("Open"));
        assert_eq!(elements.id_for(key("Save")), first);
        assert_ne!(first, other);
        assert!(
            elements
                .key(&first)
                .is_some_and(|found| found.name == "Save")
        );
        assert!(elements.key("e999").is_none());
        assert!(elements.key("save").is_none());
    }

    #[test]
    fn forgotten_ids_are_not_reused() {
        let mut elements = Elements::default();
        let first = elements.id_for(key("Save"));
        elements.forget();
        assert!(elements.key(&first).is_none());
        assert_ne!(elements.id_for(key("Save")), first);
    }

    #[test]
    fn notes_an_empty_tree_and_a_chromium_frame_without_content() {
        let none = note(&[], &[]).unwrap();
        assert!(none.contains("Tk"));
        let applications = [json!({ "name": "electron", "toolkit": "Chromium", "pid": 5 })];
        let frame = [json!({ "pid": 5, "role": "frame", "name": "Todo" })];
        assert!(
            note(&applications, &frame)
                .unwrap()
                .contains("--force-renderer-accessibility")
        );
        let page = [
            json!({ "pid": 5, "role": "frame", "name": "Todo" }),
            json!({ "pid": 5, "role": "document web", "name": "Todo" }),
        ];
        assert!(note(&applications, &page).is_none());
    }
}
