use std::env;
use std::path::PathBuf;

pub struct Config {
    pub api_url: String,
    pub device_id: String,
    pub runner_secret: String,
    pub project_id: String,
    pub workspace: PathBuf,
    pub git_auth_file: Option<PathBuf>,
}

impl Config {
    pub fn from_env() -> Result<Self, String> {
        Ok(Self {
            api_url: required("POINTER_API_URL")?
                .trim_end_matches('/')
                .to_string(),
            device_id: required("POINTER_DEVICE_ID")?,
            runner_secret: required("POINTER_RUNNER_SECRET")?,
            project_id: required("POINTER_PROJECT_ID")?,
            workspace: env::var("POINTER_WORKSPACE")
                .map(PathBuf::from)
                .unwrap_or_else(|_| PathBuf::from("/workspace/repo")),
            git_auth_file: env::var("POINTER_GIT_AUTH_CONFIG")
                .ok()
                .filter(|value| !value.is_empty())
                .map(PathBuf::from),
        })
    }

    pub fn gateway_url(&self) -> String {
        format!(
            "{}/ws",
            self.api_url
                .replacen("https://", "wss://", 1)
                .replacen("http://", "ws://", 1)
        )
    }
}

fn required(name: &str) -> Result<String, String> {
    env::var(name)
        .ok()
        .filter(|value| !value.is_empty())
        .ok_or_else(|| format!("{name} is not set"))
}
