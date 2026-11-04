use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

use serde_json::json;

use super::{ExecError, ExecOutcome, Request};

const CREATE_NO_WINDOW: u32 = 0x0800_0000;
const BRANCH_PREFIX: &str = "pointer/";

pub struct Worktree {
    pub path: PathBuf,
    pub branch: String,
}

impl Worktree {
    pub fn locate(worktrees_dir: &Path, session_id: &str, branch: &str) -> Option<Self> {
        let valid_session = !session_id.is_empty()
            && session_id
                .chars()
                .all(|char| char.is_ascii_hexdigit() || char == '-');
        let valid_branch = branch.strip_prefix(BRANCH_PREFIX).is_some_and(|name| {
            !name.is_empty()
                && name
                    .chars()
                    .all(|char| char.is_ascii_alphanumeric() || char == '-')
        });
        (valid_session && valid_branch).then(|| Self {
            path: worktrees_dir.join(session_id),
            branch: branch.to_string(),
        })
    }
}

pub fn handle(
    project: &Path,
    worktree: Option<&Worktree>,
    request: &Request,
) -> Result<ExecOutcome, ExecError> {
    let worktree = worktree
        .ok_or_else(|| ExecError::rejected("invalid_arguments", "The session has no worktree"))?;
    match request.operation.as_str() {
        "pointer.worktree_create" => create(project, worktree),
        "pointer.worktree_status" => status(project, worktree, request),
        "pointer.worktree_merge" => merge(project, worktree, request),
        "pointer.worktree_discard" => discard(project, worktree),
        other => Err(ExecError::rejected(
            "unknown_operation",
            format!("Unknown operation {other}"),
        )),
    }
}

fn create(project: &Path, worktree: &Worktree) -> Result<ExecOutcome, ExecError> {
    let top_level = git(project, &["rev-parse", "--show-toplevel"]).map_err(|_| {
        ExecError::failed(
            "not_a_git_repository",
            "The project folder is not a git repository",
        )
    })?;
    if !same_path(Path::new(&top_level), project) {
        return Err(ExecError::failed(
            "not_repository_root",
            "The project folder must be the top level of its git repository",
        ));
    }
    let base = current_branch(project)?;
    if worktree.path.exists() {
        return Err(ExecError::failed(
            "worktree_exists",
            "The worktree folder already exists",
        ));
    }
    if let Some(parent) = worktree.path.parent() {
        fs::create_dir_all(parent)
            .map_err(|error| ExecError::failed("io_error", error.to_string()))?;
    }
    let path = worktree.path.to_string_lossy();
    git(
        project,
        &["worktree", "add", "-b", &worktree.branch, &path, "HEAD"],
    )
    .map_err(|message| ExecError::failed("git_failed", message))?;
    Ok(ExecOutcome::result(json!({
        "branch": worktree.branch,
        "baseBranch": base,
        "path": path,
    })))
}

fn status(
    project: &Path,
    worktree: &Worktree,
    request: &Request,
) -> Result<ExecOutcome, ExecError> {
    require_worktree(worktree)?;
    let base = request.required_str("baseBranch")?;
    let changed = uncommitted_files(&worktree.path)?;
    let range = format!("{base}..{}", worktree.branch);
    let commits = git(project, &["rev-list", "--count", &range])
        .ok()
        .and_then(|count| count.parse::<u64>().ok())
        .unwrap_or(0);
    let checked_out = current_branch(project).ok();
    Ok(ExecOutcome::result(json!({
        "path": worktree.path.to_string_lossy(),
        "branch": worktree.branch,
        "baseBranch": base,
        "commitsAhead": commits,
        "uncommittedFiles": changed,
        "baseCheckedOut": checked_out.as_deref() == Some(base),
    })))
}

