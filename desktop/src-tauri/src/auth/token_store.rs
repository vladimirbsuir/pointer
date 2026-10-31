use keyring::Entry;

use crate::config::CREDENTIAL_SERVICE;
use crate::error::AppResult;

const REFRESH_TOKEN_ACCOUNT: &str = "refresh-token";

pub struct TokenStore {
    entry: Entry,
}

impl TokenStore {
    pub fn new() -> AppResult<Self> {
        Ok(Self {
            entry: Entry::new(CREDENTIAL_SERVICE, REFRESH_TOKEN_ACCOUNT)?,
        })
    }

    pub fn load(&self) -> AppResult<Option<String>> {
        match self.entry.get_password() {
            Ok(token) => Ok(Some(token)),
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(error) => Err(error.into()),
        }
    }

    pub fn save(&self, token: &str) -> AppResult<()> {
        Ok(self.entry.set_password(token)?)
    }

    pub fn clear(&self) -> AppResult<()> {
        match self.entry.delete_credential() {
            Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
            Err(error) => Err(error.into()),
        }
    }
}
