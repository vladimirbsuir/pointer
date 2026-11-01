use std::fs;
use std::path::Path;

use globset::{Glob, GlobMatcher};
use ignore::WalkBuilder;
use regex::RegexBuilder;
use serde_json::json;

use super::paths::{relative, resolve};
use super::{ExecError, ExecOutcome, Request};

const MAX_GLOB_RESULTS: usize = 500;
const DEFAULT_GREP_RESULTS: usize = 100;
const MAX_LINE_CHARS: usize = 300;
const MAX_SUGGESTION_SCAN: usize = 20_000;
const MAX_GREP_FILE_BYTES: u64 = 2 * 1024 * 1024;

pub fn glob(root: &Path, request: &Request) -> Result<ExecOutcome, ExecError> {
    let base = resolve(root, request.str_arg("path").unwrap_or("."))?;
    let matcher = matcher(request.required_str("pattern")?)?;
    let mut paths = Vec::new();
    let mut truncated = false;
    for path in files(&base) {
        let relative_to_base = relative(&base, &path);
        if matcher.is_match(&relative_to_base) {
            if paths.len() == MAX_GLOB_RESULTS {
                truncated = true;
                break;
            }
            paths.push(relative(root, &path));
        }
    }
    paths.sort();
    Ok(ExecOutcome::result(
        json!({ "paths": paths, "truncated": truncated }),
    ))
}

pub fn grep(root: &Path, request: &Request) -> Result<ExecOutcome, ExecError> {
    let base = resolve(root, request.str_arg("path").unwrap_or("."))?;
    let regex = RegexBuilder::new(request.required_str("pattern")?)
        .case_insensitive(request.bool_arg("caseInsensitive").unwrap_or(false))
        .build()
        .map_err(|error| ExecError::failed("invalid_pattern", error.to_string()))?;
    let filter = request.str_arg("glob").map(matcher).transpose()?;
    let max_results = request
        .u64_arg("maxResults")
        .map(|max| max as usize)
        .unwrap_or(DEFAULT_GREP_RESULTS);
    let mut matches = Vec::new();
    let mut truncated = false;
    'files: for path in files(&base) {
        if let Some(filter) = &filter
            && !filter.is_match(relative(&base, &path))
        {
            continue;
        }
        if fs::metadata(&path)
            .map(|metadata| metadata.len())
            .unwrap_or(0)
            > MAX_GREP_FILE_BYTES
        {
            continue;
        }
        let Ok(bytes) = fs::read(&path) else {
            continue;
        };
        if bytes.iter().take(8192).any(|byte| *byte == 0) {
            continue;
        }
        let content = String::from_utf8_lossy(&bytes);
        for (index, line) in content.lines().enumerate() {
            if regex.is_match(line) {
                if matches.len() == max_results {
                    truncated = true;
                    break 'files;
                }
                matches.push(json!({
                    "path": relative(root, &path),
                    "line": index + 1,
                    "text": line.trim().chars().take(MAX_LINE_CHARS).collect::<String>(),
                }));
            }
        }
    }
    Ok(ExecOutcome::result(
        json!({ "matches": matches, "truncated": truncated }),
    ))
}

pub fn suggest_files(root: &Path, query: &str, limit: usize) -> Result<Vec<String>, ExecError> {
    let base = resolve(root, ".")?;
    let query = query.to_lowercase().replace('\\', "/");
    let mut ranked: Vec<(u8, usize, String)> = files(&base)
        .take(MAX_SUGGESTION_SCAN)
        .filter_map(|path| {
            let relative = relative(root, &path);
            suggestion_rank(&relative.to_lowercase(), &query)
                .map(|rank| (rank, relative.len(), relative))
        })
        .collect();
    ranked.sort();
    Ok(ranked
        .into_iter()
        .take(limit)
        .map(|(_, _, path)| path)
        .collect())
}

fn suggestion_rank(path: &str, query: &str) -> Option<u8> {
    let name = path.rsplit('/').next().unwrap_or(path);
    if query.is_empty() || name.starts_with(query) {
        Some(0)
    } else if name.contains(query) {
        Some(1)
    } else if path.contains(query) {
        Some(2)
    } else if is_subsequence(path, query) {
        Some(3)
    } else {
        None
    }
}

fn is_subsequence(text: &str, query: &str) -> bool {
    let mut remaining = text.chars();
    query
        .chars()
        .all(|wanted| remaining.any(|candidate| candidate == wanted))
}

fn files(base: &Path) -> impl Iterator<Item = std::path::PathBuf> {
    WalkBuilder::new(base)
        .hidden(false)
        .require_git(false)
        .filter_entry(|entry| entry.file_name() != ".git")
        .build()
        .filter_map(Result::ok)
        .filter(|entry| entry.file_type().is_some_and(|kind| kind.is_file()))
        .map(|entry| entry.into_path())
}

fn matcher(pattern: &str) -> Result<GlobMatcher, ExecError> {
    let pattern = if pattern.contains('/') || pattern.starts_with("**") {
        pattern.to_string()
    } else {
        format!("**/{pattern}")
    };
    Glob::new(&pattern)
        .map(|glob| glob.compile_matcher())
        .map_err(|error| ExecError::failed("invalid_pattern", error.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{Value, json};

    fn request(args: Value) -> Request {
        Request {
            operation: String::new(),
            args,
            dry_run: false,
            timeout_ms: 30_000,
        }
    }

    fn project() -> tempfile::TempDir {
        let root = tempfile::tempdir().unwrap();
        fs::create_dir_all(root.path().join("src/nested")).unwrap();
        fs::create_dir_all(root.path().join("target")).unwrap();
        fs::write(root.path().join(".gitignore"), "target/\n").unwrap();
        fs::write(
            root.path().join("src/main.rs"),
            "fn main() {\n    run();\n}\n",
        )
        .unwrap();
        fs::write(root.path().join("src/nested/lib.rs"), "pub fn run() {}\n").unwrap();
        fs::write(root.path().join("target/out.rs"), "fn run() {}\n").unwrap();
        root
    }

    #[test]
    fn glob_finds_files_and_respects_gitignore() {
        let root = project();

        let outcome = glob(root.path(), &request(json!({"pattern": "*.rs"}))).unwrap();

        assert_eq!(
            outcome.result["paths"],
            json!(["src/main.rs", "src/nested/lib.rs"])
        );
    }

    #[test]
    fn suggestions_prefer_file_name_matches() {
        let root = project();

        let suggestions = suggest_files(root.path(), "lib", 10).unwrap();

        assert_eq!(
            suggestions.first().map(String::as_str),
            Some("src/nested/lib.rs")
        );
        assert!(suggest_files(root.path(), "zzz", 10).unwrap().is_empty());
    }

    #[test]
    fn grep_reports_matching_lines() {
        let root = project();

        let outcome = grep(
            root.path(),
            &request(json!({"pattern": "fn run", "glob": "*.rs"})),
        )
        .unwrap();

        assert_eq!(
            outcome.result["matches"],
            json!([{ "path": "src/nested/lib.rs", "line": 1, "text": "pub fn run() {}" }])
        );
    }
}
