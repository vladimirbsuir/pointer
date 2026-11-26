import json
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 18090
requests_log = []


def text_of(message):
    content = message.get("content")
    if isinstance(content, list):
        return " ".join(part.get("text", "") for part in content if isinstance(part, dict))
    return content or ""


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path.endswith("/models"):
            body = json.dumps({"object": "list", "data": [{"id": "fake-model", "object": "model"}]}).encode()
        elif self.path == "/log":
            body = json.dumps(requests_log[-20:], indent=1).encode()
        else:
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(length) or b"{}")
        messages = request.get("messages", [])
        last = messages[-1] if messages else {}
        has_tools = bool(request.get("tools"))
        users = [text_of(m) for m in messages if m.get("role") == "user"]
        last_user = users[-1] if users else ""
        requests_log.append({"role": last.get("role"), "lastUser": last_user[:200], "tools": has_tools,
                             "lastText": text_of(last)[:2000]})
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        if not has_tools:
            self.stream_text("Live check session", 0)
        elif last.get("role") == "tool":
            self.stream_text("Tool finished: " + text_of(last)[:300], 0)
        elif "RUN " in last_user and last.get("role") == "user":
            arguments = last_user.split("RUN ", 1)[1].strip()
            self.stream_tool_call("run_command", arguments)
        elif "LONG" in last_user:
            self.stream_text(" ".join(f"word{i}" for i in range(80)), 0.1)
        else:
            self.stream_text("Hello from the fake model.", 0)
        self.close_connection = True

    def chunk(self, delta, finish=None):
        payload = {"id": "chatcmpl-fake", "object": "chat.completion.chunk", "model": "fake-model",
                   "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
        self.wfile.write(f"data: {json.dumps(payload)}\n\n".encode())
        self.wfile.flush()

    def finish(self, reason):
        self.chunk({}, reason)
        usage = {"id": "chatcmpl-fake", "object": "chat.completion.chunk", "model": "fake-model", "choices": [],
                 "usage": {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120}}
        self.wfile.write(f"data: {json.dumps(usage)}\n\ndata: [DONE]\n\n".encode())
        self.wfile.flush()

    def stream_text(self, text, delay):
        self.chunk({"role": "assistant", "content": ""})
        for word in text.split(" "):
            self.chunk({"content": word + " "})
            if delay:
                time.sleep(delay)
        self.finish("stop")

    def stream_tool_call(self, name, arguments):
        call_id = "call_" + uuid.uuid4().hex[:8]
        self.chunk({"role": "assistant", "content": None, "tool_calls": [
            {"index": 0, "id": call_id, "type": "function", "function": {"name": name, "arguments": ""}}]})
        self.chunk({"tool_calls": [{"index": 0, "function": {"arguments": arguments}}]})
        self.finish("tool_calls")


if __name__ == "__main__":
    print(f"fake llm on {PORT}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
