use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use crate::error::{AppError, AppResult};

const PROJECTS_FILE: &str = "projects.json";

pub fn github_repository(root: &Path) -> Option<String> {
    let config = fs::read_to_string(root.join(".git").join("config")).ok()?;
    origin_url(&config).and_then(|url| repository_from_url(&url))
}

fn origin_url(config: &str) -> Option<String> {
    let mut in_origin = false;
    for line in config.lines().map(str::trim) {
        if line.starts_with('[') {
            in_origin = line == "[remote \"origin\"]";
            continue;
        }
        if in_origin
            && let Some(value) = line.strip_prefix("url")
            && let Some(url) = value.trim_start().strip_prefix('=')
        {
            return Some(url.trim().to_string());
        }
    }
    None
}

fn repository_from_url(url: &str) -> Option<String> {
    let path = url
        .strip_prefix("https://github.com/")
        .or_else(|| url.strip_prefix("http://github.com/"))
        .or_else(|| url.strip_prefix("git@github.com:"))
        .or_else(|| url.strip_prefix("ssh://git@github.com/"))?;
    let path = path.trim_end_matches('/').trim_end_matches(".git");
    let mut parts = path.split('/');
    let owner = parts.next().filter(|part| !part.is_empty())?;
    let name = parts.next().filter(|part| !part.is_empty())?;
    parts.next().is_none().then(|| format!("{owner}/{name}"))
}

pub struct ProjectMap {
    file: PathBuf,
    paths: Mutex<HashMap<String, String>>,
}

impl ProjectMap {
    pub fn load(data_dir: &Path) -> AppResult<Self> {
        fs::create_dir_all(data_dir)?;
        let file = data_dir.join(PROJECTS_FILE);
        let paths = fs::read_to_string(&file)
            .ok()
            .and_then(|content| serde_json::from_str(&content).ok())
            .unwrap_or_default();
        Ok(Self {
            file,
            paths: Mutex::new(paths),
        })
    }

    pub fn all(&self) -> HashMap<String, String> {
        self.paths
            .lock()
            .map(|paths| paths.clone())
            .unwrap_or_default()
    }

    pub fn path(&self, project_id: &str) -> Option<PathBuf> {
        self.paths
            .lock()
            .ok()
            .and_then(|paths| paths.get(project_id).map(PathBuf::from))
    }

    pub fn project_ids(&self) -> Vec<String> {
        self.all().into_keys().collect()
    }

    pub fn link(&self, project_id: &str, path: &str) -> AppResult<()> {
        if !Path::new(path).is_dir() {
            return Err(AppError::new(
                "folder_not_found",
                format!("{path} is not a folder"),
            ));
        }
        self.update(|paths| {
            paths.insert(project_id.to_string(), path.to_string());
        })
    }

    pub fn unlink(&self, project_id: &str) -> AppResult<()> {
        self.update(|paths| {
            paths.remove(project_id);
        })
    }

    fn update(&self, change: impl FnOnce(&mut HashMap<String, String>)) -> AppResult<()> {
        let mut paths = self
            .paths
            .lock()
            .map_err(|_| AppError::new("internal_error", "Project map is unavailable"))?;
        change(&mut paths);
        fs::write(&self.file, serde_json::to_string_pretty(&*paths)?)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_github_repository_from_origin() {
        let config = "[core]
	bare = false
[remote \"upstream\"]
	url = https://github.com/other/repo.git
[remote \"origin\"]
	url = git@github.com:octo/demo.git
";
        assert_eq!(
            origin_url(config).and_then(|url| repository_from_url(&url)),
            Some("octo/demo".to_string())
        );
        assert_eq!(
            repository_from_url("https://github.com/octo/demo"),
            Some("octo/demo".to_string())
        );
        assert_eq!(
            repository_from_url("https://gitlab.com/octo/demo.git"),
            None
        );
    }
}
