use std::path::{Component, Path, PathBuf};

use super::ExecError;

pub fn resolve(root: &Path, requested: &str) -> Result<PathBuf, ExecError> {
    let requested = if requested.trim().is_empty() {
        "."
    } else {
        requested.trim()
    };
    let candidate = Path::new(requested);
    let joined = if candidate.is_absolute() {
        candidate.to_path_buf()
    } else {
        root.join(candidate)
    };
    let normalized = normalize(&joined);
    let normalized_root = normalize(root);
    if !normalized.starts_with(&normalized_root) {
        return Err(outside(requested));
    }
    if let (Ok(real), Ok(real_root)) = (normalized.canonicalize(), normalized_root.canonicalize())
        && !real.starts_with(&real_root)
    {
        return Err(outside(requested));
    }
    Ok(normalized)
}

pub fn relative(root: &Path, path: &Path) -> String {
    path.strip_prefix(normalize(root))
        .unwrap_or(path)
        .to_string_lossy()
        .replace('\\', "/")
}

fn normalize(path: &Path) -> PathBuf {
    let mut result = PathBuf::new();
    for component in path.components() {
        match component {
            Component::ParentDir => {
                result.pop();
            }
            Component::CurDir => {}
            other => result.push(other.as_os_str()),
        }
    }
    result
}

fn outside(requested: &str) -> ExecError {
    ExecError::rejected(
        "path_outside_project",
        format!("{requested} is outside the project folder"),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(windows)]
    const FOREIGN_ABSOLUTE_PATH: &str = "C:/Windows/System32/drivers/etc/hosts";
    #[cfg(not(windows))]
    const FOREIGN_ABSOLUTE_PATH: &str = "/etc/hosts";

    #[test]
    fn resolves_relative_paths_inside_root() {
        let root = tempfile::tempdir().unwrap();
        let resolved = resolve(root.path(), "src/main.rs").unwrap();

        assert!(resolved.ends_with("src/main.rs"));
        assert_eq!(relative(root.path(), &resolved), "src/main.rs");
    }

    #[test]
    fn rejects_parent_traversal_and_foreign_absolute_paths() {
        let root = tempfile::tempdir().unwrap();

        assert!(resolve(root.path(), "../secret.txt").is_err());
        assert!(resolve(root.path(), "src/../../secret.txt").is_err());
        assert!(resolve(root.path(), FOREIGN_ABSOLUTE_PATH).is_err());
    }

    #[test]
    fn allows_traversal_that_stays_inside() {
        let root = tempfile::tempdir().unwrap();

        assert!(resolve(root.path(), "src/../README.md").is_ok());
        assert!(resolve(root.path(), ".").is_ok());
    }
}
