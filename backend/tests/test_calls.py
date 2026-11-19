"""Exercise the real SQLite store and HTTP/MCP APIs without fake model functions."""
from pathlib import Path

import pytest
from alphamobile.app import create_app, voice_prompt
from alphamobile.store import Store
from fastapi.testclient import TestClient


def setup_store(tmp_path: Path):
    store = Store(tmp_path / "alphaMobile.sqlite3")
    agent = store.credential("agent", "Build agent")
    paired = store.pair(store.pair_code(), "Nothing Phone (2a)")
    assert paired
    return store, agent, paired


def new_call(store, agent, device, key="test-call"):
    return store.create_call(agent, device["device_id"], "Build finished", "Tests passed", ["Publish?"], 90, key)


def test_pair_is_single_use(tmp_path):
    store = Store(tmp_path / "test.sqlite3")
    keyword = store.pair_code()
    assert store.pair(keyword.lower(), "Phone")
    assert store.pair(keyword, "Second phone") is None
    assert store.pair("wrong-keyword", "Phone") is None


def test_diagnostics_are_device_authenticated_bounded_and_idempotent(tmp_path):
    store, agent, paired = setup_store(tmp_path)
    app = create_app(tmp_path)
    event = {"id": "event-1", "created": 1, "version": "0.1.2", "sdk": 36,
             "device": "Test Android", "stage": "receiver-start", "detail": "java.lang.SecurityException"}
    with TestClient(app) as client:
        body = {"events": [event]}
        assert client.post("/api/diagnostics", json=body).status_code == 401
        assert client.post("/api/diagnostics", json=body, headers={"Authorization": "Bearer " + agent["token"]}).status_code == 401
        headers = {"Authorization": "Bearer " + paired["token"]}
        for _ in range(2):
            assert client.post("/api/diagnostics", json=body, headers=headers).status_code == 200
        with store.connect() as db:
            assert db.execute("SELECT COUNT(*) FROM device_diagnostics").fetchone()[0] == 1
        for number in range(25):
            assert client.post("/api/diagnostics", json={"events": [{**event, "id": str(number)}]}, headers=headers).status_code == 200
        with store.connect() as db:
            rows = db.execute("SELECT device_id FROM device_diagnostics").fetchall()
            assert len(rows) == 20
            assert all(row[0] == paired["device_id"] for row in rows)
        assert client.post("/api/diagnostics", json={"events": [{**event, "detail": "x" * 12001}]}, headers=headers).status_code == 422


def test_credentials_are_hashed_and_revocable(tmp_path):
    store, agent, device = setup_store(tmp_path)
    assert store.authenticate(agent["token"], "agent")["id"] == agent["id"]
    assert store.authenticate(agent["token"], "device") is None
    assert agent["token"].encode() not in store.path.read_bytes()
    assert store.revoke(agent["id"])
    assert store.authenticate(agent["token"], "agent") is None


def test_call_retry_and_busy_device(tmp_path):
    store, agent, device = setup_store(tmp_path)
    first = new_call(store, agent, device)
    assert new_call(store, agent, device)["id"] == first["id"]
    with pytest.raises(ValueError, match="busy"):
        new_call(store, agent, device, "different-request")


def test_transitions_and_transcript_idempotency(tmp_path):
    store, agent, device = setup_store(tmp_path)
    call = new_call(store, agent, device)
    with pytest.raises(ValueError):
        store.append(call["id"], "user", "Yes", "message-1")
    store.transition(call["id"], "active")
    store.append(call["id"], "user", "Please publish", "message-1")
    result = store.append(call["id"], "user", "Please publish", "message-1")
    assert len(result["messages"]) == 1
    with pytest.raises(ValueError):
        store.append(call["id"], "user", "No", "message-1")
    store.transition(call["id"], "completed")
    with pytest.raises(ValueError):
        store.transition(call["id"], "active")
    with pytest.raises(ValueError):
        store.append(call["id"], "assistant", "Late reply", "message-2")


