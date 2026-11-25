use std::collections::{BTreeSet, HashMap};
use std::sync::{Mutex, OnceLock};

const KEYMAP_SETTLE_MS: u64 = 150;
const LATIN_1_FIRST: u32 = 0xa0;
const LATIN_1_LAST: u32 = 0xff;
const ASCII_FIRST: u32 = 0x20;
const ASCII_LAST: u32 = 0x7e;
const UNICODE_KEYSYM: u64 = 0x0100_0000;

static BOUND: OnceLock<Mutex<HashMap<String, Vec<u8>>>> = OnceLock::new();

pub fn keysym(character: char) -> Option<u64> {
    let code = u32::from(character);
    if code < ASCII_FIRST || code == 0x7f {
        return None;
    }
    if (ASCII_FIRST..=ASCII_LAST).contains(&code) || (LATIN_1_FIRST..=LATIN_1_LAST).contains(&code)
    {
        return Some(u64::from(code));
    }
    Some(UNICODE_KEYSYM | u64::from(code))
}

pub fn wanted(text: &str) -> BTreeSet<u64> {
    text.chars().filter_map(keysym).collect()
}

pub fn bind_missing(display: &str, text: &str) {
    let wanted = wanted(text);
    if wanted.is_empty() {
        return;
    }
    let bound = BOUND.get_or_init(|| Mutex::new(HashMap::new()));
    let mut bound = bound
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let ours = bound.entry(display.to_string()).or_default();
    if x11::bind(display, &wanted, ours) {
        std::thread::sleep(std::time::Duration::from_millis(KEYMAP_SETTLE_MS));
    }
}

#[cfg(unix)]
mod x11 {
    use std::collections::BTreeSet;
    use std::ffi::{CString, c_char, c_int, c_uchar, c_ulong, c_void};
    use std::sync::OnceLock;

    type OpenDisplay = unsafe extern "C" fn(*const c_char) -> *mut c_void;
    type CloseDisplay = unsafe extern "C" fn(*mut c_void) -> c_int;
    type DisplayKeycodes = unsafe extern "C" fn(*mut c_void, *mut c_int, *mut c_int) -> c_int;
    type GetKeyboardMapping =
        unsafe extern "C" fn(*mut c_void, c_uchar, c_int, *mut c_int) -> *mut c_ulong;
    type ChangeKeyboardMapping =
        unsafe extern "C" fn(*mut c_void, c_int, c_int, *const c_ulong, c_int) -> c_int;
    type Free = unsafe extern "C" fn(*mut c_void) -> c_int;
    type Sync = unsafe extern "C" fn(*mut c_void, c_int) -> c_int;

    static LIBRARY: OnceLock<Option<libloading::Library>> = OnceLock::new();

    struct Functions {
        open: OpenDisplay,
        close: CloseDisplay,
        keycodes: DisplayKeycodes,
        mapping: GetKeyboardMapping,
        change: ChangeKeyboardMapping,
        free: Free,
        sync: Sync,
    }

    fn functions() -> Option<Functions> {
        let library = LIBRARY
            .get_or_init(|| unsafe { libloading::Library::new("libX11.so.6").ok() })
            .as_ref()?;
        unsafe {
            Some(Functions {
                open: *library.get(b"XOpenDisplay\0").ok()?,
                close: *library.get(b"XCloseDisplay\0").ok()?,
                keycodes: *library.get(b"XDisplayKeycodes\0").ok()?,
                mapping: *library.get(b"XGetKeyboardMapping\0").ok()?,
                change: *library.get(b"XChangeKeyboardMapping\0").ok()?,
                free: *library.get(b"XFree\0").ok()?,
                sync: *library.get(b"XSync\0").ok()?,
            })
        }
    }

    pub fn bind(display: &str, wanted: &BTreeSet<u64>, ours: &mut Vec<u8>) -> bool {
        let Some(x) = functions() else {
            return false;
        };
        let Ok(name) = CString::new(display) else {
            return false;
        };
        unsafe {
            let connection = (x.open)(name.as_ptr());
            if connection.is_null() {
                return false;
            }
            let changed = bind_on(&x, connection, wanted, ours);
            (x.sync)(connection, 0);
            (x.close)(connection);
            changed
        }
    }

    unsafe fn bind_on(
        x: &Functions,
        connection: *mut c_void,
        wanted: &BTreeSet<u64>,
        ours: &mut Vec<u8>,
    ) -> bool {
        let (mut first, mut last) = (0, 0);
        unsafe { (x.keycodes)(connection, &mut first, &mut last) };
        let Ok(first_code) = u8::try_from(first) else {
            return false;
        };
        let count = last - first + 1;
        let mut width = 0;
        let table = unsafe { (x.mapping)(connection, first_code, count, &mut width) };
        if table.is_null() || width <= 0 {
            return false;
        }
        let width = width as usize;
        let symbols = unsafe { std::slice::from_raw_parts(table, count as usize * width) }.to_vec();
        unsafe { (x.free)(table.cast()) };
        let row = |code: u8| {
            let start = (usize::from(code) - first as usize) * width;
            &symbols[start..start + width]
        };
        let present: BTreeSet<u64> = symbols
            .iter()
            .map(|symbol| u64::from(*symbol))
            .filter(|symbol| *symbol != 0)
            .collect();
        let missing: Vec<u64> = wanted.difference(&present).copied().collect();
        if missing.is_empty() {
            return false;
        }
        let mut free: Vec<u8> = (first..=last)
            .filter_map(|code| u8::try_from(code).ok())
            .filter(|code| row(*code).iter().all(|symbol| *symbol == 0))
            .collect();
        if free.len() < missing.len() {
            let reusable: Vec<u8> = ours
                .iter()
                .copied()
                .filter(|code| {
                    row(*code)
                        .first()
                        .is_none_or(|symbol| !wanted.contains(&u64::from(*symbol)))
                })
                .collect();
            free.extend(reusable);
        }
        let mut changed = false;
        for (symbol, code) in missing.iter().zip(free) {
            let mut entry = vec![0 as c_ulong; width];
            entry[0] = *symbol as c_ulong;
            if width > 1 {
                entry[1] = *symbol as c_ulong;
            }
            unsafe {
                (x.change)(
                    connection,
                    c_int::from(code),
                    width as c_int,
                    entry.as_ptr(),
                    1,
                )
            };
            if !ours.contains(&code) {
                ours.push(code);
            }
            changed = true;
        }
        changed
    }
}

#[cfg(not(unix))]
mod x11 {
    use std::collections::BTreeSet;

    pub fn bind(_display: &str, _wanted: &BTreeSet<u64>, _ours: &mut Vec<u8>) -> bool {
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maps_characters_to_keysyms() {
        assert_eq!(keysym('a'), Some(0x61));
        assert_eq!(keysym('é'), Some(0xe9));
        assert_eq!(keysym('П'), Some(0x0100_041f));
        assert_eq!(keysym('😀'), Some(0x0101_f600));
        assert_eq!(keysym('\n'), None);
        assert_eq!(wanted("aab\t"), BTreeSet::from([0x61, 0x62]));
    }
}
