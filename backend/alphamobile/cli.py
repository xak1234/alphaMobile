"""Local-only provisioning: secrets never need to be pasted into the phone."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from .config import endpoint, load_config, private_json
from .store import Store, digest


def main() -> None:
    parser = argparse.ArgumentParser(prog="alphamobile")
    parser.add_argument("--config", type=Path, help="Operator relay config JSON")
    parser.add_argument("--state", type=Path, help="Override the state directory")
    sub = parser.add_subparsers(dest="command", required=True)
    pair = sub.add_parser("pair", help="Create a single-use pairing keyword, valid for 24 hours")
    pair.add_argument("--keyword", help="Omit to generate a keyword")
    agent = sub.add_parser("agent", help="Provision an MCP agent; prints its credential once")
    agent.add_argument("name")
    agent.add_argument("--url", help="Relay base URL for a client connection file")
    agent.add_argument("--output", type=Path, help="Create a private client connection file instead of printing the token")
    agent.add_argument("--ca-file", type=Path, help="Optional client-side public CA path")
    revoke = sub.add_parser("revoke")
    revoke.add_argument("identity")
    sub.add_parser("devices")
    serve = sub.add_parser("serve")
    serve.add_argument("--host")
    serve.add_argument("--port", type=int)
    args = parser.parse_args()
    try:
        config = load_config(args.config) if args.config else {}
    except (ValueError, OSError) as error:
        parser.error(str(error))
    args.state = args.state or Path(config.get("state", os.environ.get("ALPHAMOBILE_STATE", "./state")))
    args.state.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.environ["ALPHAMOBILE_STATE"] = str(args.state.resolve())
    if "allowed_hosts" in config:
        os.environ["ALPHAMOBILE_HOSTS"] = ",".join(config["allowed_hosts"])
    store = Store(args.state / "alphaMobile.sqlite3")
    if args.command == "pair":
        if args.keyword:
            if len(args.keyword.strip()) < 3:
                parser.error("Use a memorable keyword of at least three characters")
            import time
            with store.connect() as db:
                db.execute("DELETE FROM pairing")
                db.execute("INSERT INTO pairing VALUES (?,?)", (digest(args.keyword.strip().upper()), time.time() + 86400))
            print(args.keyword)
        else:
            import secrets
            words = ("cobalt", "silver", "velvet", "amber", "violet", "willow", "copper", "meadow", "orbit", "harbour", "comet", "otter", "falcon", "forest", "river", "marble")
            keyword = "-".join(secrets.choice(words) for _ in range(4))
            import time
            with store.connect() as db:
                db.execute("DELETE FROM pairing")
                db.execute("INSERT INTO pairing VALUES (?,?)", (digest(keyword.upper()), time.time() + 86400))
            print(keyword)
    elif args.command == "agent":
        if args.output:
            if not args.url:
                parser.error("--url is required with --output")
            try:
                url = endpoint(args.url)
            except ValueError as error:
                parser.error(str(error))
            credential = store.credential("agent", args.name)
            connection = {"url": url, "token": credential["token"]}
            if args.ca_file:
                connection["ca_file"] = str(args.ca_file.resolve())
            try:
                private_json(args.output, connection)
            except OSError:
                store.revoke(credential["id"])
                raise
            print(json.dumps({"id": credential["id"], "name": args.name, "connection_file": str(args.output.resolve())}))
        else:
            print(json.dumps(store.credential("agent", args.name), indent=2))
    elif args.command == "devices":
        print(json.dumps(store.devices(), indent=2))
    elif args.command == "revoke":
        print(json.dumps({"revoked": store.revoke(args.identity)}))
    elif args.command == "serve":
        import uvicorn

        from .app import create_app
        uvicorn.run(create_app(args.state), host=args.host or config.get("host", "127.0.0.1"), port=args.port or config.get("port", 8793), access_log=False)


if __name__ == "__main__":
    main()
