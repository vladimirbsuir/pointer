use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use serde_json::{Value, json};

use super::{Fixture, apps, await_state, enabled};

const SCREEN_WIDTH: u64 = 1280;
const SCREEN_HEIGHT: u64 = 800;

struct Coverage<'a> {
    fixture: &'a str,
    toolkit: &'a str,
    expected: &'a [(&'a str, &'a str)],
}

impl Fixture {
    fn launch_ready(&self, command: &str, ready: &str) -> Value {
        let launched = self.ok("desktop_launch", json!({ "command": command }));
        let waited = self.ok(
            "desktop_wait",
            json!({ "untilLog": { "processId": launched["processId"], "text": ready }, "ms": 90_000, "observe": false }),
        );
        let log = launched["logPath"]
            .as_str()
            .and_then(|path| std::fs::read_to_string(path).ok())
            .unwrap_or_default();
        assert_eq!(waited["met"], true, "{command} did not start: {log}");
        std::thread::sleep(Duration::from_secs(1));
        launched
    }

    fn tree(&self, args: Value) -> Value {
        self.ok("desktop_tree", args)
    }

    fn timed_tree(&self) -> (Value, u128) {
        let started = Instant::now();
        let tree = self.tree(json!({}));
        (tree, started.elapsed().as_millis())
    }

    fn click_element(&self, node: &Value) -> Value {
        let clicked = self.ok(
            "desktop_click",
            json!({ "elementId": node["id"], "observe": false }),
        );
        assert_eq!(clicked["elementId"], node["id"]);
        assert!(inside(node, &clicked), "{clicked} lies outside {node}");
        clicked
    }

    fn await_tree(&self, role: &str, name: &str) -> (Value, Value) {
        let deadline = Instant::now() + Duration::from_secs(15);
        loop {
            let tree = self.tree(json!({}));
            if let Some(node) = search(&tree, role, name) {
                return (tree, node);
            }
            if Instant::now() > deadline {
                panic!("no {role} \"{name}\" in {}", describe(&tree));
            }
            std::thread::sleep(Duration::from_millis(300));
        }
    }
}

fn search(tree: &Value, role: &str, name: &str) -> Option<Value> {
    tree["nodes"]
        .as_array()?
        .iter()
        .find(|node| {
            node["role"]
                .as_str()
                .is_some_and(|found| found.contains(role))
                && node["name"] == name
        })
        .cloned()
}

fn find(tree: &Value, role: &str, name: &str) -> Value {
    search(tree, role, name).unwrap_or_else(|| panic!("no {role} \"{name}\" in {}", describe(tree)))
}

fn describe(tree: &Value) -> String {
    let nodes = tree["nodes"].as_array().cloned().unwrap_or_default();
    let mut lines = vec![format!(
        "scope {} applications {} note {}",
        tree["scope"], tree["applications"], tree["note"]
    )];
    for node in nodes {
        lines.push(format!(
            "{}{} {} {:?} text={} states={}",
            "  ".repeat(node["depth"].as_u64().unwrap_or(0) as usize),
            node["id"],
            node["role"],
            node["name"].as_str().unwrap_or_default(),
            node["text"],
            node["states"]
        ));
    }
    lines.join("\n")
}

fn inside(node: &Value, point: &Value) -> bool {
    let (x, y) = (point["x"].as_u64().unwrap(), point["y"].as_u64().unwrap());
    let left = node["x"].as_u64().unwrap();
    let top = node["y"].as_u64().unwrap();
    x >= left
        && y >= top
        && x < left + node["width"].as_u64().unwrap()
        && y < top + node["height"].as_u64().unwrap()
}

fn assert_on_screen(tree: &Value) {
    for node in tree["nodes"].as_array().unwrap() {
        let x = node["x"].as_u64().unwrap();
        let y = node["y"].as_u64().unwrap();
        let width = node["width"].as_u64().unwrap();
        let height = node["height"].as_u64().unwrap();
        assert!(
            width > 0 && height > 0 && x + width <= SCREEN_WIDTH && y + height <= SCREEN_HEIGHT,
            "{node}"
        );
    }
}

