use std::collections::BTreeSet;
use std::fs::{self, File};
use std::io::{self, BufReader, BufWriter, Read, Seek, SeekFrom, Write};
use std::path::{Component, Path, PathBuf};
use std::time::{Duration, UNIX_EPOCH};

use flate2::Compression;
use flate2::read::GzDecoder;
use flate2::write::GzEncoder;
use serde_json::{Value, json};
use tokio::io::AsyncWriteExt;

use super::paths::relative;
use super::search::files;
use super::{ExecError, ExecOutcome, Request};

pub const SNAPSHOT: &str = "pointer.workspace_snapshot";
pub const EXTRACT: &str = "pointer.sandbox_extract";

const DEFAULT_MAX_FILES: u64 = 10_000;
const DEFAULT_MAX_BYTES: u64 = 100 * 1024 * 1024;
const LISTED_EXCLUSIONS: usize = 50;
const LISTED_LARGEST: usize = 5;
const MANIFEST_NAME: &str = ".pointer-sync.json";
const TRANSFER_PATH_PREFIX: &str = "/api/v1/";
const ARCHIVE_CONTENT_TYPE: &str = "application/gzip";
const PUBLIC_ENV_MARKERS: &[&str] = &["example", "sample", "template", "dist"];
const SECRET_EXTENSIONS: &[&str] = &[
    "pem", "key", "p8", "p12", "pfx", "jks", "keystore", "crt", "cer", "der", "ppk",
];
const SECRET_NAMES: &[&str] = &[
    "id_rsa",
    "id_dsa",
    "id_ecdsa",
    "id_ed25519",
    ".envrc",
    ".netrc",
    ".npmrc",
    ".pypirc",
    ".pgpass",
    ".git-credentials",
    "credentials.json",
    "secrets.json",
    "secrets.yaml",
    "secrets.yml",
];
const SECRET_PATHS: &[&str] = &[".aws/credentials"];
const SERVICE_ACCOUNT_MARKERS: &[&str] = &["service-account", "service_account"];
const SCRIPT_MODE: u32 = 0o755;
const FILE_MODE: u32 = 0o644;

struct Selected {
    path: PathBuf,
    name: String,
    size: u64,
}

struct Selection {
    files: Vec<Selected>,
    excluded: Vec<String>,
    bytes: u64,
}

pub async fn snapshot(
    root: &Path,
    request: &Request,
    api_url: Option<&str>,
) -> Result<ExecOutcome, ExecError> {
    let max_files = request.u64_arg("maxFiles").unwrap_or(DEFAULT_MAX_FILES);
    let max_bytes = request.u64_arg("maxBytes").unwrap_or(DEFAULT_MAX_BYTES);
    let upload = if request.dry_run {
        None
    } else {
        Some(transfer_url(api_url, request.required_str("uploadPath")?)?)
    };
    let selection_root = root.to_path_buf();
    let selection = blocking(move || select(&selection_root)).await?;
    check_limits(&selection, max_files, max_bytes)?;
    let mut result = json!({
        "files": selection.files.len(),
        "bytes": selection.bytes,
        "excluded": selection.excluded.iter().take(LISTED_EXCLUSIONS).collect::<Vec<_>>(),
        "excludedCount": selection.excluded.len(),
    });
    let Some(upload) = upload else {
        return Ok(ExecOutcome::result(result));
    };
    let (archive, archive_bytes) = blocking(move || pack(&selection.files)).await?;
    send_archive(&upload, archive, archive_bytes, request.timeout_ms).await?;
    result["archiveBytes"] = json!(archive_bytes);
    Ok(ExecOutcome::result(result))
}

pub async fn extract(
    root: &Path,
    request: &Request,
    api_url: Option<&str>,
) -> Result<ExecOutcome, ExecError> {
    let download = transfer_url(api_url, request.required_str("downloadPath")?)?;
    let archive = receive_archive(&download, request.timeout_ms).await?;
    let target = root.to_path_buf();
    blocking(move || unpack(&target, archive)).await
}

async fn blocking<T: Send + 'static>(
    task: impl FnOnce() -> Result<T, ExecError> + Send + 'static,
) -> Result<T, ExecError> {
    tokio::task::spawn_blocking(task)
        .await
        .unwrap_or_else(|error| Err(ExecError::failed("internal_error", error.to_string())))
}

