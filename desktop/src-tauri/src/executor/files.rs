use std::fs;
use std::path::{Path, PathBuf};

use serde_json::{Value, json};
use similar::{ChangeTag, TextDiff};

use super::paths::{relative, resolve};
use super::{ExecError, ExecOutcome, Request};

const DEFAULT_READ_LINES: usize = 2000;
const MAX_READ_BYTES: usize = 256 * 1024;
const MAX_LIST_ENTRIES: usize = 1000;
const MAX_RULES_BYTES: usize = 50 * 1024;
const MAX_ATTACHMENT_BYTES: u64 = 200 * 1024;
const RULES_FILES: [&str; 2] = ["AGENTS.md", "CLAUDE.md"];

pub fn list_directory(root: &Path, request: &Request) -> Result<ExecOutcome, ExecError> {
    let directory = resolve(root, request.str_arg("path").unwrap_or("."))?;
    let mut entries = fs::read_dir(&directory)
        .map_err(|error| ExecError::failed("directory_not_found", error.to_string()))?
        .filter_map(Result::ok)
        .filter(|entry| entry.file_name() != ".git")
        .map(|entry| {
            let metadata = entry.metadata().ok();
            let is_dir = metadata.as_ref().is_some_and(|metadata| metadata.is_dir());
            json!({
                "name": entry.file_name().to_string_lossy(),
                "kind": if is_dir { "directory" } else { "file" },
                "size": if is_dir { None } else { metadata.map(|metadata| metadata.len()) },
            })
        })
        .collect::<Vec<_>>();
    entries.sort_by_key(|entry| (entry["kind"] != "directory", entry["name"].to_string()));
    let truncated = entries.len() > MAX_LIST_ENTRIES;
    entries.truncate(MAX_LIST_ENTRIES);
    Ok(ExecOutcome::result(
        json!({ "entries": entries, "truncated": truncated }),
    ))
}

pub fn read_file(root: &Path, request: &Request) -> Result<ExecOutcome, ExecError> {
    let path = resolve(root, request.required_str("path")?)?;
    let content = read_text(&path)?;
    let lines = content.lines().collect::<Vec<_>>();
    let offset = request.u64_arg("offset").unwrap_or(1).max(1) as usize;
    let limit = request
        .u64_arg("limit")
        .map(|limit| limit as usize)
        .unwrap_or(DEFAULT_READ_LINES);
    let mut selected = String::new();
    let mut truncated = false;
    for (index, line) in lines.iter().enumerate().skip(offset - 1).take(limit) {
        let numbered = format!("{:>6}\t{}\n", index + 1, line);
        if selected.len() + numbered.len() > MAX_READ_BYTES {
            truncated = true;
            break;
        }
        selected.push_str(&numbered);
    }
    truncated |= offset - 1 + limit < lines.len();
    Ok(ExecOutcome::result(json!({
        "content": selected,
        "totalLines": lines.len(),
        "truncated": truncated,
    })))
}

pub fn write_file(root: &Path, request: &Request) -> Result<ExecOutcome, ExecError> {
    let requested = request.required_str("path")?;
    let path = resolve(root, requested)?;
    let content = request.required_str("content")?;
    let before = if path.exists() {
        Some(read_text(&path)?)
    } else {
        None
    };
    apply_change(root, &path, before, content.to_string(), request.dry_run)
}

