import json
import secrets
import sys
import uuid
import urllib.request
from pathlib import Path

API = "http://127.0.0.1:8080/api/v1"
HERE = Path(__file__).parent


def call(method, path, body=None, token=None):
    request = urllib.request.Request(API + path, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    with urllib.request.urlopen(request, data) as response:
        text = response.read()
        return json.loads(text) if text else None


email = f"live-check-{uuid.uuid4().hex[:8]}@example.test"
password = secrets.token_urlsafe(18)
setup_device = str(uuid.uuid4())
auth = call("POST", "/auth/register", {
    "email": email, "password": password, "displayName": "Live Check",
    "device": {"id": setup_device, "name": "Setup script", "platform": {"os": "windows"}}})
token = auth["accessToken"]
provider = call("POST", "/providers", {"name": "Fake", "baseUrl": "http://127.0.0.1:18090/v1", "isLocal": False}, token)
model = call("POST", f"/providers/{provider['id']}/models", {"modelKey": "fake-model", "contextWindow": 32000}, token)
project = call("POST", "/projects", {"name": "live-check"}, token)
state = {"email": email, "password": password, "userId": auth["user"]["id"], "setupDevice": setup_device,
         "providerId": provider["id"], "modelId": model["id"], "projectId": project["id"]}
(HERE / "account.local.json").write_text(json.dumps(state, indent=1))
print(json.dumps({key: value for key, value in state.items() if key != "password"}, indent=1))