def test_expiry(tmp_path):
    store, agent, device = setup_store(tmp_path)
    call = new_call(store, agent, device)
    with store.connect() as db:
        db.execute("UPDATE calls SET expires=0 WHERE id=?", (call["id"],))
    assert store.get(call["id"])["state"] == "missed"
    with pytest.raises(ValueError):
        store.transition(call["id"], "active")


def test_callback_waits_for_original_agent(tmp_path):
    store, agent, device = setup_store(tmp_path)
    call = new_call(store, agent, device)
    store.transition(call["id"], "declined")
    callback = store.request_callback(device["device_id"], agent["id"], call["id"], "Call me", "callback-key")
    assert callback["state"] == "waiting"
    assert store.callbacks(agent["id"])[0]["id"] == callback["id"]
    other = store.credential("agent", "Other agent")
    assert store.callbacks(other["id"]) == []
    answered = store.answer_callback(callback["id"], agent, "Updated context", [])
    assert answered["state"] == "ringing"
    assert store.answer_callback(callback["id"], agent, "", [])["id"] == answered["id"]


def test_device_and_agent_isolation(tmp_path):
    store, agent, device = setup_store(tmp_path)
    call = new_call(store, agent, device)
    other = store.credential("device", "Other phone")
    with TestClient(create_app(tmp_path)) as client:
        assert client.get("/api/inbox").status_code == 401
        assert client.get("/api/calls/" + call["id"], headers={"Authorization": "Bearer " + other["token"]}).status_code == 404
        assert client.get("/api/inbox", headers={"Authorization": "Bearer " + agent["token"]}).status_code == 401
        assert client.get("/api/inbox", headers={"Authorization": "Bearer " + device["token"]}).json()["calls"][0]["id"] == call["id"]
        assert client.post("/mcp", json={"jsonrpc":"2.0", "id":1, "method":"tools/list"}).status_code == 401


def test_actual_mcp_tools_call(tmp_path):
    store, agent, device = setup_store(tmp_path)
    other = store.credential("agent", "Other")
    headers = {"Authorization": "Bearer " + agent["token"], "Accept": "application/json, text/event-stream"}
    with TestClient(create_app(tmp_path)) as client:
        def rpc(method, params=None, auth=headers):
            payload = {"jsonrpc": "2.0", "id": 1, "method": method}
            if params is not None:
                payload["params"] = params
            response = client.post("/mcp", json=payload, headers=auth)
            assert response.status_code == 200, response.text
            return response.json()
        tools = rpc("tools/list")["result"]["tools"]
        assert {"call_user", "get_call_result", "pending_callbacks", "answer_callback"} <= {t["name"] for t in tools}
        result = rpc("tools/call", {"name": "call_user", "arguments": {"device_id": device["device_id"], "reason":"Please review build", "request_key":"mcp-real-test"}})["result"]
        assert not result.get("isError"), result
        call = result["structuredContent"]
        assert call["agent_name"] == "Build agent"
        denied = rpc("tools/call", {"name":"get_call_result", "arguments":{"call_id":call["id"]}}, {**headers,"Authorization":"Bearer "+other["token"]})["result"]
        assert denied["isError"]


def test_pair_rate_limit(tmp_path):
    with TestClient(create_app(tmp_path)) as client:
        for _ in range(8):
            assert client.post("/api/pair", json={"keyword":"incorrect"}).status_code == 401
        assert client.post("/api/pair", json={"keyword":"incorrect"}).status_code == 429


def test_restart_preserves_transcript(tmp_path):
    store, agent, device = setup_store(tmp_path)
    call = new_call(store, agent, device)
    store.transition(call["id"], "active")
    store.append(call["id"], "user", "Wait until tomorrow", "persisted-message")
    reloaded = Store(store.path).get(call["id"])
    assert reloaded["messages"][0]["text"] == "Wait until tomorrow"
    assert "cannot inspect" in voice_prompt(reloaded)[0]["content"]