fn record(coverage: &Coverage, tree: &Value, millis: u128, clicked: bool) {
    let found: Vec<&str> = coverage
        .expected
        .iter()
        .filter(|(role, name)| search(tree, role, name).is_some())
        .map(|(_, name)| *name)
        .collect();
    let missing: Vec<&str> = coverage
        .expected
        .iter()
        .filter(|(role, name)| search(tree, role, name).is_none())
        .map(|(_, name)| *name)
        .collect();
    let report = json!({
        "fixture": coverage.fixture,
        "toolkit": coverage.toolkit,
        "applicationToolkit": tree["applications"][0]["toolkit"],
        "expected": coverage.expected.len(),
        "found": found.len(),
        "missing": missing,
        "nodes": tree["nodes"].as_array().map_or(0, Vec::len),
        "treeMs": millis,
        "clickById": clicked,
        "tree": describe(tree),
    });
    eprintln!("tree coverage {report}");
    if let Some(directory) = std::env::var_os("POINTER_DESKTOP_TREE_REPORT") {
        let directory = PathBuf::from(directory);
        std::fs::create_dir_all(&directory).unwrap();
        std::fs::write(
            directory.join(format!("{}.json", coverage.fixture)),
            serde_json::to_string_pretty(&report).unwrap(),
        )
        .unwrap();
    }
}

fn title_contains(fixture: &Fixture, window: &str, text: &str) -> bool {
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        if fixture.window_title(window).contains(text) {
            return true;
        }
        if Instant::now() > deadline {
            return false;
        }
        std::thread::sleep(Duration::from_millis(200));
    }
}

#[test]
fn tree_of_gtk_notes_has_its_controls_and_clicks_by_element_id() {
    if !enabled() {
        return;
    }
    let fixture = Fixture::new(":110");
    fixture.ok("desktop_start", json!({}));
    let check = fixture.root.path().join("gtk-notes.json");
    fixture.launch_ready(
        &format!(
            "python3 {} {}",
            apps().join("gtk-notes/app.py").display(),
            check.display()
        ),
        "gtk-notes ready",
    );
    let (tree, millis) = fixture.timed_tree();
    assert_eq!(tree["scope"], "focused", "{}", describe(&tree));
    assert_eq!(tree["truncated"], false);
    assert_on_screen(&tree);
    let window = find(&tree, "frame", "GTK Notes");
    assert_eq!(window["depth"], 0);
    let entry = find(&tree, "text", "Note text");
    let add = find(&tree, "button", "Add note");
    let pinned = find(&tree, "check box", "Pinned");
    let file = find(&tree, "menu", "File");

    let again = fixture.tree(json!({}));
    assert_eq!(
        again["nodes"], tree["nodes"],
        "the order and the ids are stable"
    );
    let limited = fixture.tree(json!({ "maxNodes": 3 }));
    assert_eq!(limited["nodes"].as_array().unwrap().len(), 3);
    assert_eq!(limited["truncated"], true);
    let shallow = fixture.tree(json!({ "maxDepth": 1 }));
    assert!(
        shallow["nodes"]
            .as_array()
            .unwrap()
            .iter()
            .all(|node| node["depth"] == 0)
    );
    assert_eq!(shallow["truncated"], true);

    fixture.click_element(&entry);
    fixture.ok("desktop_type", json!({ "text": "milk", "observe": false }));
    let clicked = fixture.click_element(&add);
    assert_eq!(clicked["name"], "Add note");
    assert!(clicked["role"].as_str().unwrap().contains("button"));
    await_state(&check, |state| state["notes"] == json!(["milk"]));
    fixture.click_element(&pinned);
    await_state(&check, |state| state["pinned"] == true);
    let checked = fixture.tree(json!({}));
    let pinned_now = find(&checked, "check box", "Pinned");
    assert_eq!(pinned_now["id"], pinned["id"]);
    assert!(
        pinned_now["states"]
            .as_array()
            .is_some_and(|states| states.contains(&json!("checked")))
    );

    fixture.click_element(&file);
    let (_, export) = fixture.await_tree("menu item", "Export…");
    fixture.click_element(&export);
    await_state(&check, |state| state["dialog"] == true);
    let (dialog_tree, cancel) = fixture.await_tree("button", "Cancel");
    assert!(
        search(&dialog_tree, "button", "Add note").is_none(),
        "the focused scope shows the dialog only: {}",
        describe(&dialog_tree)
    );
    let all = fixture.tree(json!({ "scope": "all" }));
    assert_eq!(all["scope"], "all");
    assert!(search(&all, "button", "Add note").is_some());
    fixture.click_element(&cancel);
    await_state(&check, |state| state["dialog"] == false);
    std::thread::sleep(Duration::from_millis(500));
    assert_eq!(
        fixture.error(
            "desktop_click",
            json!({ "elementId": cancel["id"], "observe": false })
        ),
        "element_not_found"
    );
    assert_eq!(
        fixture.error(
            "desktop_click",
            json!({ "elementId": "e999999", "observe": false })
        ),
        "element_not_found"
    );
    assert_eq!(
        fixture.error(
            "desktop_click",
            json!({ "elementId": add["id"], "x": 10, "y": 10 })
        ),
        "invalid_arguments"
    );
    assert_eq!(
        fixture.error("desktop_tree", json!({ "scope": "window" })),
        "invalid_arguments"
    );
    assert_eq!(
        fixture.error("desktop_tree", json!({ "maxNodes": 0 })),
        "invalid_arguments"
    );
    let shot = fixture.ok("desktop_click", json!({ "elementId": add["id"] }));
    assert_eq!(shot["images"].as_array().map(Vec::len), Some(1));

    record(
        &Coverage {
            fixture: "gtk-notes",
            toolkit: "GTK 3",
            expected: &[
                ("frame", "GTK Notes"),
                ("menu", "File"),
                ("text", "Note text"),
                ("button", "Add note"),
                ("check box", "Pinned"),
                ("list", "Notes"),
            ],
        },
        &tree,
        millis,
        true,
    );
}

