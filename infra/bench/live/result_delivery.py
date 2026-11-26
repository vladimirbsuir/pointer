import json
import sys
import time
import urllib.request

from ui import click, send, text

CONTROL = "http://127.0.0.1:8082"
WAIT_SECONDS = 150


def control(path):
    with urllib.request.urlopen(CONTROL + path) as response:
        return json.loads(response.read())


def log_lines():
    return control("/log?n=400")


def wait_for_line(predicate, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        found = [line for line in log_lines() if predicate(line)]
        if found:
            return found[-1]
        time.sleep(0.5)
    return None


def is_command_request(line):
    return "down exec.request" in line and "op=run_command" in line and "dryRun=True" not in line


def run(mode):
    label = f"delivery check {time.strftime('%H%M%S')}"
    command = f"Start-Sleep 12; '{label} done'"
    marker = len(log_lines())
    finished_before = text(100000).count("Tool finished")
    send("RUN " + json.dumps({"command": command, "timeoutMs": 90000}))
    time.sleep(2.5)
    if "Allow for this session" in text(400):
        click("Allow for this session")
    request = wait_for_line(is_command_request, 20)
    if request is None:
        print("the command was not requested")
        return
    call_id = request.split("callId=")[1].split(" ")[0]
    time.sleep(3)
    print(control("/cut?block=15") if mode == "cut" else control("/stall"))
    started = time.time()
    while time.time() - started < WAIT_SECONDS and text(100000).count("Tool finished") == finished_before:
        time.sleep(1)
    print(f"tool call finished {round(time.time() - started)} s after the {mode}")
    print(text(300))
    for line in log_lines()[marker:]:
        if any(part in line for part in (call_id, "hello", "opened", "ended", "control", "refused")):
            print(line)


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "cut")