def test_tasks_results_and_agent_ownership(tmp_path):
    store, agent, device = setup_store(tmp_path)
    task = store.report_task(agent, device["device_id"], "weekly", "Weekly report", "Fridays 08:00", "Europe/London", "2026-11-20T08:00:00+00:00", True, "Review results")
    updated = store.report_task(agent, device["device_id"], "weekly", "Weekly report", "Fridays 09:00", "Europe/London", None, True, "Review results")
    assert updated["id"] == task["id"]
    store.report_result(agent["id"], task["id"], "run-1", "running", "Started", "", None, None)
    store.report_result(agent["id"], task["id"], "run-1", "completed", "Finished", "Actual result", None, None)
    result = store.tasks(device_id=device["device_id"])
    assert len(result[0]["results"]) == 1
    assert result[0]["results"][0]["status"] == "completed"
    assert store.tasks(device_id="different-device") == []
    other = store.credential("agent", "Other agent")
    with pytest.raises(ValueError):
        store.report_result(other["id"], task["id"], "run-2", "completed", "Forged", "", None, None)


def test_turn_waits_for_agent_without_model(tmp_path, monkeypatch):
    # An unreachable configured model must not delay or affect an agent call.
    monkeypatch.setenv("ALPHAMOBILE_LLM_URL", "http://127.0.0.1:1")
    monkeypatch.setenv("ALPHAMOBILE_MODEL", "unreachable")
    store, agent, device = setup_store(tmp_path)
    call = new_call(store, agent, device)
    store.transition(call["id"], "active")
    auth = {"Authorization": "Bearer " + device["token"]}
    body = {"id": "gbrain-question", "role": "user", "text": "What is GBrain's status?"}
    with TestClient(create_app(tmp_path)) as client:
        import time
        start = time.monotonic()
        response = client.post("/api/calls/" + call["id"] + "/turn", headers=auth, json=body)
        assert time.monotonic() - start < 2
        assert response.status_code == 200
        result = response.json()
        assert result["awaiting_agent"]
        assert result["pending_questions"][0]["id"] == body["id"]
        assert result["messages"][-1]["text"] == "Checking with Build agent."
        assert result["messages"][-1]["role"] == "status"
        assert not any(m["role"] == "assistant" for m in result["messages"])
        again = client.post("/api/calls/" + call["id"] + "/turn", headers=auth, json=body).json()
        assert len(again["messages"]) == 2
        assert client.get("/api/calls/" + call["id"] + "/prompt", headers=auth).status_code == 409
        assert client.post("/api/calls/" + call["id"] + "/messages", headers=auth,
                           json={"id": "invented-answer", "role": "assistant", "text": "I don't know GBrain"}).status_code == 409


def test_progress_and_correlated_answers(tmp_path):
    store, agent, device = setup_store(tmp_path)
    call = new_call(store, agent, device)
    cid = call["id"]
    store.transition(cid, "active")
    store.append(cid, "user", "First question", "question-1")
    store.append(cid, "user", "Second question", "question-2")
    progress = store.append(cid, "agent", "Checking the source", "progress-1", reply_to="question-1", final=False)
    assert len(progress["pending_questions"]) == 2
    answered = store.append(cid, "agent", "First result", "answer-1", reply_to="question-1")
    assert [p["id"] for p in answered["pending_questions"]] == ["question-2"]
    retried = store.append(cid, "agent", "First result", "answer-1", reply_to="question-1")
    assert len(retried["pending_questions"]) == 1
    # A late status acknowledgement must not follow an already delivered answer.
    assert len(store.append(cid, "status", "Checking", "hold-1", reply_to="question-1", final=False)["messages"]) == len(answered["messages"])
    with pytest.raises(ValueError, match="reference"):
        store.append(cid, "agent", "Invalid reply", "answer-invalid", reply_to="another-call-question")
    final = store.append(cid, "agent", "Second result", "answer-2")
    assert not final["awaiting_agent"]
    assert final["messages"][-1]["reply_to"] == "question-2"