#[test]
fn tree_of_tk_counter_is_empty_and_says_why() {
    if !enabled() {
        return;
    }
    let fixture = Fixture::new(":111");
    fixture.ok("desktop_start", json!({}));
    fixture.start_tk_counter();
    let (tree, millis) = fixture.timed_tree();
    assert_eq!(tree["nodes"], json!([]));
    assert!(
        tree["note"]
            .as_str()
            .is_some_and(|note| note.contains("Tk")),
        "{tree}"
    );
    record(
        &Coverage {
            fixture: "tk-counter",
            toolkit: "Tk",
            expected: &[("frame", "Tk Counter"), ("button", "Increment")],
        },
        &tree,
        millis,
        false,
    );
}

#[test]
fn tree_of_a_web_page_in_chromium_reaches_the_form() {
    if !enabled() {
        return;
    }
    let fixture = Fixture::new(":112");
    fixture.ok("desktop_start", json!({}));
    let page = apps().join("form-web/index.html").canonicalize().unwrap();
    let profile = fixture.root.path().join("profile");
    fixture.ok(
        "desktop_launch",
        json!({ "command": format!("pointer-chromium --user-data-dir={} --app=file://{}", profile.display(), page.display()) }),
    );
    let opened = fixture.ok(
        "desktop_wait",
        json!({ "untilWindow": "form-web", "ms": 30_000, "observe": false }),
    );
    assert_eq!(opened["met"], true);
    std::thread::sleep(Duration::from_secs(2));
    let (tree, millis) = fixture.timed_tree();
    assert_on_screen(&tree);
    let entry = tree["nodes"]
        .as_array()
        .unwrap()
        .iter()
        .find(|node| node["role"] == "entry")
        .cloned()
        .unwrap_or_else(|| panic!("no entry in {}", describe(&tree)));
    for item in ["A", "B", "C", "D"] {
        assert!(
            tree["nodes"].as_array().unwrap().iter().any(|node| {
                node["role"] == "list item" && (node["text"] == item || node["name"] == item)
            }),
            "no list item {item} in {}",
            describe(&tree)
        );
    }
    fixture.click_element(&entry);
    fixture.ok("desktop_type", json!({ "text": "tree", "observe": false }));
    let typed = title_contains(&fixture, "form-web", "\"text\":\"tree\"");
    assert!(typed, "{}", fixture.window_title("form-web"));
    record(
        &Coverage {
            fixture: "form-web",
            toolkit: "Chromium",
            expected: &[
                ("document", "form-web {\"order\":\"ABCD\",\"text\":\"\",\"users\":[],\"dialog\":false}"),
                ("list item", ""),
                ("entry", ""),
            ],
        },
        &tree,
        millis,
        typed,
    );
}

#[test]
fn tree_of_swing_calc_comes_through_the_java_atk_wrapper() {
    if !enabled() || !Path::new("/usr/share/java/java-atk-wrapper.jar").is_file() {
        return;
    }
    let fixture = Fixture::new(":113");
    fixture.ok("desktop_start", json!({}));
    let check = fixture.root.path().join("swing-calc.json");
    fixture.launch_ready(
        &format!(
            "java {} {}",
            apps().join("swing-calc/SwingCalc.java").display(),
            check.display()
        ),
        "swing-calc ready",
    );
    fixture.await_tree("button", "Plus");
    let (tree, millis) = fixture.timed_tree();
    assert_on_screen(&tree);
    fixture.click_element(&find(&tree, "button", "Plus"));
    let clicked = await_state(&check, |state| state["clicks"] == 1);
    assert_eq!(clicked["result"], "5");
    record(
        &Coverage {
            fixture: "swing-calc",
            toolkit: "Java Swing",
            expected: &[
                ("frame", "Swing Calc"),
                ("text", "First number"),
                ("text", "Second number"),
                ("button", "Plus"),
                ("label", "Result: none"),
            ],
        },
        &tree,
        millis,
        true,
    );
}