fn merge(project: &Path, worktree: &Worktree, request: &Request) -> Result<ExecOutcome, ExecError> {
    require_worktree(worktree)?;
    let base = request.required_str("baseBranch")?;
    let message = request
        .str_arg("message")
        .filter(|message| !message.trim().is_empty())
        .unwrap_or("Pointer session changes");
    if current_branch(project).ok().as_deref() != Some(base) {
        return Err(ExecError::failed(
            "base_not_checked_out",
            format!("Check out {base} in the project folder before merging"),
        ));
    }
    if !uncommitted_files(&worktree.path)?.is_empty() {
        git(&worktree.path, &["add", "--all"])
            .map_err(|message| ExecError::failed("git_failed", message))?;
        git(&worktree.path, &["commit", "--no-verify", "-m", message])
            .map_err(|message| ExecError::failed("commit_failed", message))?;
    }
    let range = format!("{base}..{}", worktree.branch);
    let commits = git(project, &["rev-list", "--count", &range])
        .map_err(|message| ExecError::failed("git_failed", message))?;
    let merged = commits != "0";
    if merged {
        let merge_message = format!("Merge {} into {base}", worktree.branch);
        if let Err(message) = git(
            project,
            &["merge", "--no-ff", "-m", &merge_message, &worktree.branch],
        ) {
            let _ = git(project, &["merge", "--abort"]);
            return Err(ExecError::failed("merge_conflict", message));
        }
    }
    remove(project, worktree, false)?;
    Ok(ExecOutcome::result(json!({
        "merged": merged,
        "commits": commits.parse::<u64>().unwrap_or(0),
    })))
}

fn discard(project: &Path, worktree: &Worktree) -> Result<ExecOutcome, ExecError> {
    remove(project, worktree, true)?;
    Ok(ExecOutcome::result(json!({ "discarded": true })))
}

fn remove(project: &Path, worktree: &Worktree, force_branch: bool) -> Result<(), ExecError> {
    if worktree.path.exists() {
        let path = worktree.path.to_string_lossy();
        git(project, &["worktree", "remove", "--force", &path])
            .map_err(|message| ExecError::failed("git_failed", message))?;
    }
    let _ = git(project, &["worktree", "prune"]);
    let delete_flag = if force_branch { "-D" } else { "-d" };
    let branch_exists = git(
        project,
        &[
            "rev-parse",
            "--verify",
            "--quiet",
            &format!("refs/heads/{}", worktree.branch),
        ],
    )
    .is_ok();
    if branch_exists {
        git(project, &["branch", delete_flag, &worktree.branch])
            .map_err(|message| ExecError::failed("git_failed", message))?;
    }
    Ok(())
}

fn require_worktree(worktree: &Worktree) -> Result<(), ExecError> {
    if worktree.path.is_dir() {
        Ok(())
    } else {
        Err(missing())
    }
}

pub fn missing() -> ExecError {
    ExecError::rejected(
        "worktree_missing",
        "The session worktree does not exist on this device",
    )
}

fn uncommitted_files(path: &Path) -> Result<Vec<String>, ExecError> {
    let output = git(path, &["status", "--porcelain", "--untracked-files=all"])
        .map_err(|message| ExecError::failed("git_failed", message))?;
    Ok(output
        .lines()
        .filter(|line| line.len() > 3)
        .map(|line| line[3..].trim_matches('"').to_string())
        .collect())
}

fn current_branch(path: &Path) -> Result<String, ExecError> {
    git(path, &["symbolic-ref", "--short", "HEAD"]).map_err(|_| {
        ExecError::failed(
            "detached_head",
            "The project folder has no branch checked out",
        )
    })
}

fn same_path(left: &Path, right: &Path) -> bool {
    match (left.canonicalize(), right.canonicalize()) {
        (Ok(left), Ok(right)) => left == right,
        _ => false,
    }
}

