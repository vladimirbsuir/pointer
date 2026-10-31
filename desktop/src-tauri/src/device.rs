use std::fs;
use std::path::Path;

use serde::{Deserialize, Serialize};
use serde_json::json;
use uuid::Uuid;

use crate::config::CLIENT_VERSION;
use crate::error::AppResult;

const DEVICE_FILE: &str = "device.json";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceInfo {
    pub id: Uuid,
    pub name: String,
    pub platform: serde_json::Value,
    pub client_version: String,
}

#[derive(Serialize, Deserialize)]
struct StoredDevice {
    id: Uuid,
}

impl DeviceInfo {
    pub fn load_or_create(data_dir: &Path) -> AppResult<Self> {
        fs::create_dir_all(data_dir)?;
        let path = data_dir.join(DEVICE_FILE);
        let id = match fs::read_to_string(&path)
            .ok()
            .and_then(|content| serde_json::from_str::<StoredDevice>(&content).ok())
        {
            Some(stored) => stored.id,
            None => {
                let id = Uuid::now_v7();
                fs::write(&path, serde_json::to_string(&StoredDevice { id })?)?;
                id
            }
        };
        Ok(Self {
            id,
            name: std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Windows PC".to_string()),
            platform: json!({ "os": std::env::consts::OS, "arch": std::env::consts::ARCH }),
            client_version: CLIENT_VERSION.to_string(),
        })
    }
}
