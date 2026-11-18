import argparse
import json
import secrets
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_FILE = HERE / "account.local.json"
DEFAULT_CONTAINER = "pointer-postgres-1"


def parse_args():
    parser = argparse.ArgumentParser(
        description="Create and use a local benchmark account; credentials stay in a git-ignored file."
    )
    parser.add_argument("--file", default=str(DEFAULT_FILE), help="where the account is stored")
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("create", help="register an account with a project")
    create.add_argument("--api", default="http://localhost:8080/api/v1")
    create.add_argument("--project", default="pointer", help="name of the project to create")
    create.add_argument("--workspace", default=str(HERE.parents[1]), help="folder the bench device executes in")
    create.add_argument(
        "--copy-providers-from",
        help="email of a local account whose providers, models and encrypted keys are copied (needs Docker)",
    )
    create.add_argument("--container", default=DEFAULT_CONTAINER, help="Postgres container of the local stack")
    env = commands.add_parser("env", help="print shell exports for the scripts and the bench device")
    env.add_argument("--shell", choices=["bash", "powershell"], default="bash")
    return parser.parse_args()


def call(api, method, path, body=None, token=None):
    request = urllib.request.Request(
        api + path, data=None if body is None else json.dumps(body).encode(), method=method
    )
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request) as response:
            text = response.read().decode()
            return json.loads(text) if text else None
    except urllib.error.HTTPError as error:
        raise SystemExit(f"{method} {path} failed: {error.code} {error.read().decode()}")


def sql(container, statement):
    return subprocess.run(
        ["docker", "exec", container, "psql", "-U", "pointer", "-d", "pointer", "-At", "-c", statement],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()


def copy_providers(container, source_email, user_id):
    source_id = sql(container, f"select id from users where email = '{source_email}'")
    if not source_id:
        raise SystemExit(f"No local account {source_email}")
    columns = sql(
        container,
        "select string_agg(column_name, ',') from information_schema.columns "
        "where table_name = 'models' and column_name not in ('id', 'provider_id')",
    )
    provider_columns = sql(
        container,
        "select string_agg(column_name, ',') from information_schema.columns "
        "where table_name = 'providers' and column_name not in ('id', 'user_id', 'created_at', 'updated_at')",
    )
    providers = sql(container, f"select id from providers where user_id = '{source_id}'").splitlines()
    for provider in providers:
        copy = str(uuid.uuid4())
        sql(
            container,
            f"insert into providers (id, user_id, created_at, updated_at, {provider_columns}) "
            f"select '{copy}', '{user_id}', now(), now(), {provider_columns} from providers where id = '{provider}'",
        )
        sql(
            container,
            f"insert into models (id, provider_id, {columns}) "
            f"select gen_random_uuid(), '{copy}', {columns} from models where provider_id = '{provider}'",
        )
    return len(providers)


def create(args):
    email = f"bench-{int(time.time())}@example.test"
    password = secrets.token_urlsafe(18)
    device_id = str(uuid.uuid4())
    auth = call(
        args.api,
        "POST",
        "/auth/register",
        {
            "email": email,
            "password": password,
            "displayName": "Bench",
            "locale": "en",
            "device": {"id": device_id, "name": "Bench device", "platform": {"os": "windows"}},
        },
    )
    project = call(args.api, "POST", "/projects", {"name": args.project}, auth["accessToken"])
    copied = 0
    if args.copy_providers_from:
        user_id = sql(args.container, f"select id from users where email = '{email}'")
        copied = copy_providers(args.container, args.copy_providers_from, user_id)
    account = {
        "api": args.api,
        "email": email,
        "password": password,
        "deviceId": device_id,
        "project": args.project,
        "projectId": project["id"],
        "workspace": args.workspace,
    }
    Path(args.file).write_text(json.dumps(account, indent=2), encoding="utf-8")
    print(f"Created {email} with project {args.project}, copied {copied} providers, saved to {args.file}")


def env(args):
    account = json.loads(Path(args.file).read_text(encoding="utf-8"))
    values = {
        "POINTER_API": account["api"],
        "POINTER_API_URL": account["api"].removesuffix("/api/v1"),
        "POINTER_EMAIL": account["email"],
        "POINTER_PASSWORD": account["password"],
        "POINTER_DEVICE_ID": account["deviceId"],
        "POINTER_PROJECT": account["project"],
        "POINTER_PROJECT_ID": account["projectId"],
        "POINTER_WORKSPACE": account["workspace"],
    }
    for name, value in values.items():
        if args.shell == "powershell":
            print(f"$env:{name} = '{value}'")
        else:
            print(f"export {name}='{value}'")


def main():
    args = parse_args()
    if args.command == "create":
        create(args)
    else:
        env(args)


if __name__ == "__main__":
    main()
