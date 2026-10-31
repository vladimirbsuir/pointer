pub const API_URL: &str = match option_env!("POINTER_API_URL") {
    Some(url) => url,
    None => "http://localhost:8080",
};

pub const CLIENT_VERSION: &str = env!("CARGO_PKG_VERSION");

pub const CREDENTIAL_SERVICE: &str = "dev.pointer.desktop";