def test_actual_mcp_reply_saved_after_hangup(tmp_path):
    store, agent, device = setup_store(tmp_path)
    call = new_call(store, agent, device)
    cid = call["id"]
    store.transition(cid, "active")
    store.append(cid, "user", "Please check GBrain", "gbrain-status")
    store.transition(cid, "completed")
    auth = {"Authorization": "Bearer " + agent["token"], "Accept": "application/json, text/event-stream"}
    with TestClient(create_app(tmp_path)) as client:
        payload = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "send_message", "arguments": {
            "call_id": cid, "text": "Observed test result", "message_id": "gbrain-result",
            "reply_to": "gbrain-status", "final": True}}}
        response = client.post("/mcp", json=payload, headers=auth).json()["result"]
        assert not response.get("isError"), response
        assert response["structuredContent"]["state"] == "completed"
        assert not response["structuredContent"]["awaiting_agent"]
        feed = client.get("/api/messages", headers={"Authorization": "Bearer " + device["token"]}).json()
        assert feed["messages"][0]["text"] == "Observed test result"
        assert feed["messages"][0]["question"] == "Please check GBrain"
    restarted = Store(store.path)
    assert restarted.agent_messages(device["device_id"])["total"] == 1
    assert restarted.agent_messages("other-device")["messages"] == []


def test_legacy_greeting_is_exact_caller_text(tmp_path):
    store, agent, device = setup_store(tmp_path)
    call = new_call(store, agent, device)
    store.transition(call["id"], "active")
    with TestClient(create_app(tmp_path)) as client:
        result = client.post("/api/calls/" + call["id"] + "/turn",
            headers={"Authorization": "Bearer " + device["token"]},
            json={"id": "greeting-1", "role": "user", "text": "I have answered. Please explain why you called."}).json()
        assert result["messages"][-1]["text"] == "Build finished Publish?"
        assert not result["awaiting_agent"]


def test_feed_retains_old_conversations_and_paginates(tmp_path):
    store, agent, device = setup_store(tmp_path)
    call = new_call(store, agent, device)
    store.transition(call["id"], "active")
    for i in range(55):
        store.append(call["id"], "agent", f"Result {i}", f"message-{i}")
    first = store.agent_messages(device["device_id"])
    second = store.agent_messages(device["device_id"], first["next_offset"])
    assert first["has_more"] and first["total"] == 55
    assert not second["has_more"]
    assert len({m["id"] for m in first["messages"] + second["messages"]}) == 55


def test_upgrade_preserves_existing_agent_answer(tmp_path):
    import sqlite3
    path = tmp_path / "alphaMobile.sqlite3"
    with sqlite3.connect(path) as db:
        db.execute("CREATE TABLE messages(id TEXT PRIMARY KEY,call_id TEXT NOT NULL,role TEXT NOT NULL,text TEXT NOT NULL,created REAL NOT NULL)")
        db.execute("INSERT INTO messages VALUES ('legacy-answer','legacy-call','agent','Existing GBrain answer',1)")
    store = Store(path)
    with store.connect() as db:
        row = db.execute("SELECT * FROM messages WHERE id='legacy-answer'").fetchone()
        assert row["text"] == "Existing GBrain answer"
        assert row["is_final"] == 1
        assert row["reply_to"] is None


def test_mcp_wait_returns_promptly_when_phone_speaks(tmp_path):
    import time
    from concurrent.futures import ThreadPoolExecutor
    store, agent, device = setup_store(tmp_path)
    call = new_call(store, agent, device)
    store.transition(call["id"], "active")
    auth = {"Authorization": "Bearer " + agent["token"], "Accept": "application/json, text/event-stream"}
    with TestClient(create_app(tmp_path)) as client, ThreadPoolExecutor() as pool:
        pending = pool.submit(client.post, "/mcp", headers=auth, json={"jsonrpc": "2.0", "id": 1,
            "method": "tools/call", "params": {"name": "get_call_result", "arguments": {
                "call_id": call["id"], "wait_seconds": 5, "after_message_id": ""}}})
        time.sleep(0.1)
        start = time.monotonic()
        response = client.post("/api/calls/" + call["id"] + "/turn",
            headers={"Authorization": "Bearer " + device["token"]},
            json={"id": "fast-question", "role": "user", "text": "Please check the status"})
        assert response.status_code == 200
        result = pending.result(timeout=2).json()["result"]["structuredContent"]
        assert time.monotonic() - start < 2
        assert result["pending_questions"][0]["id"] == "fast-question"


