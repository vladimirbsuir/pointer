use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::RwLock;

pub struct GitAuth {
    file: Option<PathBuf>,
    url_prefix: String,
    token: RwLock<Option<String>>,
}

impl GitAuth {
    pub fn new(api_url: &str, file: Option<PathBuf>) -> Self {
        Self {
            file,
            url_prefix: format!("{}/api/v1/runner/git/", api_url.trim_end_matches('/')),
            token: RwLock::new(None),
        }
    }

    pub fn update(&self, token: &str) {
        if let Ok(mut current) = self.token.write() {
            *current = Some(token.to_string());
        }
        if let Some(file) = &self.file
            && let Err(error) = write_config(file, &self.url_prefix, token)
        {
            eprintln!("pointer-runner: cannot write the git credentials: {error}");
        }
    }

    pub fn apply(&self, command: &mut Command) {
        if self.file.is_some() {
            return;
        }
        let Some(token) = self.token.read().ok().and_then(|token| token.clone()) else {
            return;
        };
        command
            .env("GIT_CONFIG_COUNT", "1")
            .env("GIT_CONFIG_KEY_0", self.header_key())
            .env("GIT_CONFIG_VALUE_0", header_value(&token));
    }

    fn header_key(&self) -> String {
        format!("http.{}.extraHeader", self.url_prefix)
    }
}

fn header_value(token: &str) -> String {
    format!("Authorization: Bearer {token}")
}

fn write_config(file: &Path, url_prefix: &str, token: &str) -> std::io::Result<()> {
    if let Some(parent) = file.parent() {
        fs::create_dir_all(parent)?;
    }
    let temporary = file.with_extension("tmp");
    let mut options = fs::OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut output = options.open(&temporary)?;
    write!(
        output,
        "[http \"{url_prefix}\"]\n\textraHeader = {}\n",
        header_value(token)
    )?;
    output.sync_all()?;
    drop(output);
    fs::rename(&temporary, file)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn writes_a_url_scoped_header_to_the_configured_file() {
        let directory = tempfile::tempdir().unwrap();
        let file = directory.path().join("auth").join("git.conf");
        let auth = GitAuth::new("http://backend:8080/", Some(file.clone()));

        auth.update("first");
        auth.update("second");

        let written = fs::read_to_string(&file).unwrap();
        assert_eq!(
            written,
            "[http \"http://backend:8080/api/v1/runner/git/\"]\n\textraHeader = Authorization: Bearer second\n"
        );
        let mut command = Command::new("git");
        auth.apply(&mut command);
        assert_eq!(command.get_envs().count(), 0);
    }

    #[test]
    fn passes_the_header_in_the_environment_without_a_file() {
        let auth = GitAuth::new("http://backend:8080", None);
        let mut command = Command::new("git");
        auth.apply(&mut command);
        assert_eq!(command.get_envs().count(), 0);

        auth.update("token");
        auth.apply(&mut command);
        let value = command
            .get_envs()
            .find(|(key, _)| *key == "GIT_CONFIG_KEY_0")
            .and_then(|(_, value)| value.map(|value| value.to_string_lossy().into_owned()));
        assert_eq!(
            value.as_deref(),
            Some("http.http://backend:8080/api/v1/runner/git/.extraHeader")
        );
    }
}