fn git(directory: &Path, args: &[&str]) -> Result<String, String> {
    let mut command = Command::new("git");
    command
        .args(["-c", "core.quotepath=off"])
        .args(args)
        .current_dir(directory)
        .env("GIT_TERMINAL_PROMPT", "0");
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(CREATE_NO_WINDOW);
    }
    let output = command.output().map_err(|error| error.to_string())?;
    if output.status.success() {
        Ok(String::from_utf8_lossy(&output.stdout)
            .trim_end()
            .to_string())
    } else {
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
        let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
        Err(if stderr.is_empty() { stdout } else { stderr })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    fn request(operation: &str, args: Value) -> Request {
        Request {
            operation: operation.into(),
            args,
            dry_run: false,
            timeout_ms: 30_000,
        }
    }

    fn repository() -> tempfile::TempDir {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        git(root, &["init", "-b", "main"]).unwrap();
        git(root, &["config", "user.email", "test@example.com"]).unwrap();
        git(root, &["config", "user.name", "Test"]).unwrap();
        git(root, &["config", "core.autocrlf", "false"]).unwrap();
        fs::write(root.join("app.txt"), "one\n").unwrap();
        git(root, &["add", "--all"]).unwrap();
        git(root, &["commit", "-m", "init"]).unwrap();
        dir
    }

    fn worktree(data: &Path) -> Worktree {
        Worktree::locate(data, "0f1e2d3c-aaaa", "pointer/0f1e2d3c").unwrap()
    }

    #[test]
    fn merge_commits_worktree_changes_into_base() {
        let repo = repository();
        let data = tempfile::tempdir().unwrap();
        let worktree = worktree(data.path());

        let created = handle(
            repo.path(),
            Some(&worktree),
            &request("pointer.worktree_create", json!({})),
        )
        .unwrap();
        fs::write(worktree.path.join("app.txt"), "two\n").unwrap();
        let status = handle(
            repo.path(),
            Some(&worktree),
            &request("pointer.worktree_status", json!({ "baseBranch": "main" })),
        )
        .unwrap();
        let merged = handle(
            repo.path(),
            Some(&worktree),
            &request(
                "pointer.worktree_merge",
                json!({ "baseBranch": "main", "message": "Change app" }),
            ),
        )
        .unwrap();

        assert_eq!(created.result["baseBranch"], "main");
        assert_eq!(status.result["uncommittedFiles"][0], "app.txt");
        assert_eq!(merged.result["merged"], true);
        assert_eq!(
            fs::read_to_string(repo.path().join("app.txt")).unwrap(),
            "two\n"
        );
        assert!(!worktree.path.exists());
        assert!(
            git(
                repo.path(),
                &[
                    "rev-parse",
                    "--verify",
                    "--quiet",
                    "refs/heads/pointer/0f1e2d3c"
                ]
            )
            .is_err()
        );
    }

    #[test]
    fn conflicting_merge_is_aborted() {
        let repo = repository();
        let data = tempfile::tempdir().unwrap();
        let worktree = worktree(data.path());
        handle(
            repo.path(),
            Some(&worktree),
            &request("pointer.worktree_create", json!({})),
        )
        .unwrap();
        fs::write(worktree.path.join("app.txt"), "worktree\n").unwrap();
        fs::write(repo.path().join("app.txt"), "main\n").unwrap();
        git(repo.path(), &["commit", "-am", "main change"]).unwrap();

        let error = handle(
            repo.path(),
            Some(&worktree),
            &request("pointer.worktree_merge", json!({ "baseBranch": "main" })),
        )
        .unwrap_err();

        assert_eq!(error.code, "merge_conflict");
        assert_eq!(
            fs::read_to_string(repo.path().join("app.txt")).unwrap(),
            "main\n"
        );
        assert!(worktree.path.exists());
        assert!(uncommitted_files(repo.path()).unwrap().is_empty());
    }

    #[test]
    fn discard_removes_worktree_and_branch() {
        let repo = repository();
        let data = tempfile::tempdir().unwrap();
        let worktree = worktree(data.path());
        handle(
            repo.path(),
            Some(&worktree),
            &request("pointer.worktree_create", json!({})),
        )
        .unwrap();
        fs::write(worktree.path.join("new.txt"), "x").unwrap();

        handle(
            repo.path(),
            Some(&worktree),
            &request("pointer.worktree_discard", json!({})),
        )
        .unwrap();

        assert!(!worktree.path.exists());
        assert!(!repo.path().join("new.txt").exists());
        assert!(
            git(
                repo.path(),
                &[
                    "rev-parse",
                    "--verify",
                    "--quiet",
                    "refs/heads/pointer/0f1e2d3c"
                ]
            )
            .is_err()
        );
    }

    #[test]
    fn locate_rejects_unsafe_names() {
        let data = Path::new("data");
        assert!(Worktree::locate(data, "../etc", "pointer/abc").is_none());
        assert!(Worktree::locate(data, "abc", "main").is_none());
        assert!(Worktree::locate(data, "abc", "pointer/a b").is_none());
    }
}