def test_chat_survives_voice_expiry_and_does_not_block_calls(tmp_path):
    store, agent, device = setup_store(tmp_path)
    chat = store.create_chat(device['device_id'], agent['id'])
    assert store.create_chat(device['device_id'], agent['id'])['id'] == chat['id']
    store.append(chat['id'], 'user', 'Check status', 'chat-question')
    with store.connect() as db:
        db.execute('UPDATE calls SET updated=0 WHERE id=?', (chat['id'],))
    assert Store(store.path).get(chat['id'])['state'] == 'active'
    assert new_call(store, agent, device)['state'] == 'ringing'
    assert store.pending_messages(agent['id'])[0]['id'] == chat['id']
    assert store.pending_messages(store.credential('agent', 'Other')['id']) == []


def test_stream_saves_one_reply_and_resolves_only_on_final(tmp_path):
    store, agent, device = setup_store(tmp_path)
    chat = store.create_chat(device['device_id'], agent['id'])
    store.append(chat['id'], 'user', 'Explain', 'stream-question')
    first = store.append_chunk(chat['id'], 'stream-question', 'answer', 0, 'First sentence. ', False)
    assert first['awaiting_agent']
    assert first['messages'][-1]['text'] == 'First sentence. '
    store.append_chunk(chat['id'], 'stream-question', 'answer', 0, 'First sentence. ', False)
    with pytest.raises(ValueError, match='identical'):
        store.append_chunk(chat['id'], 'stream-question', 'answer', 0, 'Changed', False)
    with pytest.raises(ValueError, match='sequence'):
        store.append_chunk(chat['id'], 'stream-question', 'answer', 2, 'Skipped', False)
    final = store.append_chunk(chat['id'], 'stream-question', 'answer', 1, 'Second sentence.', True)
    assert not final['awaiting_agent']
    assert final['messages'][-1]['text'] == 'First sentence. Second sentence.'
    assert store.agent_messages(device['device_id'])['total'] == 1
    assert Store(store.path).get(chat['id'])['messages'][-1]['is_final']
    with pytest.raises(ValueError, match='complete'):
        store.append_chunk(chat['id'], 'stream-question', 'answer', 2, 'Too late', False)


def test_stream_ownership_and_completion_after_hangup(tmp_path):
    store, agent, device = setup_store(tmp_path)
    call = new_call(store, agent, device)
    store.transition(call['id'], 'active')
    store.append(call['id'], 'user', 'Please check', 'voice-question')
    store.append_chunk(call['id'], 'voice-question', 'voice-answer', 0, 'Checking result.', False)
    store.transition(call['id'], 'completed')
    assert not store.append_chunk(call['id'], 'voice-question', 'voice-answer', 1, '', True)['awaiting_agent']
    chat = store.create_chat(device['device_id'], agent['id'])
    store.append(chat['id'], 'user', 'Other question', 'other-question')
    with pytest.raises(ValueError, match='different reply'):
        store.append_chunk(chat['id'], 'other-question', 'voice-answer', 0, 'Hijack', True)
    with pytest.raises(ValueError, match='user message'):
        store.append_chunk(chat['id'], 'voice-question', 'other-answer', 0, 'Cross chat', True)


def test_task_sync_preserves_inventory_when_source_unavailable(tmp_path):
    store, agent, device = setup_store(tmp_path)
    task = store.report_task(agent, device['device_id'], 'weekly', 'Weekly', 'Friday', 'Europe/London', None, True, '')
    store.request_task_sync(device_id=device['device_id'])
    request = store.task_sync_requests(agent_id=agent['id'], pending_only=True)[0]
    store.request_task_sync(device_id=device['device_id'])
    assert store.task_sync_requests()[0]['id'] == request['id']
    store.complete_task_sync(agent['id'], request['id'], [], False, 'Scheduler unavailable')
    assert store.tasks()[0]['enabled']
    with pytest.raises(ValueError, match='not found'):
        store.complete_task_sync('other', request['id'], [], True, '')
    with pytest.raises(ValueError, match='Publish each'):
        store.complete_task_sync(agent['id'], request['id'], ['unknown'], True, '')
    store.complete_task_sync(agent['id'], request['id'], [], True, 'Confirmed empty')
    assert store.tasks()[0]['id'] == task['id']
    assert not store.tasks()[0]['enabled']


