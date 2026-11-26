import base64
import itertools
import json
import sys
import urllib.request
from pathlib import Path

import websocket

counter = itertools.count(1)


def page_socket():
    with urllib.request.urlopen("http://127.0.0.1:9222/json") as response:
        targets = json.loads(response.read())
    pages = [target for target in targets if target.get("type") == "page"]
    return websocket.create_connection(pages[0]["webSocketDebuggerUrl"], timeout=60, suppress_origin=True)


def command(sock, method, params=None):
    number = next(counter)
    sock.send(json.dumps({"id": number, "method": method, "params": params or {}}))
    while True:
        message = json.loads(sock.recv())
        if message.get("id") == number:
            return message


def evaluate(expression):
    sock = page_socket()
    try:
        reply = command(sock, "Runtime.evaluate", {
            "expression": expression, "awaitPromise": True, "returnByValue": True})
        result = reply.get("result", {})
        if "exceptionDetails" in result:
            return {"exception": result["exceptionDetails"].get("exception", {}).get("description")
                    or result["exceptionDetails"].get("text")}
        return result.get("result", {}).get("value")
    finally:
        sock.close()


def screenshot(path):
    sock = page_socket()
    try:
        reply = command(sock, "Page.captureScreenshot", {"format": "png"})
        Path(path).write_bytes(base64.b64decode(reply["result"]["data"]))
        return path
    finally:
        sock.close()


if __name__ == "__main__":
    if sys.argv[1] == "shot":
        print(screenshot(sys.argv[2]))
    else:
        source = Path(sys.argv[2]).read_text(encoding="utf-8") if sys.argv[1] == "file" else sys.argv[1]
        print(json.dumps(evaluate(source), indent=1, ensure_ascii=False))
