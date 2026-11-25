use std::path::Path;
use std::process::Command;

use serde_json::{Value, json};

use crate::git_auth::GitAuth;

pub const PREPARE: &str = "pointer.repo_prepare";
pub const PUBLISH: &str = "pointer.repo_publish";

#[derive(Debug)]
pub struct RepoError {
    pub code: &'static str,
    pub message: String,
}

impl RepoError {
    fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}

type RepoResult<T> = Result<T, RepoError>;

pub fn handle(
    workspace: &Path,
    operation: &str,
    args: &Value,
    auth: &GitAuth,
) -> RepoResult<Value> {
    match operation {
        PREPARE => prepare(workspace, args, auth),
        PUBLISH => publish(workspace, args, auth),
        other => Err(RepoError::new(
            "unknown_operation",
            format!("Unknown operation {other}"),
        )),
    }
}

fn prepare(workspace: &Path, args: &Value, auth: &GitAuth) -> RepoResult<Value> {
    let clone_url = required(args, "cloneUrl")?;
    let branch = required(args, "branch")?;
    let remote = Some(auth);
    if workspace.join(".git").is_dir() {
        git(workspace, None)
            .args(["remote", "set-url", "origin", clone_url])
            .run("remote_failed")?;
    } else {
        let parent = workspace
            .parent()
            .ok_or_else(|| RepoError::new("invalid_workspace", "The workspace has no parent"))?;
        std::fs::create_dir_all(parent)
            .map_err(|error| RepoError::new("io_error", error.to_string()))?;
        git(parent, remote)
            .args(["clone", "--no-tags", clone_url])
            .arg(workspace)
            .run("clone_failed")?;
    }
    let base_branch = match args["baseBranch"].as_str().filter(|base| !base.is_empty()) {
        Some(base) => base.to_string(),
        None => default_branch(workspace)?,
    };
    configure_author(workspace, args)?;
    if local_branch_exists(workspace, branch) {
        git(workspace, None)
            .args(["checkout", branch])
            .run("checkout_failed")?;
    } else {
        git(workspace, remote)
            .args(["fetch", "origin", &base_branch])
            .run("fetch_failed")?;
        git(workspace, None)
            .args(["checkout", "-b", branch, &format!("origin/{base_branch}")])
            .run("checkout_failed")?;
    }
    Ok(json!({
        "branch": branch,
        "baseBranch": base_branch,
        "head": head(workspace)?,
    }))
}

fn publish(workspace: &Path, args: &Value, auth: &GitAuth) -> RepoResult<Value> {
    let branch = required(args, "branch")?;
    let message = required(args, "message")?;
    let remote = Some(auth);
    git(workspace, None)
        .args(["add", "--all"])
        .run("add_failed")?;
    let staged = git(workspace, None)
        .args(["diff", "--cached", "--quiet"])
        .status()
        .map_err(|error| RepoError::new("git_unavailable", error.to_string()))?;
    let committed = !staged.success();
    if committed {
        git(workspace, None)
            .args(["commit", "--quiet", "-m", message])
            .run("commit_failed")?;
    }
    let unpushed = !remote_contains_head(workspace, branch);
    if committed || unpushed {
        git(workspace, remote)
            .args([
                "push",
                "--quiet",
                "origin",
                &format!("HEAD:refs/heads/{branch}"),
            ])
            .run("push_failed")?;
    }
    Ok(json!({
        "committed": committed,
        "pushed": committed || unpushed,
        "commit": head(workspace)?,
    }))
}

fn configure_author(workspace: &Path, args: &Value) -> RepoResult<()> {
    let name = args["authorName"].as_str().unwrap_or("Pointer");
    let email = args["authorEmail"]
        .as_str()
        .unwrap_or("pointer@users.noreply.github.com");
    git(workspace, None)
        .args(["config", "user.name", name])
        .run("config_failed")?;
    git(workspace, None)
        .args(["config", "user.email", email])
        .run("config_failed")?;
    Ok(())
}

fn default_branch(workspace: &Path) -> RepoResult<String> {
    let reference = git(workspace, None)
        .args(["symbolic-ref", "--short", "refs/remotes/origin/HEAD"])
        .output_text("default_branch_unknown")?;
    Ok(reference
        .trim()
        .strip_prefix("origin/")
        .unwrap_or(reference.trim())
        .to_string())
}

fn local_branch_exists(workspace: &Path, branch: &str) -> bool {
    git(workspace, None)
        .args([
            "rev-parse",
            "--verify",
            "--quiet",
            &format!("refs/heads/{branch}"),
        ])
        .status()
        .is_ok_and(|status| status.success())
}

fn remote_contains_head(workspace: &Path, branch: &str) -> bool {
    let Ok(local) = head(workspace) else {
        return false;
    };
    git(workspace, None)
        .args([
            "rev-parse",
            "--verify",
            "--quiet",
            &format!("refs/remotes/origin/{branch}"),
        ])
        .output_text("rev_parse_failed")
        .is_ok_and(|remote| remote.trim() == local)
}

