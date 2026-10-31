use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use crate::error::{AppError, AppResult};

const PROJECTS_FILE: &str = "projects.json";

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