fn transfer_url(api_url: Option<&str>, path: &str) -> Result<String, ExecError> {
    let api_url = api_url.ok_or_else(|| {
        ExecError::rejected(
            "transfer_unavailable",
            "This executor cannot reach the Pointer server over HTTP",
        )
    })?;
    if !path.starts_with(TRANSFER_PATH_PREFIX) {
        return Err(ExecError::rejected(
            "invalid_arguments",
            "The transfer path must be a path on the Pointer server",
        ));
    }
    Ok(format!("{}{path}", api_url.trim_end_matches('/')))
}

fn select(root: &Path) -> Result<Selection, ExecError> {
    let mut selection = Selection {
        files: Vec::new(),
        excluded: Vec::new(),
        bytes: 0,
    };
    for path in files(root) {
        let name = relative(root, &path);
        if is_secret(&name) {
            selection.excluded.push(name);
            continue;
        }
        let Ok(metadata) = fs::metadata(&path) else {
            continue;
        };
        selection.bytes += metadata.len();
        selection.files.push(Selected {
            path,
            name,
            size: metadata.len(),
        });
    }
    selection
        .files
        .sort_by(|left, right| left.name.cmp(&right.name));
    selection.excluded.sort();
    Ok(selection)
}

fn is_secret(name: &str) -> bool {
    let name = name.to_ascii_lowercase();
    let file_name = name.rsplit('/').next().unwrap_or(&name);
    is_env_file(file_name)
        || SECRET_NAMES.contains(&file_name)
        || SECRET_PATHS
            .iter()
            .any(|path| name == *path || name.ends_with(&format!("/{path}")))
        || is_service_account_key(file_name)
        || Path::new(file_name)
            .extension()
            .and_then(|extension| extension.to_str())
            .is_some_and(|extension| SECRET_EXTENSIONS.contains(&extension))
}

fn is_env_file(file_name: &str) -> bool {
    let named_env = file_name == ".env"
        || file_name.starts_with(".env.")
        || (file_name.ends_with(".env") && file_name.len() > ".env".len());
    named_env
        && !file_name
            .split('.')
            .any(|part| PUBLIC_ENV_MARKERS.contains(&part))
}

fn is_service_account_key(file_name: &str) -> bool {
    file_name.ends_with(".json")
        && SERVICE_ACCOUNT_MARKERS
            .iter()
            .any(|marker| file_name.contains(marker))
}

fn check_limits(selection: &Selection, max_files: u64, max_bytes: u64) -> Result<(), ExecError> {
    if selection.files.len() as u64 > max_files {
        return Err(ExecError::failed(
            "snapshot_too_large",
            format!(
                "The project has {} files to send, more than the limit of {max_files}. Add generated folders to .gitignore.",
                selection.files.len()
            ),
        ));
    }
    if selection.bytes > max_bytes {
        let mut largest: Vec<&Selected> = selection.files.iter().collect();
        largest.sort_by_key(|file| std::cmp::Reverse(file.size));
        let names = largest
            .iter()
            .take(LISTED_LARGEST)
            .map(|file| format!("{} ({})", file.name, megabytes(file.size)))
            .collect::<Vec<_>>()
            .join(", ");
        return Err(ExecError::failed(
            "snapshot_too_large",
            format!(
                "The project files take {}, more than the limit of {}. The largest are {names}. Add generated or large files to .gitignore.",
                megabytes(selection.bytes),
                megabytes(max_bytes)
            ),
        ));
    }
    Ok(())
}

fn megabytes(bytes: u64) -> String {
    format!("{:.1} MB", bytes as f64 / (1024.0 * 1024.0))
}

fn pack(selected: &[Selected]) -> Result<(File, u64), ExecError> {
    let packing = |error: io::Error| ExecError::failed("snapshot_failed", error.to_string());
    let archive = tempfile::tempfile().map_err(packing)?;
    let encoder = GzEncoder::new(
        BufWriter::new(archive.try_clone().map_err(packing)?),
        Compression::fast(),
    );
    let mut builder = tar::Builder::new(encoder);
    for file in selected {
        append(&mut builder, file).map_err(|error| {
            ExecError::failed(
                "snapshot_failed",
                format!("Could not pack {}: {error}", file.name),
            )
        })?;
    }
    let mut writer = builder
        .into_inner()
        .and_then(GzEncoder::finish)
        .map_err(packing)?;
    writer.flush().map_err(packing)?;
    drop(writer);
    let mut archive = archive;
    let size = archive.seek(SeekFrom::End(0)).map_err(packing)?;
    archive.seek(SeekFrom::Start(0)).map_err(packing)?;
    Ok((archive, size))
}