def test_phone_chat_and_stream_via_real_http_mcp(tmp_path):
    store, agent, device = setup_store(tmp_path)
    phone = {'Authorization': 'Bearer ' + device['token']}
    agent_headers = {'Authorization': 'Bearer ' + agent['token'], 'Accept': 'application/json, text/event-stream'}
    with TestClient(create_app(tmp_path)) as client:
        connected = client.post('/api/connect', headers=phone, json={}).json()
        assert connected['task_sync'][0]['state'] == 'pending'
        chat = client.post('/api/chats', headers=phone, json={'agent_id': agent['id']}).json()
        turn = client.post('/api/calls/' + chat['id'] + '/turn', headers=phone,
                           json={'id': 'typed-question', 'role': 'user', 'text': 'Typed question'}).json()
        assert turn['awaiting_agent']
        def tool(name, arguments):
            response = client.post('/mcp', headers=agent_headers, json={'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call', 'params': {'name': name, 'arguments': arguments}})
            result = response.json()['result']
            assert not result.get('isError'), result
            return result
        pending = tool('get_pending_messages', {'wait_seconds': 0})
        assert chat['id'] in str(pending)
        tool('send_reply_chunk', {'call_id': chat['id'], 'reply_to': 'typed-question', 'stream_id': 'http-answer', 'sequence': 0, 'text': 'First sentence. '})
        saved = client.get('/api/connection', headers=phone).json()
        assert saved['messages'][0]['text'] == 'First sentence. '
        assert saved['ai_connected']
        tool('send_reply_chunk', {'call_id': chat['id'], 'reply_to': 'typed-question', 'stream_id': 'http-answer', 'sequence': 1, 'text': 'Final sentence.', 'final': True})
        assert not client.get('/api/calls/' + chat['id'], headers=phone).json()['awaiting_agent']
        client.post('/api/agent/presence', headers=agent_headers, json={'online': False})
        assert not client.get('/api/connection', headers=phone).json()['ai_connected']


def test_latest_task_result_is_visible_in_snapshot_and_sorts_first(tmp_path):
    store, agent, device = setup_store(tmp_path)
    older = store.report_task(agent, device["device_id"], "older", "Earlier schedule", "daily", "Europe/London", None, True, "")
    newer = store.report_task(agent, device["device_id"], "newer", "Later schedule", "daily", "Europe/London", None, True, "")
    assert store.tasks(device_id=device["device_id"])[0]["id"] == newer["id"]
    run = store.report_result(agent["id"], older["id"], "run-1", "running", "Checking", "First update", None, None)
    assert store.tasks(device_id=device["device_id"])[0]["id"] == older["id"]
    updated = store.report_result(agent["id"], older["id"], "run-1", "completed", "Finished", "Actual output. " * 300, None, "2026-11-19T15:00:00Z")
    assert updated["id"] == run["id"]
    with TestClient(create_app(tmp_path)) as client:
        headers = {"Authorization": "Bearer " + device["token"]}
        for path in ["/api/tasks", "/api/connection", "/api/inbox"]:
            result = client.get(path, headers=headers).json()["tasks"][0]
            assert result["id"] == older["id"]
            latest = result["results"][0]
            assert latest["status"] == "completed" and latest["summary"] == "Finished"
            assert latest["output"] == updated["output"][:2000]
            assert latest["output_truncated"] and latest["finished_at"] == "2026-11-19T15:00:00Z"
        detail = client.get("/api/tasks/" + older["id"], headers=headers).json()
        assert len(detail["results"]) == 1 and detail["results"][0]["output"] == updated["output"]