pub fn edit_file(root: &Path, request: &Request) -> Result<ExecOutcome, ExecError> {
    let path = resolve(root, request.required_str("path")?)?;
    let before = read_text(&path)?;
    let old_text = request.required_str("oldText")?;
    let new_text = request.required_str("newText")?;
    let replace_all = request.bool_arg("replaceAll").unwrap_or(false);
    if old_text.is_empty() {
        return Err(ExecError::failed(
            "invalid_arguments",
            "oldText must not be empty. To append, use the last lines of the file as oldText and repeat them at the start of newText.",
        ));
    }
    let (old_text, new_text) = if !before.contains(old_text) && before.contains("\r\n") {
        (
            old_text.replace("\r\n", "\n").replace('\n', "\r\n"),
            new_text.replace("\r\n", "\n").replace('\n', "\r\n"),
        )
    } else {
        (old_text.to_string(), new_text.to_string())
    };
    let (old_text, new_text) = if !before.contains(&old_text)
        && !old_text.trim_end().is_empty()
        && before.contains(old_text.trim_end())
    {
        (
            old_text.trim_end().to_string(),
            new_text.trim_end().to_string(),
        )
    } else {
        (old_text, new_text)
    };
    let occurrences = before.matches(&old_text).count();
    if occurrences == 0 {
        return Err(ExecError::failed(
            "text_not_found",
            "oldText was not found in the file. Read the file again and copy the text exactly.",
        ));
    }
    if occurrences > 1 && !replace_all {
        return Err(ExecError::failed(
            "text_not_unique",
            format!("oldText occurs {occurrences} times. Include more context or set replaceAll."),
        ));
    }
    let after = if replace_all {
        before.replace(&old_text, &new_text)
    } else {
        before.replacen(&old_text, &new_text, 1)
    };
    let mut outcome = apply_change(root, &path, Some(before), after, request.dry_run)?;
    if !request.dry_run {
        outcome.result["replacements"] = json!(if replace_all { occurrences } else { 1 });
    }
    Ok(outcome)
}

pub fn restore_file(root: &Path, request: &Request) -> Result<ExecOutcome, ExecError> {
    let path = resolve(root, request.required_str("path")?)?;
    let current = if path.exists() {
        Some(read_text(&path)?)
    } else {
        None
    };
    let expected = request.args.get("expectedContent").and_then(Value::as_str);
    let force = request
        .args
        .get("force")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    if !force && current.as_deref() != expected {
        return Err(ExecError::failed(
            "file_modified",
            "The file changed after the agent edited it",
        ));
    }
    if request.dry_run {
        return Ok(ExecOutcome::result(json!({ "restored": false })));
    }
    match request.args.get("content").and_then(Value::as_str) {
        Some(content) => write_text(&path, content)?,
        None => {
            if path.exists() {
                fs::remove_file(&path)
                    .map_err(|error| ExecError::failed("io_error", error.to_string()))?;
            }
        }
    }
    Ok(ExecOutcome::result(json!({ "restored": true })))
}

pub fn project_context(root: &Path) -> Result<ExecOutcome, ExecError> {
    let rules = RULES_FILES.iter().find_map(|name| {
        let content = fs::read_to_string(root.join(name)).ok()?;
        let content = if content.len() > MAX_RULES_BYTES {
            content
                .char_indices()
                .take_while(|(index, _)| *index < MAX_RULES_BYTES)
                .map(|(_, char)| char)
                .collect()
        } else {
            content
        };
        Some(json!({ "name": name, "content": content }))
    });
    let head = fs::read_to_string(git_dir(root).join("HEAD")).ok();
    let branch = head
        .as_deref()
        .and_then(|head| head.trim().strip_prefix("ref: refs/heads/"))
        .map(str::to_string);
    Ok(ExecOutcome::result(json!({
        "rulesFile": rules,
        "gitBranch": branch,
        "isGitRepository": head.is_some(),
    })))
}

fn git_dir(root: &Path) -> PathBuf {
    let dot_git = root.join(".git");
    fs::read_to_string(&dot_git)
        .ok()
        .and_then(|link| {
            link.trim()
                .strip_prefix("gitdir:")
                .map(|dir| root.join(dir.trim()))
        })
        .unwrap_or(dot_git)
}