#[test]
fn tree_of_qt_settings_has_tabs_and_inputs() {
    let Some(python) = std::env::var_os("POINTER_QT_PYTHON") else {
        return;
    };
    if !enabled() {
        return;
    }
    let fixture = Fixture::new(":114");
    fixture.ok("desktop_start", json!({}));
    let check = fixture.root.path().join("qt-settings.json");
    fixture.launch_ready(
        &format!(
            "{} {} {}",
            Path::new(&python).display(),
            apps().join("qt-settings/app.py").display(),
            check.display()
        ),
        "qt-settings ready",
    );
    let (tree, millis) = fixture.timed_tree();
    assert_on_screen(&tree);
    fixture.click_element(&find(&tree, "check box", "Dark mode"));
    fixture.click_element(&find(&tree, "button", "Apply"));
    let applied = await_state(&check, |state| state["applied"] == 1);
    assert_eq!(applied["dark"], true);
    fixture.click_element(&find(&tree, "page tab", "Advanced"));
    await_state(&check, |state| state["tab"] == 1);
    let (_, proxy) = fixture.await_tree("text", "Proxy");
    fixture.click_element(&proxy);
    record(
        &Coverage {
            fixture: "qt-settings",
            toolkit: "Qt 6",
            expected: &[
                ("page tab", "General"),
                ("page tab", "Advanced"),
                ("check box", "Dark mode"),
                ("spin button", "Font size"),
                ("button", "Apply"),
            ],
        },
        &tree,
        millis,
        true,
    );
}

#[test]
fn tree_of_electron_todo_needs_renderer_accessibility() {
    let Some(directory) = std::env::var_os("POINTER_ELECTRON_TODO") else {
        return;
    };
    if !enabled() {
        return;
    }
    let directory = PathBuf::from(directory);
    let electron = directory.join("node_modules/.bin/electron");
    let fixture = Fixture::new(":115");
    fixture.ok("desktop_start", json!({}));
    let plain = fixture.launch_ready(
        &format!("{} {}", electron.display(), directory.display()),
        "electron-todo ready",
    );
    let frame_only = fixture.tree(json!({}));
    assert!(
        frame_only["note"]
            .as_str()
            .is_some_and(|note| note.contains("--force-renderer-accessibility")),
        "{}",
        describe(&frame_only)
    );
    fixture.ok("desktop_stop", json!({ "processId": plain["processId"] }));
    fixture.launch_ready(
        &format!(
            "{} --force-renderer-accessibility {}",
            electron.display(),
            directory.display()
        ),
        "electron-todo ready",
    );
    let (tree, millis) = fixture.timed_tree();
    assert!(tree.get("note").is_none(), "{}", describe(&tree));
    assert_on_screen(&tree);
    fixture.click_element(&find(&tree, "entry", "New todo"));
    fixture.ok("desktop_type", json!({ "text": "milk", "observe": false }));
    fixture.click_element(&find(&tree, "button", "Add"));
    let added = title_contains(&fixture, "electron-todo", "\"todos\":[\"milk\"]");
    assert!(added, "{}", fixture.window_title("electron-todo"));
    record(
        &Coverage {
            fixture: "electron-todo",
            toolkit: "Electron",
            expected: &[
                ("entry", "New todo"),
                ("button", "Add"),
                ("check box", "Hide done"),
            ],
        },
        &tree,
        millis,
        added,
    );
}

#[test]
fn tree_of_a_webkit_window_reaches_the_page() {
    let Some(binary) = std::env::var_os("POINTER_WEBKIT_COUNTER") else {
        return;
    };
    if !enabled() {
        return;
    }
    let fixture = Fixture::new(":116");
    fixture.ok("desktop_start", json!({}));
    fixture.ok(
        "desktop_launch",
        json!({ "command": Path::new(&binary).display().to_string() }),
    );
    let opened = fixture.ok(
        "desktop_wait",
        json!({ "untilWindow": "webkit-counter 0", "ms": 60_000, "observe": false }),
    );
    assert_eq!(opened["met"], true);
    std::thread::sleep(Duration::from_secs(1));
    fixture.await_tree("button", "Increment");
    let (tree, millis) = fixture.timed_tree();
    fixture.click_element(&find(&tree, "button", "Increment"));
    let clicked = title_contains(&fixture, "webkit-counter", "webkit-counter 1");
    assert!(clicked);
    record(
        &Coverage {
            fixture: "webkit-counter",
            toolkit: "WebKitGTK (Tauri)",
            expected: &[("frame", "webkit-counter 0"), ("button", "Increment")],
        },
        &tree,
        millis,
        clicked,
    );
}