fn append<W: Write>(builder: &mut tar::Builder<W>, file: &Selected) -> io::Result<()> {
    let mut source = File::open(&file.path)?;
    let metadata = source.metadata()?;
    let mut header = tar::Header::new_gnu();
    header.set_entry_type(tar::EntryType::Regular);
    header.set_size(metadata.len());
    header.set_mode(mode(&metadata, &mut source)?);
    header.set_mtime(
        metadata
            .modified()
            .ok()
            .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
            .map(|elapsed| elapsed.as_secs())
            .unwrap_or_default(),
    );
    builder.append_data(
        &mut header,
        &file.name,
        ExactLength::new(source, metadata.len()),
    )
}

struct ExactLength<R> {
    source: R,
    remaining: u64,
}

impl<R: Read> ExactLength<R> {
    fn new(source: R, declared: u64) -> Self {
        Self {
            source,
            remaining: declared,
        }
    }
}

impl<R: Read> Read for ExactLength<R> {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        if self.remaining == 0 || buffer.is_empty() {
            return Ok(0);
        }
        let allowed = usize::try_from(self.remaining)
            .unwrap_or(usize::MAX)
            .min(buffer.len());
        let read = self.source.read(&mut buffer[..allowed])?;
        if read == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                format!(
                    "the file got shorter while it was being packed, {} bytes are missing; sync again",
                    self.remaining
                ),
            ));
        }
        self.remaining -= read as u64;
        Ok(read)
    }
}

#[cfg(unix)]
fn mode(metadata: &fs::Metadata, _source: &mut File) -> io::Result<u32> {
    use std::os::unix::fs::PermissionsExt;
    Ok(if metadata.permissions().mode() & 0o111 != 0 {
        SCRIPT_MODE
    } else {
        FILE_MODE
    })
}

#[cfg(not(unix))]
fn mode(_metadata: &fs::Metadata, source: &mut File) -> io::Result<u32> {
    let mut start = [0u8; 2];
    let read = source.read(&mut start)?;
    source.seek(SeekFrom::Start(0))?;
    Ok(if read == 2 && &start == b"#!" {
        SCRIPT_MODE
    } else {
        FILE_MODE
    })
}

async fn send_archive(
    url: &str,
    archive: File,
    archive_bytes: u64,
    timeout_ms: u64,
) -> Result<(), ExecError> {
    let uploading = |error: reqwest::Error| ExecError::failed("upload_failed", error.to_string());
    let client = client(timeout_ms).map_err(uploading)?;
    let response = client
        .put(url)
        .header(reqwest::header::CONTENT_TYPE, ARCHIVE_CONTENT_TYPE)
        .header(reqwest::header::CONTENT_LENGTH, archive_bytes)
        .body(tokio::fs::File::from_std(archive))
        .send()
        .await
        .map_err(uploading)?;
    if !response.status().is_success() {
        let status = response.status();
        let body = response.text().await.unwrap_or_default();
        return Err(ExecError::failed(
            "upload_failed",
            format!("The server answered {status} to the upload: {body}"),
        ));
    }
    Ok(())
}

async fn receive_archive(url: &str, timeout_ms: u64) -> Result<File, ExecError> {
    let downloading =
        |error: reqwest::Error| ExecError::failed("download_failed", error.to_string());
    let writing = |error: io::Error| ExecError::failed("download_failed", error.to_string());
    let client = client(timeout_ms).map_err(downloading)?;
    let mut response = client.get(url).send().await.map_err(downloading)?;
    if !response.status().is_success() {
        return Err(ExecError::failed(
            "download_failed",
            format!("The server answered {} to the download", response.status()),
        ));
    }
    let archive = tempfile::tempfile().map_err(writing)?;
    let mut writer = tokio::fs::File::from_std(archive.try_clone().map_err(writing)?);
    while let Some(chunk) = response.chunk().await.map_err(downloading)? {
        writer.write_all(&chunk).await.map_err(writing)?;
    }
    writer.flush().await.map_err(writing)?;
    drop(writer);
    let mut archive = archive;
    archive.seek(SeekFrom::Start(0)).map_err(writing)?;
    Ok(archive)
}

fn client(timeout_ms: u64) -> reqwest::Result<reqwest::Client> {
    reqwest::Client::builder()
        .timeout(Duration::from_millis(timeout_ms))
        .build()
}

