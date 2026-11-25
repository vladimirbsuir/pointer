use crate::ExecError;

const ALIASES: [&str; 6] = ["alt", "ctrl", "control", "meta", "super", "shift"];

pub fn validate(keys: &str) -> Result<(), ExecError> {
    let names: Vec<&str> = keys.split('+').map(str::trim).collect();
    if names.is_empty() || names.iter().any(|name| name.is_empty()) {
        return Err(invalid(keys));
    }
    match names.iter().find(|name| !is_known(name)) {
        Some(_) => Err(invalid(keys)),
        None => Ok(()),
    }
}

fn is_known(name: &str) -> bool {
    ALIASES.contains(&name) || keysym_exists(name)
}

fn invalid(keys: &str) -> ExecError {
    ExecError::rejected(
        "invalid_key",
        format!(
            "{keys} contains a key name that does not exist; use X keysym names such as Return, Tab, Escape, ctrl+s"
        ),
    )
}

#[cfg(unix)]
fn keysym_exists(name: &str) -> bool {
    use std::ffi::{CString, c_char, c_ulong};
    use std::sync::OnceLock;

    type StringToKeysym = unsafe extern "C" fn(*const c_char) -> c_ulong;
    static LIBRARY: OnceLock<Option<libloading::Library>> = OnceLock::new();

    let Ok(name) = CString::new(name) else {
        return false;
    };
    let library = LIBRARY.get_or_init(|| unsafe { libloading::Library::new("libX11.so.6").ok() });
    let Some(library) = library else {
        return false;
    };
    unsafe {
        library
            .get::<StringToKeysym>(b"XStringToKeysym\0")
            .map(|function| function(name.as_ptr()) != 0)
            .unwrap_or(false)
    }
}

#[cfg(not(unix))]
fn keysym_exists(_name: &str) -> bool {
    false
}
