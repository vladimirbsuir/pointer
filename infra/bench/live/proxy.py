import json
import socket
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

LISTEN = ("127.0.0.1", 8081)
BACKEND = ("127.0.0.1", 8080)
CONTROL = ("127.0.0.1", 8082)

lock = threading.Lock()
log_lines = []
state = {"drop": 0, "dropped": [], "connections": [], "blocked_until": 0.0, "refused": 0}
active = []


def log(line):
    stamp = time.strftime("%H:%M:%S")
    with lock:
        log_lines.append(f"{stamp} {line}")
        del log_lines[:-5000]
    print(f"{stamp} {line}", flush=True)


def read_exact(sock, n):
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise ConnectionError("closed")
        data += chunk
    return data


def read_headers(sock):
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = sock.recv(65536)
        if not chunk:
            raise ConnectionError("closed")
        data += chunk
    return data


def read_frame(sock):
    head = read_exact(sock, 2)
    fin = head[0] & 0x80
    opcode = head[0] & 0x0F
    masked = head[1] & 0x80
    length = head[1] & 0x7F
    raw = head
    if length == 126:
        ext = read_exact(sock, 2)
        raw += ext
        length = struct.unpack(">H", ext)[0]
    elif length == 127:
        ext = read_exact(sock, 8)
        raw += ext
        length = struct.unpack(">Q", ext)[0]
    key = b""
    if masked:
        key = read_exact(sock, 4)
        raw += key
    payload = read_exact(sock, length)
    raw += payload
    if masked:
        payload = bytes(b ^ key[i % 4] for i, b in enumerate(payload))
    return fin, opcode, payload, raw


def close_frame(code, reason=b""):
    body = struct.pack(">H", code) + reason
    return bytes([0x88, len(body)]) + body


class Connection:
    def __init__(self, number, client, upstream):
        self.number = number
        self.client = client
        self.upstream = upstream
        self.opened = time.time()
        self.closed = None
        self.server_close = None
        self.client_close = None
        self.stalled = False
        self.subscribes = []
        self.events = 0
        self.pings = 0
        self.pongs = 0

    def view(self):
        return {
            "number": self.number,
            "opened": time.strftime("%H:%M:%S", time.localtime(self.opened)),
            "ageSeconds": round((self.closed or time.time()) - self.opened),
            "closed": self.closed is not None,
            "serverClose": self.server_close,
            "clientClose": self.client_close,
            "stalled": self.stalled,
            "subscribes": self.subscribes,
            "events": self.events,
            "pings": self.pings,
            "pongs": self.pongs,
        }

    def shutdown(self):
        for sock in (self.client, self.upstream):
            try:
                sock.close()
            except OSError:
                pass
        if self.closed is None:
            self.closed = time.time()


def describe(payload):
    try:
        message = json.loads(payload)
    except ValueError:
        return None, None
    return message.get("type"), message


def pump_down(conn):
    try:
        while True:
            fin, opcode, payload, raw = read_frame(conn.upstream)
            if conn.stalled:
                continue
            if opcode == 0x9:
                conn.pings += 1
            if opcode == 0x8:
                conn.server_close = struct.unpack(">H", payload[:2])[0] if len(payload) >= 2 else None
                log(f"#{conn.number} server close {conn.server_close} {payload[2:].decode(errors='replace')}")
            if opcode == 0x1:
                kind, message = describe(payload)
                if kind == "session.event":
                    event = message.get("payload", {})
                    seq = event.get("seq")
                    conn.events += 1
                    with lock:
                        drop = state["drop"] > 0 and seq is not None
                        if drop:
                            state["drop"] -= 1
                            state["dropped"].append(seq)
                    inner = event.get("event", {})
                    kind_inner = inner.get("type") if isinstance(inner, dict) else None
                    log(f"#{conn.number} down session.event seq={seq} {event.get('kind') or kind_inner}{' DROPPED' if drop else ''}")
                    if drop:
                        continue
                elif kind == "exec.request":
                    request = message.get("payload", {})
                    log(f"#{conn.number} down exec.request callId={request.get('callId')} op={request.get('operation')} dryRun={request.get('dryRun')}")
                elif kind is not None and kind not in ("session.delta",):
                    log(f"#{conn.number} down {kind}")
            conn.client.sendall(raw)
    except (ConnectionError, OSError) as error:
        log(f"#{conn.number} upstream ended: {error}")
    finally:
        conn.shutdown()