fn apply_change(
    root: &Path,
    path: &Path,
    before: Option<String>,
    after: String,
    dry_run: bool,
) -> Result<ExecOutcome, ExecError> {
    let display_path = relative(root, path);
    let old = before.clone().unwrap_or_default();
    let diff = TextDiff::from_lines(&old, &after);
    let (added, removed) = diff
        .iter_all_changes()
        .fold((0, 0), |(added, removed), change| match change.tag() {
            ChangeTag::Insert => (added + 1, removed),
            ChangeTag::Delete => (added, removed + 1),
            ChangeTag::Equal => (added, removed),
        });
    if dry_run {
        let unified = diff
            .unified_diff()
            .context_radius(3)
            .header(&display_path, &display_path)
            .to_string();
        return Ok(ExecOutcome::result(json!({
            "preview": {
                "path": display_path,
                "unifiedDiff": unified,
                "linesAdded": added,
                "linesRemoved": removed,
                "created": before.is_none(),
            }
        })));
    }
    write_text(path, &after)?;
    let change_kind = if before.is_none() {
        "created"
    } else {
        "modified"
    };
    let mut outcome = ExecOutcome::result(json!({ "created": before.is_none() }));
    outcome.file_changes.push(json!({
        "path": display_path,
        "changeKind": change_kind,
        "before": before,
        "after": after,
        "linesAdded": added,
        "linesRemoved": removed,
    }));
    Ok(outcome)
}

pub fn read_attachment(root: &Path, requested: &str) -> Result<String, ExecError> {
    let path = resolve(root, requested)?;
    let size = fs::metadata(&path)
        .map(|metadata| metadata.len())
        .unwrap_or(0);
    if size > MAX_ATTACHMENT_BYTES {
        return Err(ExecError::failed(
            "file_too_large",
            "The file is too large to attach",
        ));
    }
    read_text(&path)
}

