use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::error::{AppError, AppResult};

const CONFIG_FILE: &str = "mcp.json";
const MAX_NAME_LENGTH: usize = 32;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerConfig {
    pub name: String,
    #[serde(default = "enabled_by_default")]
    pub enabled: bool,
    #[serde(flatten)]
    pub transport: TransportConfig,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "transport", rename_all = "camelCase")]
pub enum TransportConfig {
    Stdio {
        command: String,
        #[serde(default)]
        args: Vec<String>,
        #[serde(default)]
        env: BTreeMap<String, String>,
    },
    Http {
        url: String,
        #[serde(default)]
        headers: BTreeMap<String, String>,
    },
}

#[derive(Default, Serialize, Deserialize)]
struct ConfigFile {
    #[serde(default)]
    servers: Vec<ServerConfig>,
}

fn enabled_by_default() -> bool {
    true
}

pub fn config_path(data_dir: &Path) -> PathBuf {
    data_dir.join(CONFIG_FILE)
}

pub fn load(file: &Path) -> Vec<ServerConfig> {
    fs::read_to_string(file)
        .ok()
        .and_then(|content| serde_json::from_str::<ConfigFile>(&content).ok())
        .map(|config| config.servers)
        .unwrap_or_default()
}

pub fn save(file: &Path, servers: &[ServerConfig]) -> AppResult<()> {
    let content = serde_json::to_string_pretty(&ConfigFile {
        servers: servers.to_vec(),
    })?;
    fs::write(file, content)?;
    Ok(())
}

pub fn validate(config: &ServerConfig) -> AppResult<()> {
    let valid_name = !config.name.is_empty()
        && config.name.len() <= MAX_NAME_LENGTH
        && config
            .name
            .chars()
            .all(|char| char.is_ascii_alphanumeric() || char == '-' || char == '_');
    if !valid_name {
        return Err(AppError::new(
            "invalid_mcp_name",
            "Use up to 32 Latin letters, digits, dashes or underscores",
        ));
    }
    let valid_target = match &config.transport {
        TransportConfig::Stdio { command, .. } => !command.trim().is_empty(),
        TransportConfig::Http { url, .. } => {
            url.starts_with("http://") || url.starts_with("https://")
        }
    };
    if !valid_target {
        return Err(AppError::new(
            "invalid_mcp_target",
            "Enter a command or an http(s) URL",
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn config_round_trips_both_transports() {
        let dir = tempfile::tempdir().unwrap();
        let file = config_path(dir.path());
        let servers = vec![
            ServerConfig {
                name: "files".into(),
                enabled: true,
                transport: TransportConfig::Stdio {
                    command: "npx".into(),
                    args: vec!["-y".into(), "server".into()],
                    env: BTreeMap::from([("TOKEN".into(), "secret".into())]),
                },
            },
            ServerConfig {
                name: "remote".into(),
                enabled: false,
                transport: TransportConfig::Http {
                    url: "https://example.com/mcp".into(),
                    headers: BTreeMap::new(),
                },
            },
        ];

        save(&file, &servers).unwrap();

        assert_eq!(load(&file), servers);
    }

    #[test]
    fn names_must_fit_tool_name_limits() {
        let config = |name: &str| ServerConfig {
            name: name.into(),
            enabled: true,
            transport: TransportConfig::Stdio {
                command: "node".into(),
                args: vec![],
                env: BTreeMap::new(),
            },
        };

        assert!(validate(&config("github_tools-2")).is_ok());
        assert!(validate(&config("with space")).is_err());
        assert!(validate(&config(&"x".repeat(33))).is_err());
    }
}