def pump_up(conn):
    try:
        while True:
            fin, opcode, payload, raw = read_frame(conn.client)
            if opcode == 0xA:
                conn.pongs += 1
            if opcode == 0x8:
                conn.client_close = struct.unpack(">H", payload[:2])[0] if len(payload) >= 2 else None
                log(f"#{conn.number} client close {conn.client_close}")
            if opcode == 0x1:
                kind, message = describe(payload)
                if kind == "session.subscribe":
                    conn.subscribes.append(message.get("payload"))
                    log(f"#{conn.number} up session.subscribe {json.dumps(message.get('payload'))}")
                elif kind == "hello":
                    undelivered = message.get("payload", {}).get("undeliveredResults") or []
                    log(f"#{conn.number} up hello undeliveredResults={[(r.get('callId'), r.get('status')) for r in undelivered]}")
                elif kind == "exec.result":
                    result = message.get("payload", {})
                    log(f"#{conn.number} up exec.result callId={result.get('callId')} status={result.get('status')}{' (LOST IN STALL)' if conn.stalled else ''}")
                elif kind is not None:
                    log(f"#{conn.number} up {kind}")
            if conn.stalled:
                continue
            conn.upstream.sendall(raw)
    except (ConnectionError, OSError) as error:
        log(f"#{conn.number} client ended: {error}")
    finally:
        conn.shutdown()


def plain_pump(source, target):
    try:
        while True:
            data = source.recv(65536)
            if not data:
                break
            target.sendall(data)
    except OSError:
        pass
    finally:
        for sock in (source, target):
            try:
                sock.close()
            except OSError:
                pass


def handle(client):
    try:
        request = read_headers(client)
    except (ConnectionError, OSError):
        client.close()
        return
    try:
        upstream = socket.create_connection(BACKEND)
    except OSError as error:
        log(f"backend unreachable: {error}")
        client.close()
        return
    head = request.split(b"\r\n\r\n", 1)[0].decode(errors="replace").lower()
    if "upgrade: websocket" not in head:
        upstream.sendall(request)
        threading.Thread(target=plain_pump, args=(upstream, client), daemon=True).start()
        plain_pump(client, upstream)
        return
    if time.time() < state["blocked_until"]:
        with lock:
            state["refused"] += 1
        log("refused a websocket connection (blocked)")
        upstream.close()
        client.close()
        return
    upstream.sendall(request)
    response = read_headers(upstream)
    header, rest = response.split(b"\r\n\r\n", 1)
    client.sendall(header + b"\r\n\r\n")
    with lock:
        number = len(state["connections"]) + 1
        conn = Connection(number, client, upstream)
        state["connections"].append(conn)
    status = header.split(b"\r\n", 1)[0].decode(errors="replace")
    extensions = [line for line in header.decode(errors="replace").split("\r\n") if "extensions" in line.lower()]
    log(f"#{number} websocket opened: {status} {extensions}")
    if rest:
        client.sendall(rest)
    threading.Thread(target=pump_down, args=(conn,), daemon=True).start()
    pump_up(conn)


def current():
    with lock:
        live = [conn for conn in state["connections"] if conn.closed is None]
    return live[-1] if live else None


class Control(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, body):
        data = json.dumps(body, indent=1).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        url = urlparse(self.path)
        query = {key: values[0] for key, values in parse_qs(url.query).items()}
        if url.path == "/drop":
            with lock:
                state["drop"] += int(query.get("n", "1"))
            log(f"control: drop {query.get('n', '1')}")
            return self.reply({"ok": True})
        if url.path == "/close":
            conn = current()
            if conn is None:
                return self.reply({"ok": False})
            code = int(query.get("code", "4426"))
            log(f"control: close #{conn.number} with {code}")
            try:
                conn.client.sendall(close_frame(code, query.get("reason", "Injected").encode()))
                time.sleep(0.3)
            except OSError:
                pass
            conn.server_close = code
            conn.shutdown()
            return self.reply({"ok": True, "number": conn.number})
        if url.path == "/cut":
            conn = current()
            seconds = float(query.get("block", "0"))
            with lock:
                state["blocked_until"] = time.time() + seconds
            if conn is None:
                return self.reply({"ok": False})
            log(f"control: cut #{conn.number}, block new connections for {seconds}s")
            conn.shutdown()
            return self.reply({"ok": True, "number": conn.number})
        if url.path == "/stall":
            conn = current()
            if conn is None:
                return self.reply({"ok": False})
            conn.stalled = True
            log(f"control: stall #{conn.number}")
            return self.reply({"ok": True, "number": conn.number})
        if url.path == "/state":
            with lock:
                return self.reply({
                    "drop": state["drop"],
                    "refused": state["refused"],
                    "dropped": state["dropped"],
                    "connections": [conn.view() for conn in state["connections"]],
                })
        if url.path == "/log":
            with lock:
                return self.reply(log_lines[-int(query.get("n", "200")):])
        self.send_response(404)
        self.end_headers()


def main():
    threading.Thread(target=ThreadingHTTPServer(CONTROL, Control).serve_forever, daemon=True).start()
    server = socket.socket()
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(LISTEN)
    server.listen(64)
    log(f"proxy {LISTEN} -> {BACKEND}, control {CONTROL}")
    while True:
        client, _ = server.accept()
        threading.Thread(target=handle, args=(client,), daemon=True).start()


if __name__ == "__main__":
    main()