fn read_text(path: &Path) -> Result<String, ExecError> {
    let bytes = fs::read(path).map_err(|error| {
        if error.kind() == std::io::ErrorKind::NotFound {
            ExecError::failed("file_not_found", "The file does not exist")
        } else {
            ExecError::failed("io_error", error.to_string())
        }
    })?;
    if bytes.iter().take(8192).any(|byte| *byte == 0) {
        return Err(ExecError::failed(
            "binary_file",
            "The file looks binary and cannot be read as text",
        ));
    }
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

fn write_text(path: &Path, content: &str) -> Result<(), ExecError> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|error| ExecError::failed("io_error", error.to_string()))?;
    }
    fs::write(path, content).map_err(|error| ExecError::failed("io_error", error.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn request(args: Value, dry_run: bool) -> Request {
        Request {
            operation: String::new(),
            args,
            dry_run,
            timeout_ms: 30_000,
        }
    }

    #[test]
    fn edit_replaces_unique_text_and_reports_change() {
        let root = tempfile::tempdir().unwrap();
        fs::write(root.path().join("a.txt"), "one\ntwo\n").unwrap();

        let outcome = edit_file(
            root.path(),
            &request(
                json!({"path": "a.txt", "oldText": "two", "newText": "three"}),
                false,
            ),
        )
        .unwrap();

        assert_eq!(
            fs::read_to_string(root.path().join("a.txt")).unwrap(),
            "one\nthree\n"
        );
        assert_eq!(outcome.file_changes[0]["linesAdded"], 1);
        assert_eq!(outcome.file_changes[0]["linesRemoved"], 1);
    }

    #[test]
    fn edit_dry_run_returns_diff_without_writing() {
        let root = tempfile::tempdir().unwrap();
        fs::write(root.path().join("a.txt"), "one\n").unwrap();

        let outcome = edit_file(
            root.path(),
            &request(
                json!({"path": "a.txt", "oldText": "one", "newText": "uno"}),
                true,
            ),
        )
        .unwrap();

        assert_eq!(
            fs::read_to_string(root.path().join("a.txt")).unwrap(),
            "one\n"
        );
        let diff = outcome.result["preview"]["unifiedDiff"].as_str().unwrap();
        assert!(diff.contains("-one") && diff.contains("+uno"));
    }

    #[test]
    fn edit_matches_lf_text_in_crlf_file() {
        let root = tempfile::tempdir().unwrap();
        fs::write(root.path().join("a.txt"), "a\r\nb\r\nc\r\n").unwrap();

        edit_file(
            root.path(),
            &request(
                json!({"path": "a.txt", "oldText": "a\nb", "newText": "x\ny"}),
                false,
            ),
        )
        .unwrap();

        assert_eq!(
            fs::read_to_string(root.path().join("a.txt")).unwrap(),
            "x\r\ny\r\nc\r\n"
        );
    }

    #[test]
    fn edit_tolerates_extra_trailing_newlines_in_old_text() {
        let root = tempfile::tempdir().unwrap();
        fs::write(root.path().join("a.py"), "def a():\n    return 1\n").unwrap();

        edit_file(
            root.path(),
            &request(
                json!({"path": "a.py", "oldText": "    return 1\n\n", "newText": "    return 1\n\n\ndef b():\n    return 2\n"}),
                false,
            ),
        )
        .unwrap();

        assert_eq!(
            fs::read_to_string(root.path().join("a.py")).unwrap(),
            "def a():\n    return 1\n\n\ndef b():\n    return 2\n"
        );
    }

    #[test]
    fn edit_rejects_ambiguous_and_missing_text() {
        let root = tempfile::tempdir().unwrap();
        fs::write(root.path().join("a.txt"), "x x").unwrap();

        let ambiguous = edit_file(
            root.path(),
            &request(
                json!({"path": "a.txt", "oldText": "x", "newText": "y"}),
                false,
            ),
        );
        let missing = edit_file(
            root.path(),
            &request(
                json!({"path": "a.txt", "oldText": "z", "newText": "y"}),
                false,
            ),
        );

        assert_eq!(ambiguous.unwrap_err().code, "text_not_unique");
        assert_eq!(missing.unwrap_err().code, "text_not_found");
    }

    #[test]
    fn write_creates_nested_file() {
        let root = tempfile::tempdir().unwrap();

        let outcome = write_file(
            root.path(),
            &request(
                json!({"path": "src/new.rs", "content": "fn main() {}\n"}),
                false,
            ),
        )
        .unwrap();

        assert!(root.path().join("src/new.rs").exists());
        assert_eq!(outcome.file_changes[0]["changeKind"], "created");
    }

    #[test]
    fn read_file_numbers_lines_and_respects_range() {
        let root = tempfile::tempdir().unwrap();
        fs::write(root.path().join("a.txt"), "1\n2\n3\n4\n").unwrap();

        let outcome = read_file(
            root.path(),
            &request(json!({"path": "a.txt", "offset": 2, "limit": 2}), false),
        )
        .unwrap();

        assert_eq!(outcome.result["content"], "     2\t2\n     3\t3\n");
        assert_eq!(outcome.result["totalLines"], 4);
        assert_eq!(outcome.result["truncated"], true);
    }

    #[test]
    fn project_context_reads_rules_and_branch() {
        let root = tempfile::tempdir().unwrap();
        fs::write(root.path().join("AGENTS.md"), "No comments").unwrap();
        fs::create_dir(root.path().join(".git")).unwrap();
        fs::write(root.path().join(".git/HEAD"), "ref: refs/heads/main\n").unwrap();

        let outcome = project_context(root.path()).unwrap();

        assert_eq!(outcome.result["rulesFile"]["name"], "AGENTS.md");
        assert_eq!(outcome.result["gitBranch"], "main");
    }

    #[test]
    fn project_context_follows_worktree_git_file() {
        let root = tempfile::tempdir().unwrap();
        let git = tempfile::tempdir().unwrap();
        fs::write(
            git.path().join("HEAD"),
            "ref: refs/heads/pointer/abc
",
        )
        .unwrap();
        fs::write(
            root.path().join(".git"),
            format!(
                "gitdir: {}
",
                git.path().display()
            ),
        )
        .unwrap();

        let outcome = project_context(root.path()).unwrap();

        assert_eq!(outcome.result["gitBranch"], "pointer/abc");
    }
}