fn head(workspace: &Path) -> RepoResult<String> {
    Ok(git(workspace, None)
        .args(["rev-parse", "HEAD"])
        .output_text("rev_parse_failed")?
        .trim()
        .to_string())
}

fn required<'a>(args: &'a Value, name: &str) -> RepoResult<&'a str> {
    args[name]
        .as_str()
        .filter(|value| !value.is_empty())
        .ok_or_else(|| RepoError::new("invalid_arguments", format!("Missing argument {name}")))
}

fn git(directory: &Path, auth: Option<&GitAuth>) -> Command {
    let mut command = Command::new("git");
    command
        .current_dir(directory)
        .env("GIT_TERMINAL_PROMPT", "0");
    if let Some(auth) = auth {
        auth.apply(&mut command);
    }
    command
}

trait Run {
    fn run(&mut self, code: &'static str) -> RepoResult<()>;
    fn output_text(&mut self, code: &'static str) -> RepoResult<String>;
}

impl Run for Command {
    fn run(&mut self, code: &'static str) -> RepoResult<()> {
        self.output_text(code).map(|_| ())
    }

    fn output_text(&mut self, code: &'static str) -> RepoResult<String> {
        let output = self
            .output()
            .map_err(|error| RepoError::new("git_unavailable", error.to_string()))?;
        if output.status.success() {
            Ok(String::from_utf8_lossy(&output.stdout).into_owned())
        } else {
            Err(RepoError::new(
                code,
                String::from_utf8_lossy(&output.stderr).trim().to_string(),
            ))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(directory: &Path, args: &[&str]) {
        let status = Command::new("git")
            .current_dir(directory)
            .args(args)
            .status()
            .unwrap();
        assert!(status.success(), "git {args:?} failed");
    }

    fn origin() -> tempfile::TempDir {
        let origin = tempfile::tempdir().unwrap();
        run(origin.path(), &["init", "--quiet", "--initial-branch=main"]);
        run(origin.path(), &["config", "user.name", "Test"]);
        run(
            origin.path(),
            &["config", "user.email", "test@example.test"],
        );
        std::fs::write(origin.path().join("README.md"), "hello\n").unwrap();
        run(origin.path(), &["add", "."]);
        run(origin.path(), &["commit", "--quiet", "-m", "init"]);
        run(
            origin.path(),
            &["config", "receive.denyCurrentBranch", "ignore"],
        );
        origin
    }

    #[test]
    fn prepares_branch_and_publishes_changes() {
        let origin = origin();
        let root = tempfile::tempdir().unwrap();
        let workspace = root.path().join("repo");
        let clone_url = origin.path().to_string_lossy().to_string();
        let auth = GitAuth::new("http://backend", None);

        let prepared = handle(
            &workspace,
            PREPARE,
            &json!({ "cloneUrl": clone_url, "branch": "pointer/abc-task" }),
            &auth,
        )
        .unwrap();
        assert_eq!(prepared["baseBranch"], "main");
        assert_eq!(prepared["branch"], "pointer/abc-task");

        let nothing = handle(
            &workspace,
            PUBLISH,
            &json!({ "branch": "pointer/abc-task", "message": "Empty" }),
            &auth,
        )
        .unwrap();
        assert_eq!(nothing["committed"], false);

        std::fs::write(workspace.join("new.txt"), "content\n").unwrap();
        let published = handle(
            &workspace,
            PUBLISH,
            &json!({ "branch": "pointer/abc-task", "message": "Add new file" }),
            &auth,
        )
        .unwrap();
        assert_eq!(published["committed"], true);
        assert_eq!(published["pushed"], true);

        let remote = Command::new("git")
            .current_dir(origin.path())
            .args(["log", "-1", "--format=%s", "pointer/abc-task"])
            .output()
            .unwrap();
        assert_eq!(
            String::from_utf8_lossy(&remote.stdout).trim(),
            "Add new file"
        );

        let again = handle(
            &workspace,
            PREPARE,
            &json!({ "cloneUrl": clone_url, "branch": "pointer/abc-task" }),
            &auth,
        )
        .unwrap();
        assert_eq!(again["head"], published["commit"]);

        let moved = tempfile::tempdir().unwrap();
        run(
            moved.path(),
            &["clone", "--quiet", "--bare", &clone_url, "origin.git"],
        );
        let moved_url = moved
            .path()
            .join("origin.git")
            .to_string_lossy()
            .to_string();
        handle(
            &workspace,
            PREPARE,
            &json!({ "cloneUrl": moved_url, "branch": "pointer/abc-task" }),
            &auth,
        )
        .unwrap();
        let origin_url = Command::new("git")
            .current_dir(&workspace)
            .args(["remote", "get-url", "origin"])
            .output()
            .unwrap();
        assert_eq!(
            String::from_utf8_lossy(&origin_url.stdout).trim(),
            moved_url
        );
    }
}
