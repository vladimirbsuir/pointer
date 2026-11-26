import json
from pathlib import Path

from cdp import evaluate

here = Path(__file__).parent
workspace = here / "workspace"
workspace.mkdir(exist_ok=True)
(workspace / "README.md").write_text("# live check\n")
account = json.loads((here / "account.local.json").read_text())
arguments = json.dumps({"projectId": account["projectId"], "path": str(workspace.resolve())})
print(evaluate(
    "(async () => { await window.__TAURI_INTERNALS__.invoke('project_link', " + arguments + ");"
    " const local = await window.__TAURI_INTERNALS__.invoke('projects_local');"
    " setTimeout(() => location.reload(), 100); return local; })()"))
