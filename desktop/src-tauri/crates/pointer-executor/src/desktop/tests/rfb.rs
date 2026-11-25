use std::io::{Read, Write};
use std::net::TcpStream;
use std::time::{Duration, Instant};

use des::Des;
use des::cipher::generic_array::GenericArray;
use des::cipher::{BlockEncrypt, KeyInit};

const VERSION: &[u8; 12] = b"RFB 003.008\n";
const VNC_AUTHENTICATION: u8 = 2;
const RAW_ENCODING: i32 = 0;
const BYTES_PER_PIXEL: usize = 4;
const READ_TIMEOUT: Duration = Duration::from_secs(10);

pub enum Login {
    Accepted(Client),
    Refused(String),
}

pub struct Client {
    stream: TcpStream,
    pub width: u16,
    pub height: u16,
}

pub fn connect(port: u16, password: &str) -> Login {
    let mut stream = TcpStream::connect(("127.0.0.1", port)).expect("connect to the VNC server");
    stream.set_read_timeout(Some(READ_TIMEOUT)).unwrap();
    let mut version = [0u8; 12];
    stream.read_exact(&mut version).unwrap();
    assert_eq!(&version, VERSION, "x11vnc speaks RFB 3.8");
    stream.write_all(VERSION).unwrap();

    let count = read_u8(&mut stream);
    assert!(count > 0, "the server offered no security type");
    let mut types = vec![0u8; usize::from(count)];
    stream.read_exact(&mut types).unwrap();
    assert!(
        types.contains(&VNC_AUTHENTICATION),
        "VNC authentication is offered: {types:?}"
    );
    stream.write_all(&[VNC_AUTHENTICATION]).unwrap();

    let mut challenge = [0u8; 16];
    stream.read_exact(&mut challenge).unwrap();
    stream.write_all(&respond(&challenge, password)).unwrap();
    if read_u32(&mut stream) != 0 {
        let length = read_u32(&mut stream) as usize;
        let mut reason = vec![0u8; length];
        stream.read_exact(&mut reason).unwrap();
        return Login::Refused(String::from_utf8_lossy(&reason).into_owned());
    }

    stream.write_all(&[1]).unwrap();
    let width = read_u16(&mut stream);
    let height = read_u16(&mut stream);
    let mut pixel_format = [0u8; 16];
    stream.read_exact(&mut pixel_format).unwrap();
    let name_length = read_u32(&mut stream) as usize;
    let mut name = vec![0u8; name_length];
    stream.read_exact(&mut name).unwrap();

    let mut client = Client {
        stream,
        width,
        height,
    };
    client.set_pixel_format();
    client.set_encodings();
    Login::Accepted(client)
}

fn respond(challenge: &[u8; 16], password: &str) -> [u8; 16] {
    let mut key = [0u8; 8];
    for (slot, byte) in key.iter_mut().zip(password.bytes()) {
        *slot = byte.reverse_bits();
    }
    let cipher = Des::new_from_slice(&key).unwrap();
    let mut response = *challenge;
    for chunk in response.chunks_mut(8) {
        cipher.encrypt_block(GenericArray::from_mut_slice(chunk));
    }
    response
}

impl Client {
    fn set_pixel_format(&mut self) {
        let mut message = vec![0u8, 0, 0, 0];
        message.extend_from_slice(&[32, 24, 0, 1]);
        message.extend_from_slice(&255u16.to_be_bytes());
        message.extend_from_slice(&255u16.to_be_bytes());
        message.extend_from_slice(&255u16.to_be_bytes());
        message.extend_from_slice(&[16, 8, 0, 0, 0, 0]);
        self.stream.write_all(&message).unwrap();
    }

    fn set_encodings(&mut self) {
        let mut message = vec![2u8, 0];
        message.extend_from_slice(&1u16.to_be_bytes());
        message.extend_from_slice(&RAW_ENCODING.to_be_bytes());
        self.stream.write_all(&message).unwrap();
    }