fn unpack(root: &Path, archive: File) -> Result<ExecOutcome, ExecError> {
    let failed = |error: io::Error| ExecError::failed("extract_failed", error.to_string());
    fs::create_dir_all(root).map_err(failed)?;
    let manifest = manifest_path(root);
    let previous = read_manifest(&manifest);
    let mut reader = tar::Archive::new(GzDecoder::new(BufReader::new(archive)));
    reader.set_preserve_mtime(true);
    reader.set_preserve_permissions(true);
    reader.set_overwrite(true);
    let mut names = BTreeSet::new();
    let mut bytes = 0u64;
    for entry in reader.entries().map_err(failed)? {
        let mut entry = entry.map_err(failed)?;
        let name = entry_name(&entry)?;
        bytes += entry.header().size().map_err(failed)?;
        if !entry.unpack_in(root).map_err(|error| {
            ExecError::failed("extract_failed", format!("Could not write {name}: {error}"))
        })? {
            return Err(invalid_archive(&name));
        }
        names.insert(name);
    }
    let deleted = previous
        .as_ref()
        .map(|previous| delete_missing(root, previous, &names))
        .unwrap_or_default();
    write_manifest(&manifest, &names).map_err(failed)?;
    Ok(ExecOutcome::result(json!({
        "files": names.len(),
        "bytes": bytes,
        "deleted": deleted,
        "firstSync": previous.is_none(),
        "workspace": root.to_string_lossy(),
    })))
}

fn entry_name<R: Read>(entry: &tar::Entry<R>) -> Result<String, ExecError> {
    let path = entry
        .path()
        .map_err(|error| ExecError::failed("invalid_archive", error.to_string()))?;
    let display = path.to_string_lossy().to_string();
    let normal = path
        .components()
        .all(|component| matches!(component, Component::Normal(_)));
    if !normal || entry.header().entry_type() != tar::EntryType::Regular {
        return Err(invalid_archive(&display));
    }
    Ok(display.replace('\\', "/"))
}

fn invalid_archive(name: &str) -> ExecError {
    ExecError::failed(
        "invalid_archive",
        format!("The archive entry {name} is not a regular file inside the workspace"),
    )
}

fn delete_missing(root: &Path, previous: &BTreeSet<String>, current: &BTreeSet<String>) -> usize {
    let Ok(real_root) = root.canonicalize() else {
        return 0;
    };
    let mut deleted = 0;
    for name in previous.difference(current) {
        let path = root.join(name);
        let inside = path
            .parent()
            .and_then(|parent| parent.canonicalize().ok())
            .is_some_and(|parent| parent.starts_with(&real_root));
        let is_file = fs::symlink_metadata(&path).is_ok_and(|metadata| !metadata.is_dir());
        if inside && is_file && fs::remove_file(&path).is_ok() {
            deleted += 1;
            remove_empty_parents(root, &path);
        }
    }
    deleted
}

fn remove_empty_parents(root: &Path, path: &Path) {
    let mut current = path.parent();
    while let Some(directory) = current {
        if directory == root || !directory.starts_with(root) || fs::remove_dir(directory).is_err() {
            return;
        }
        current = directory.parent();
    }
}

fn manifest_path(root: &Path) -> PathBuf {
    root.parent()
        .filter(|parent| !parent.as_os_str().is_empty())
        .unwrap_or(root)
        .join(MANIFEST_NAME)
}

fn read_manifest(path: &Path) -> Option<BTreeSet<String>> {
    let content = fs::read_to_string(path).ok()?;
    let value: Value = serde_json::from_str(&content).ok()?;
    Some(
        value["files"]
            .as_array()?
            .iter()
            .filter_map(Value::as_str)
            .filter(|name| is_inside(name))
            .map(str::to_string)
            .collect(),
    )
}

fn is_inside(name: &str) -> bool {
    Path::new(name)
        .components()
        .all(|component| matches!(component, Component::Normal(_)))
}

