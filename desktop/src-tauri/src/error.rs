use serde::Serialize;

#[derive(Debug, Clone, Serialize, thiserror::Error)]
#[serde(rename_all = "camelCase")]
#[error("{code}: {message}")]
pub struct AppError {
    pub code: String,
    pub message: String,
    pub status: Option<u16>,
}

impl AppError {
    pub fn new(code: impl Into<String>, message: impl Into<String>) -> Self {
        Self {
            code: code.into(),
            message: message.into(),
            status: None,
        }
    }

    pub fn from_status(status: u16, code: impl Into<String>, message: impl Into<String>) -> Self {
        Self {
            code: code.into(),
            message: message.into(),
            status: Some(status),
        }
    }

    pub fn is_unauthorized(&self) -> bool {
        self.status == Some(401)
    }
}

impl From<reqwest::Error> for AppError {
    fn from(error: reqwest::Error) -> Self {
        AppError::new("network_error", error.to_string())
    }
}

impl From<keyring::Error> for AppError {
    fn from(error: keyring::Error) -> Self {
        AppError::new("credential_store_error", error.to_string())
    }
}

impl From<std::io::Error> for AppError {
    fn from(error: std::io::Error) -> Self {
        AppError::new("io_error", error.to_string())
    }
}

impl From<serde_json::Error> for AppError {
    fn from(error: serde_json::Error) -> Self {
        AppError::new("serialization_error", error.to_string())
    }
}

pub type AppResult<T> = Result<T, AppError>;