    pub fn framebuffer(&mut self) -> Vec<[u8; 3]> {
        let (width, height) = (usize::from(self.width), usize::from(self.height));
        let mut pixels = vec![[0u8; 3]; width * height];
        let mut covered = vec![false; width * height];
        let mut remaining = width * height;
        let deadline = Instant::now() + READ_TIMEOUT;
        self.request_update();
        while remaining > 0 {
            assert!(
                Instant::now() < deadline,
                "the framebuffer was not complete in time"
            );
            match read_u8(&mut self.stream) {
                0 => {
                    let mut padding = [0u8; 1];
                    self.stream.read_exact(&mut padding).unwrap();
                    let rectangles = read_u16(&mut self.stream);
                    for _ in 0..rectangles {
                        remaining -= self.read_rectangle(&mut pixels, &mut covered);
                    }
                    if remaining > 0 {
                        self.request_update();
                    }
                }
                2 => {}
                3 => {
                    let mut padding = [0u8; 3];
                    self.stream.read_exact(&mut padding).unwrap();
                    let length = read_u32(&mut self.stream) as usize;
                    let mut text = vec![0u8; length];
                    self.stream.read_exact(&mut text).unwrap();
                }
                other => panic!("unexpected server message {other}"),
            }
        }
        pixels
    }

    fn read_rectangle(&mut self, pixels: &mut [[u8; 3]], covered: &mut [bool]) -> usize {
        let x = usize::from(read_u16(&mut self.stream));
        let y = usize::from(read_u16(&mut self.stream));
        let width = usize::from(read_u16(&mut self.stream));
        let height = usize::from(read_u16(&mut self.stream));
        let encoding = read_i32(&mut self.stream);
        if encoding != RAW_ENCODING {
            assert!(encoding < 0, "unexpected encoding {encoding}");
            return 0;
        }
        let mut data = vec![0u8; width * height * BYTES_PER_PIXEL];
        self.stream.read_exact(&mut data).unwrap();
        let mut newly_covered = 0;
        for row in 0..height {
            for column in 0..width {
                let offset = (row * width + column) * BYTES_PER_PIXEL;
                let index = (y + row) * usize::from(self.width) + x + column;
                pixels[index] = [data[offset + 2], data[offset + 1], data[offset]];
                if !covered[index] {
                    covered[index] = true;
                    newly_covered += 1;
                }
            }
        }
        newly_covered
    }

    fn request_update(&mut self) {
        let mut message = vec![3u8, 0];
        for value in [0, 0, self.width, self.height] {
            message.extend_from_slice(&value.to_be_bytes());
        }
        self.stream.write_all(&message).unwrap();
    }

    pub fn click(&mut self, x: u16, y: u16) {
        for buttons in [0u8, 1, 0] {
            let mut message = vec![5u8, buttons];
            message.extend_from_slice(&x.to_be_bytes());
            message.extend_from_slice(&y.to_be_bytes());
            self.stream.write_all(&message).unwrap();
        }
        self.stream.flush().unwrap();
    }
}

pub fn websocket_greeting(port: u16) -> (String, Vec<u8>) {
    let mut stream = TcpStream::connect(("127.0.0.1", port)).expect("connect to websockify");
    stream.set_read_timeout(Some(READ_TIMEOUT)).unwrap();
    let request = format!(
        "GET / HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\
         Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\
         Sec-WebSocket-Protocol: binary\r\n\r\n"
    );
    stream.write_all(request.as_bytes()).unwrap();
    let mut head = Vec::new();
    while !head.ends_with(b"\r\n\r\n") {
        head.push(read_u8(&mut stream));
    }
    let opcode = read_u8(&mut stream) & 0x0f;
    assert_eq!(opcode, 2, "websockify sends binary frames");
    let length = usize::from(read_u8(&mut stream) & 0x7f);
    let mut payload = vec![0u8; length];
    stream.read_exact(&mut payload).unwrap();
    (String::from_utf8_lossy(&head).into_owned(), payload)
}

fn read_u8(stream: &mut TcpStream) -> u8 {
    let mut buffer = [0u8; 1];
    stream.read_exact(&mut buffer).unwrap();
    buffer[0]
}

fn read_u16(stream: &mut TcpStream) -> u16 {
    let mut buffer = [0u8; 2];
    stream.read_exact(&mut buffer).unwrap();
    u16::from_be_bytes(buffer)
}

fn read_u32(stream: &mut TcpStream) -> u32 {
    let mut buffer = [0u8; 4];
    stream.read_exact(&mut buffer).unwrap();
    u32::from_be_bytes(buffer)
}

fn read_i32(stream: &mut TcpStream) -> i32 {
    let mut buffer = [0u8; 4];
    stream.read_exact(&mut buffer).unwrap();
    i32::from_be_bytes(buffer)
}