fn write_manifest(path: &Path, names: &BTreeSet<String>) -> io::Result<()> {
    fs::write(path, json!({ "files": names }).to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write(root: &Path, name: &str, content: &str) {
        let path = root.join(name);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, content).unwrap();
    }

    fn snapshot_of(root: &Path) -> File {
        let selection = select(root).unwrap();
        pack(&selection.files).unwrap().0
    }

    #[test]
    fn selection_respects_gitignore_and_leaves_out_secrets_and_git() {
        let project = tempfile::tempdir().unwrap();
        let root = project.path();
        write(root, ".gitignore", "target/\nnode_modules\n");
        write(root, "src/main.rs", "fn main() {}");
        write(root, "target/debug/app", "binary");
        write(root, "node_modules/lib/index.js", "module");
        write(root, ".git/config", "[core]");
        write(root, ".env", "TOKEN=1");
        write(root, ".env.local", "TOKEN=2");
        write(root, ".env.example", "TOKEN=");
        write(root, "certs/server.key", "key");
        write(root, "deploy/id_ed25519", "key");
        write(root, "deploy/prod.env", "TOKEN=3");
        write(root, ".aws/credentials", "[default]");
        write(root, "config/secrets.yaml", "token: 4");
        write(root, ".github/workflows/ci.yml", "on: push");

        let selection = select(root).unwrap();
        let names: Vec<&str> = selection
            .files
            .iter()
            .map(|file| file.name.as_str())
            .collect();

        assert_eq!(
            names,
            vec![
                ".env.example",
                ".github/workflows/ci.yml",
                ".gitignore",
                "src/main.rs"
            ]
        );
        assert_eq!(
            selection.excluded,
            vec![
                ".aws/credentials",
                ".env",
                ".env.local",
                "certs/server.key",
                "config/secrets.yaml",
                "deploy/id_ed25519",
                "deploy/prod.env"
            ]
        );
    }

    #[test]
    fn secret_filter_covers_env_and_credential_files() {
        let secret = [
            ".env",
            ".env.local",
            "config/.ENV.production",
            "deploy/prod.env",
            "Staging.env",
            ".envrc",
            ".aws/credentials",
            "home/.aws/credentials",
            "credentials.json",
            "config/secrets.yaml",
            "secrets.yml",
            "secrets.json",
            ".netrc",
            ".pgpass",
            ".npmrc",
            ".pypirc",
            ".git-credentials",
            "service-account.json",
            "keys/my-project-service-account-key.json",
            "gcp_service_account.json",
            "certs/server.key",
            "deploy/id_ed25519",
        ];
        let public = [
            ".env.example",
            ".env.sample",
            ".env.template",
            ".env.dist",
            "example.env",
            "prod.example.env",
            "prod.env.example",
            "src/environment.ts",
            "src/env.ts",
            ".aws/config",
            "credentials.md",
            "src/secrets.rs",
            "service-account.md",
            "package.json",
        ];

        for name in secret {
            assert!(is_secret(name), "{name} should be left out");
        }
        for name in public {
            assert!(!is_secret(name), "{name} should be sent");
        }
    }

    #[test]
    fn exact_length_copies_the_declared_size_and_no_more() {
        let mut copied = Vec::new();

        ExactLength::new(&b"0123456789"[..], 4)
            .read_to_end(&mut copied)
            .unwrap();

        assert_eq!(copied, b"0123");
    }

    #[test]
    fn a_file_that_shrinks_while_packing_fails_instead_of_shifting_the_archive() {
        let mut builder = tar::Builder::new(Vec::new());
        let mut header = tar::Header::new_gnu();
        header.set_entry_type(tar::EntryType::Regular);
        header.set_size(10);
        header.set_mode(FILE_MODE);

        let error = builder
            .append_data(
                &mut header,
                "logs/app.log",
                ExactLength::new(&b"0123"[..], 10),
            )
            .unwrap_err();

        assert_eq!(error.kind(), io::ErrorKind::UnexpectedEof);
        assert!(error.to_string().contains("got shorter"));
    }

    #[test]
    fn a_file_that_grows_while_packing_keeps_the_declared_size() {
        let mut builder = tar::Builder::new(Vec::new());
        let mut header = tar::Header::new_gnu();
        header.set_entry_type(tar::EntryType::Regular);
        header.set_size(4);
        header.set_mode(FILE_MODE);
        builder
            .append_data(
                &mut header,
                "logs/app.log",
                ExactLength::new(&b"0123456789"[..], 4),
            )
            .unwrap();
        header.set_size(2);
        builder
            .append_data(&mut header, "next.txt", ExactLength::new(&b"ok"[..], 2))
            .unwrap();
        let bytes = builder.into_inner().unwrap();

        let mut archive = tar::Archive::new(&bytes[..]);
        let entries = archive
            .entries()
            .unwrap()
            .map(|entry| {
                let mut entry = entry.unwrap();
                let mut content = String::new();
                entry.read_to_string(&mut content).unwrap();
                (entry.path().unwrap().to_string_lossy().to_string(), content)
            })
            .collect::<Vec<_>>();

        assert_eq!(
            entries,
            vec![
                ("logs/app.log".to_string(), "0123".to_string()),
                ("next.txt".to_string(), "ok".to_string()),
            ]
        );
    }

    #[test]
    fn limits_name_the_largest_files() {
        let project = tempfile::tempdir().unwrap();
        write(project.path(), "big.bin", &"x".repeat(4096));
        write(project.path(), "small.txt", "x");
        let selection = select(project.path()).unwrap();

        let too_many = check_limits(&selection, 1, u64::MAX).unwrap_err();
        let too_big = check_limits(&selection, 10, 1024).unwrap_err();

        assert_eq!(too_many.code, "snapshot_too_large");
        assert!(too_many.message.contains("2 files"));
        assert!(too_big.message.contains("big.bin"));
        assert!(check_limits(&selection, 10, 1024 * 1024).is_ok());
    }

    #[test]
    fn extraction_mirrors_the_project_and_keeps_sandbox_output() {
        let local = tempfile::tempdir().unwrap();
        let volume = tempfile::tempdir().unwrap();
        let sandbox = volume.path().join("project");
        write(local.path(), "src/app.py", "print(1)");
        write(local.path(), "src/old/legacy.py", "old");
        write(local.path(), "README.md", "readme");

        let first = unpack(&sandbox, snapshot_of(local.path())).unwrap();
        write(&sandbox, "node_modules/lib.js", "installed in the sandbox");
        fs::remove_dir_all(local.path().join("src/old")).unwrap();
        write(local.path(), "src/app.py", "print(2)");
        let second = unpack(&sandbox, snapshot_of(local.path())).unwrap();

        assert_eq!(first.result["firstSync"], json!(true));
        assert_eq!(first.result["files"], json!(3));
        assert_eq!(second.result["firstSync"], json!(false));
        assert_eq!(second.result["deleted"], json!(1));
        assert_eq!(
            fs::read_to_string(sandbox.join("src/app.py")).unwrap(),
            "print(2)"
        );
        assert!(!sandbox.join("src/old").exists());
        assert!(sandbox.join("node_modules/lib.js").exists());
        assert!(volume.path().join(MANIFEST_NAME).exists());
    }

    #[test]
    fn extraction_rejects_entries_outside_the_workspace() {
        let volume = tempfile::tempdir().unwrap();
        let mut builder = tar::Builder::new(GzEncoder::new(Vec::new(), Compression::fast()));
        let mut header = tar::Header::new_gnu();
        header.set_size(1);
        header.set_mode(FILE_MODE);
        header.set_entry_type(tar::EntryType::Regular);
        let name = b"../escape.txt";
        header.as_old_mut().name[..name.len()].copy_from_slice(name);
        header.set_cksum();
        builder.append(&header, &b"x"[..]).unwrap();
        let bytes = builder.into_inner().unwrap().finish().unwrap();
        let mut archive = tempfile::tempfile().unwrap();
        archive.write_all(&bytes).unwrap();
        archive.seek(SeekFrom::Start(0)).unwrap();

        let error = unpack(&volume.path().join("project"), archive).unwrap_err();

        assert_eq!(error.code, "invalid_archive");
        assert!(!volume.path().join("escape.txt").exists());
    }

    #[test]
    fn scripts_keep_an_executable_mode() {
        let local = tempfile::tempdir().unwrap();
        let volume = tempfile::tempdir().unwrap();
        let sandbox = volume.path().join("project");
        write(local.path(), "gradlew", "#!/bin/sh\necho ok\n");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(
                local.path().join("gradlew"),
                fs::Permissions::from_mode(0o755),
            )
            .unwrap();
        }
        let mut archive = tar::Archive::new(GzDecoder::new(snapshot_of(local.path())));
        let entry = archive.entries().unwrap().next().unwrap().unwrap();

        assert_eq!(entry.header().mode().unwrap(), SCRIPT_MODE);
        drop(entry);
        assert!(unpack(&sandbox, snapshot_of(local.path())).is_ok());
    }

    #[test]
    fn transfer_urls_stay_on_the_server() {
        assert_eq!(
            transfer_url(Some("http://localhost:8080/"), "/api/v1/transfers/abc").unwrap(),
            "http://localhost:8080/api/v1/transfers/abc"
        );
        assert_eq!(
            transfer_url(Some("http://localhost:8080"), "https://evil.test/x")
                .unwrap_err()
                .code,
            "invalid_arguments"
        );
        assert_eq!(
            transfer_url(None, "/api/v1/transfers/abc")
                .unwrap_err()
                .code,
            "transfer_unavailable"
        );
    }
}
