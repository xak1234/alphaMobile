"""Actual configuration/provisioning paths, with isolated state and real subprocesses."""
import json
import os
import subprocess
import sys

import pytest
from alphamobile.config import endpoint, load_config, private_json
from alphamobile.store import Store


def test_private_config_is_exclusive_and_private(tmp_path):
    target = tmp_path / "connection.json"
    private_json(target, {"url": "https://relay.example", "token": "test-only"})
    with pytest.raises(FileExistsError):
        private_json(target, {"token": "replacement"})
    assert json.loads(target.read_text())["token"] == "test-only"
    if os.name != "nt":
        assert target.stat().st_mode & 0o777 == 0o600


@pytest.mark.parametrize("url", ["http://remote.example", "https://user:pass@relay.example", "https://relay.example/?token=secret", "ftp://relay.example", "https://relay.example:bad"])
def test_connection_url_rejects_unsafe_transport_or_credentials(url):
    with pytest.raises(ValueError):
        endpoint(url)


def test_operator_config_and_real_agent_file(tmp_path):
    config = tmp_path / "relay.json"
    private_json(config, {"state": str(tmp_path / "state"), "allowed_hosts": ["relay.example", "127.0.0.1"], "port": 8793})
    assert load_config(config)["port"] == 8793
    output = tmp_path / "agent.connection.json"
    command = [sys.executable, "-m", "alphamobile.cli", "--config", str(config), "agent", "Test agent", "--url", "https://relay.example", "--output", str(output)]
    result = subprocess.run(command, capture_output=True, text=True, check=True)
    connection = json.loads(output.read_text())
    assert connection["token"] not in result.stdout
    store = Store(tmp_path / "state/alphaMobile.sqlite3")
    assert store.authenticate(connection["token"], "agent")
    first = output.read_bytes()
    assert subprocess.run(command, capture_output=True).returncode != 0
    assert output.read_bytes() == first
    with store.connect() as db:
        assert db.execute("SELECT COUNT(*) FROM credentials").fetchone()[0] == 1


def test_config_requires_absolute_state_and_explicit_hosts(tmp_path):
    config = tmp_path / "bad.json"
    private_json(config, {"state": "state", "allowed_hosts": ["*"]})
    with pytest.raises(ValueError):
        load_config(config)
    config.write_text(json.dumps({"state": str(tmp_path), "allowed_hosts": ["*"], "port": 8793}))
    with pytest.raises(ValueError):
        load_config(config)
