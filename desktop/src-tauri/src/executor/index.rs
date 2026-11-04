use std::fmt::Write;
use std::fs;
use std::path::Path;

use serde_json::{Value, json};
use sha2::{Digest, Sha256};

use super::chunker;
use super::paths::{relative, resolve};
use super::search::files;
use super::{ExecError, ExecOutcome, Request};

const MAX_INDEXED_FILES: usize = 10_000;
const MAX_INDEXED_FILE_BYTES: u64 = 256 * 1024;
const MAX_PATHS_PER_REQUEST: usize = 200;

const INDEXED_EXTENSIONS: &[&str] = &[
    "rs", "ts", "mts", "cts", "tsx", "js", "jsx", "mjs", "cjs", "py", "java", "go", "kt", "kts",
    "scala", "c", "h", "cc", "cpp", "hpp", "cs", "rb", "php", "swift", "sql", "sh", "ps1", "md",
    "mdx", "html", "css", "scss", "vue", "svelte", "toml", "yaml", "yml", "gradle", "proto",
    "graphql",
];

const SKIPPED_FILE_NAMES: &[&str] = &[
    "Cargo.lock",
    "package-lock.json",
    "pnpm-lock.yaml",
    "yarn.lock",
];

pub fn scan(root: &Path) -> Result<ExecOutcome, ExecError> {
    let mut entries = Vec::new();
    let mut truncated = false;
    for path in files(root) {
        let relative_path = relative(root, &path);
        if !indexable_name(&relative_path) {
            continue;
        }
        let Some(content) = read_indexable(&path) else {
            continue;
        };
        if entries.len() == MAX_INDEXED_FILES {
            truncated = true;
            break;
        }
        entries.push(json!({ "path": relative_path, "hash": hash(&content) }));
    }
    Ok(ExecOutcome::result(
        json!({ "files": entries, "truncated": truncated }),
    ))
}

pub fn chunks(root: &Path, request: &Request) -> Result<ExecOutcome, ExecError> {
    let paths: Vec<&str> = request
        .args
        .get("paths")
        .and_then(Value::as_array)
        .ok_or_else(|| ExecError::rejected("invalid_arguments", "Missing argument paths"))?
        .iter()
        .filter_map(Value::as_str)
        .take(MAX_PATHS_PER_REQUEST)
        .collect();
    let mut results = Vec::with_capacity(paths.len());
    for requested in paths {
        let path = resolve(root, requested)?;
        let relative_path = relative(root, &path);
        if !path.is_file() {
            results.push(json!({ "path": relative_path, "missing": true }));
            continue;
        }
        let content = indexable_name(&relative_path)
            .then(|| read_indexable(&path))
            .flatten();
        match content {
            Some(content) => results.push(json!({
                "path": relative_path,
                "hash": hash(&content),
                "chunks": chunker::chunk(&relative_path, &content),
            })),
            None => results.push(json!({ "path": relative_path, "skipped": true })),
        }
    }
    Ok(ExecOutcome::result(json!({ "files": results })))
}

fn indexable_name(relative_path: &str) -> bool {
    let file_name = relative_path.rsplit('/').next().unwrap_or(relative_path);
    if SKIPPED_FILE_NAMES.contains(&file_name) || file_name.contains(".min.") {
        return false;
    }
    file_name.rsplit_once('.').is_some_and(|(_, extension)| {
        INDEXED_EXTENSIONS.contains(&extension.to_ascii_lowercase().as_str())
    })
}

fn read_indexable(path: &Path) -> Option<String> {
    let size = fs::metadata(path).ok()?.len();
    if size == 0 || size > MAX_INDEXED_FILE_BYTES {
        return None;
    }
    let bytes = fs::read(path).ok()?;
    if bytes.contains(&0) {
        return None;
    }
    String::from_utf8(bytes).ok()
}

fn hash(content: &str) -> String {
    Sha256::digest(content.as_bytes())
        .iter()
        .fold(String::with_capacity(64), |mut hex, byte| {
            let _ = write!(hex, "{byte:02x}");
            hex
        })
}

#[cfg(test)]
mod tests {
    use super::*;

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
        fs::create_dir_all(root.path().join("src")).unwrap();
        fs::create_dir_all(root.path().join("target")).unwrap();
        fs::write(root.path().join(".gitignore"), "target/\n").unwrap();
        fs::write(
            root.path().join("src/main.rs"),
            "fn main() {\n    println!(\"hello from pointer\");\n}\n",
        )
        .unwrap();
        fs::write(root.path().join("src/logo.png"), [0u8, 1, 2]).unwrap();
        fs::write(root.path().join("Cargo.lock"), "lock file content\n").unwrap();
        fs::write(root.path().join("target/out.rs"), "fn generated() {}\n").unwrap();
        root
    }

    #[test]
    fn scan_lists_indexable_files_with_hashes() {
        let root = project();
        let outcome = scan(root.path()).unwrap();
        let files = outcome.result["files"].as_array().unwrap();
        assert_eq!(files.len(), 1);
        assert_eq!(files[0]["path"], "src/main.rs");
        assert_eq!(files[0]["hash"].as_str().unwrap().len(), 64);
        assert_eq!(outcome.result["truncated"], false);
    }

    #[test]
    fn chunks_reports_chunks_missing_and_skipped_files() {
        let root = project();
        let outcome = chunks(
            root.path(),
            &request(json!({ "paths": ["src/main.rs", "src/gone.rs", "Cargo.lock"] })),
        )
        .unwrap();
        let files = outcome.result["files"].as_array().unwrap();
        assert_eq!(files[0]["path"], "src/main.rs");
        assert_eq!(files[0]["chunks"][0]["symbol"], "main");
        assert_eq!(files[0]["chunks"][0]["startLine"], 1);
        assert_eq!(files[1]["missing"], true);
        assert_eq!(files[2]["skipped"], true);
    }

    #[test]
    fn chunks_rejects_paths_outside_the_project() {
        let root = project();
        let error =
            chunks(root.path(), &request(json!({ "paths": ["../secret.rs"] }))).unwrap_err();
        assert_eq!(error.code, "path_outside_project");
    }
}
