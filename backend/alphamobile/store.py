"""Transactional call state, credentials and transcripts stored in SQLite."""
from __future__ import annotations

import hashlib
import json
import secrets
import sqlite3
import time
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any

TERMINAL = {"completed", "declined", "cancelled", "missed", "failed"}
TRANSITIONS = {
    "ringing": {"active", "declined", "cancelled", "missed"},
    "active": {"completed", "cancelled", "failed"},
}


def digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


class Store:
    def __init__(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        with self.connect() as db:
            db.executescript("""
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS credentials (
              token_hash TEXT PRIMARY KEY, id TEXT UNIQUE NOT NULL,
              kind TEXT NOT NULL, name TEXT NOT NULL, seen REAL NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS pairing (
              code_hash TEXT PRIMARY KEY, expires REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS device_diagnostics (
              device_id TEXT NOT NULL, event_id TEXT NOT NULL, received REAL NOT NULL,
              event TEXT NOT NULL, PRIMARY KEY(device_id,event_id));
            CREATE TABLE IF NOT EXISTS calls (
              id TEXT PRIMARY KEY, device_id TEXT NOT NULL, agent_id TEXT NOT NULL,
              agent_name TEXT NOT NULL, reason TEXT NOT NULL, context TEXT NOT NULL,
              questions TEXT NOT NULL, state TEXT NOT NULL, created REAL NOT NULL,
              expires REAL NOT NULL, updated REAL NOT NULL, request_key TEXT,
              UNIQUE(agent_id, request_key));
            CREATE TABLE IF NOT EXISTS messages (
              id TEXT PRIMARY KEY, call_id TEXT NOT NULL, role TEXT NOT NULL,
              text TEXT NOT NULL, created REAL NOT NULL);
            CREATE INDEX IF NOT EXISTS calls_device ON calls(device_id, created);
            CREATE INDEX IF NOT EXISTS messages_call ON messages(call_id, created);
            CREATE TABLE IF NOT EXISTS pending_questions (
              message_id TEXT PRIMARY KEY, call_id TEXT NOT NULL, resolved INTEGER NOT NULL DEFAULT 0);
            CREATE INDEX IF NOT EXISTS pending_call ON pending_questions(call_id, resolved);
            CREATE TABLE IF NOT EXISTS reply_chunks (
              message_id TEXT NOT NULL, sequence INTEGER NOT NULL, text TEXT NOT NULL,
              final INTEGER NOT NULL, PRIMARY KEY(message_id,sequence));
            CREATE TABLE IF NOT EXISTS task_sync (
              id TEXT PRIMARY KEY, agent_id TEXT NOT NULL, device_id TEXT NOT NULL,
              state TEXT NOT NULL, requested REAL NOT NULL, completed REAL, note TEXT NOT NULL,
              UNIQUE(agent_id,device_id));
            """)
            columns = {r["name"] for r in db.execute("PRAGMA table_info(messages)")}
            if "reply_to" not in columns:
                db.execute("ALTER TABLE messages ADD COLUMN reply_to TEXT")
            if "is_final" not in columns:
                db.execute("ALTER TABLE messages ADD COLUMN is_final INTEGER NOT NULL DEFAULT 1")
            if "is_stream" not in columns:
                db.execute("ALTER TABLE messages ADD COLUMN is_stream INTEGER NOT NULL DEFAULT 0")
            if "channel" not in {r["name"] for r in db.execute("PRAGMA table_info(calls)")}:
                db.execute("ALTER TABLE calls ADD COLUMN channel TEXT NOT NULL DEFAULT 'voice'")

    @contextmanager
    def connect(self) -> Iterator[sqlite3.Connection]:
        db = sqlite3.connect(self.path, timeout=15)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def credential(self, kind: str, name: str) -> dict[str, str]:
        token = secrets.token_urlsafe(32)
        identity = secrets.token_hex(12)
        with self.connect() as db:
            db.execute("INSERT INTO credentials VALUES (?,?,?,?,?)",
                       (digest(token), identity, kind, name, 0))
        return {"id": identity, "name": name, "token": token}

    def authenticate(self, token: str, kind: str) -> dict[str, Any] | None:
        with self.connect() as db:
            row = db.execute("SELECT id,name,kind FROM credentials WHERE token_hash=? AND kind=?",
                             (digest(token), kind)).fetchone()
        return dict(row) if row else None

    def revoke(self, identity: str) -> bool:
        with self.connect() as db:
            return db.execute("DELETE FROM credentials WHERE id=?", (identity,)).rowcount > 0

    def pair_code(self, lifetime: int = 86400) -> str:
        code = secrets.token_hex(6).upper()
        with self.connect() as db:
            db.execute("DELETE FROM pairing WHERE expires<?", (time.time(),))
            db.execute("INSERT INTO pairing VALUES (?,?)", (digest(code), time.time() + lifetime))
        return code

    def pair(self, code: str, name: str) -> dict[str, str] | None:
        token, identity = secrets.token_urlsafe(32), secrets.token_hex(12)
        with self.connect() as db:
            deleted = db.execute("DELETE FROM pairing WHERE code_hash=? AND expires>?",
                                 (digest(code.strip().upper()), time.time())).rowcount
            if not deleted:
                return None
            db.execute("INSERT INTO credentials VALUES (?,?,?,?,?)",
                       (digest(token), identity, "device", name, time.time()))
        return {"device_id": identity, "token": token, "name": name}

    def devices(self) -> list[dict[str, Any]]:
        with self.connect() as db:
            return [dict(r) for r in db.execute(
                "SELECT id,name,seen FROM credentials WHERE kind='device' ORDER BY name")]

    def expire(self, db: sqlite3.Connection) -> None:
        now = time.time()
        db.execute("UPDATE calls SET state='missed',updated=? WHERE state='ringing' AND expires<?",
                   (now, now))
        db.execute("UPDATE calls SET state='failed',updated=? WHERE channel='voice' AND state='active' AND created<?",
                   (now, now - 3600))

    def create_call(self, agent: dict[str, Any], device_id: str, reason: str, context: str,
                    questions: list[str], ring_seconds: int, request_key: str | None) -> dict[str, Any]:
        now, call_id = time.time(), secrets.token_hex(12)
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            self.expire(db)
            if request_key:
                existing = db.execute("SELECT id FROM calls WHERE agent_id=? AND request_key=?",
                                      (agent["id"], request_key)).fetchone()
                if existing:
                    return self._get(db, existing["id"])
            if not db.execute("SELECT id FROM credentials WHERE id=? AND kind='device'",
                              (device_id,)).fetchone():
                raise ValueError("Unknown device. Pair the app first.")
            if db.execute("SELECT id FROM calls WHERE device_id=? AND channel='voice' AND state IN ('ringing','active')",
                          (device_id,)).fetchone():
                raise ValueError("Device is busy with another call.")
            db.execute("INSERT INTO calls (id,device_id,agent_id,agent_name,reason,context,questions,state,created,expires,updated,request_key) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", (
                call_id, device_id, agent["id"], agent["name"], reason, context,
                json.dumps(questions), "ringing", now, now + ring_seconds, now, request_key,
            ))
        return self.get(call_id)

    def create_chat(self, device_id: str, agent_id: str) -> dict[str, Any]:
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            agent = db.execute("SELECT name FROM credentials WHERE id=? AND kind='agent'", (agent_id,)).fetchone()
            if agent is None:
                raise ValueError("Agent not found")
            row = db.execute("SELECT id FROM calls WHERE device_id=? AND agent_id=? AND channel='chat' AND state='active'", (device_id, agent_id)).fetchone()
            if row:
                return self._get(db, row["id"])
            identity, now = secrets.token_hex(12), time.time()
            db.execute("INSERT INTO calls (id,device_id,agent_id,agent_name,reason,context,questions,state,created,expires,updated,request_key,channel) VALUES (?,?,?,?,?,'','[]','active',?,0,?,NULL,'chat')",
                       (identity, device_id, agent_id, agent["name"], "Messages with " + agent["name"], now, now))
            return self._get(db, identity)

    def pending_messages(self, agent_id: str) -> list[dict[str, Any]]:
        with self.connect() as db:
            self.expire(db)
            rows = db.execute("SELECT DISTINCT c.id FROM calls c JOIN pending_questions p ON p.call_id=c.id WHERE c.agent_id=? AND p.resolved=0 ORDER BY c.updated LIMIT 30", (agent_id,)).fetchall()
            return [self._get(db, row["id"]) for row in rows]

    def agent_presence(self, agent_id: str, online: bool = True) -> None:
        with self.connect() as db:
            db.execute("UPDATE credentials SET seen=? WHERE id=? AND kind='agent'", (time.time() if online else 0, agent_id))

    def agents(self) -> list[dict[str, Any]]:
        with self.connect() as db:
            return [{**dict(r), "online": r["seen"] > time.time() - 35} for r in db.execute("SELECT id,name,seen FROM credentials WHERE kind='agent' ORDER BY name")]

    def request_task_sync(self, device_id: str | None = None, agent_id: str | None = None) -> None:
        with self.connect() as db:
            devices = db.execute("SELECT id FROM credentials WHERE kind='device' AND (? IS NULL OR id=?)", (device_id, device_id)).fetchall()
            agents = db.execute("SELECT id FROM credentials WHERE kind='agent' AND (? IS NULL OR id=?)", (agent_id, agent_id)).fetchall()
            for device in devices:
                for agent in agents:
                    db.execute("""INSERT INTO task_sync VALUES (?,?,?,'pending',?,NULL,'Waiting for agent task inventory')
                        ON CONFLICT(agent_id,device_id) DO UPDATE SET id=excluded.id,state='pending',requested=excluded.requested,completed=NULL,note=excluded.note
                        WHERE task_sync.state!='pending' AND task_sync.requested<?""",
                        (secrets.token_hex(12), agent["id"], device["id"], time.time(), time.time() - 60))

    def task_sync_requests(self, device_id: str | None = None, agent_id: str | None = None,
                           pending_only: bool = False) -> list[dict[str, Any]]:
        with self.connect() as db:
            return [dict(r) for r in db.execute("SELECT s.*,c.name AS agent_name FROM task_sync s JOIN credentials c ON c.id=s.agent_id WHERE (? IS NULL OR s.device_id=?) AND (? IS NULL OR s.agent_id=?) AND (?=0 OR s.state='pending') ORDER BY s.requested DESC",
                                              (device_id, device_id, agent_id, agent_id, int(pending_only)))]

    def complete_task_sync(self, agent_id: str, request_id: str, task_keys: list[str],
                           available: bool, note: str) -> dict[str, Any]:
        with self.connect() as db:
            self.tasks_schema(db)
            row = db.execute("SELECT * FROM task_sync WHERE id=? AND agent_id=?", (request_id, agent_id)).fetchone()
            if row is None:
                raise ValueError("Sync request not found")
            if available:
                existing = db.execute("SELECT id,task_key FROM scheduled_tasks WHERE agent_id=? AND device_id=?", (agent_id, row["device_id"])).fetchall()
                known = {r["task_key"] for r in existing}
                if not set(task_keys) <= known:
                    raise ValueError("Publish each task before completing its inventory sync")
                for task in existing:
                    if task["task_key"] not in task_keys:
                        db.execute("UPDATE scheduled_tasks SET enabled=0,updated=? WHERE id=?", (time.time(), task["id"]))
            db.execute("UPDATE task_sync SET state=?,completed=?,note=? WHERE id=?", ("completed" if available else "unavailable", time.time(), note, request_id))
            return dict(db.execute("SELECT * FROM task_sync WHERE id=?", (request_id,)).fetchone())

    def _get(self, db: sqlite3.Connection, call_id: str) -> dict[str, Any]:
        row = db.execute("SELECT * FROM calls WHERE id=?", (call_id,)).fetchone()
        if row is None:
            raise KeyError("Call not found")
        result = dict(row)
        result["questions"] = json.loads(result["questions"])
        result["messages"] = [dict(r) for r in db.execute(
            "SELECT id,role,text,created,reply_to,is_final,is_stream FROM messages WHERE call_id=? ORDER BY created,rowid",
            (call_id,))]
        result["pending_questions"] = [dict(r) for r in db.execute(
            "SELECT m.id,m.text,m.created FROM pending_questions p JOIN messages m ON m.id=p.message_id "
            "WHERE p.call_id=? AND p.resolved=0 ORDER BY m.created,m.rowid", (call_id,))]
        result["awaiting_agent"] = bool(result["pending_questions"])
        result["response_mode"] = "agent"
        return result

    def get(self, call_id: str) -> dict[str, Any]:
        with self.connect() as db:
            self.expire(db)
            return self._get(db, call_id)

    def inbox(self, device_id: str) -> list[dict[str, Any]]:
        with self.connect() as db:
            self.expire(db)
            db.execute("UPDATE credentials SET seen=? WHERE id=?", (time.time(), device_id))
            ids = db.execute("SELECT id FROM calls WHERE device_id=? ORDER BY updated DESC LIMIT 30",
                             (device_id,)).fetchall()
            return [self._get(db, r["id"]) for r in ids]

    def transition(self, call_id: str, state: str) -> dict[str, Any]:
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            self.expire(db)
            current = self._get(db, call_id)
            if current["state"] == state:
                return current
            if state not in TRANSITIONS.get(current["state"], set()):
                raise ValueError(f"Cannot change {current['state']} to {state}")
            db.execute("UPDATE calls SET state=?,updated=? WHERE id=?",
                       (state, time.time(), call_id))
        return self.get(call_id)

    def append(self, call_id: str, role: str, text: str, message_id: str,
               reply_to: str | None = None, final: bool = True,
               track_question: bool = True) -> dict[str, Any]:
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            current = self._get(db, call_id)
            existing = db.execute("SELECT * FROM messages WHERE id=?", (message_id,)).fetchone()
            if existing:
                if (existing["call_id"], existing["role"], existing["text"], bool(existing["is_final"])) != (call_id, role, text, final) or (reply_to is not None and existing["reply_to"] != reply_to):
                    raise ValueError("Message ID already used for different content")
                return current
            # Agent results remain deliverable after hang-up; never change the call's state.
            if current["state"] != "active" and role != "agent":
                raise ValueError("The call is not active")
            if len(current["messages"]) >= 500:
                raise ValueError("Call transcript limit reached")
            if reply_to is not None and not db.execute(
                "SELECT 1 FROM messages WHERE id=? AND call_id=? AND role='user'", (reply_to, call_id)
            ).fetchone():
                raise ValueError("Reply must reference a user message in this conversation")
            if role == "agent" and reply_to is None and current["pending_questions"]:
                reply_to = current["pending_questions"][0]["id"]
            if role == "status" and not db.execute(
                "SELECT 1 FROM pending_questions WHERE message_id=? AND call_id=? AND resolved=0",
                (reply_to, call_id),
            ).fetchone():
                return current  # A fast agent answer already arrived; do not add a stale hold.
            db.execute("INSERT INTO messages (id,call_id,role,text,created,reply_to,is_final) VALUES (?,?,?,?,?,?,?)",
                       (message_id, call_id, role, text, time.time(), reply_to, int(final)))
            if role == "user" and track_question:
                db.execute("INSERT INTO pending_questions(message_id,call_id) VALUES (?,?)", (message_id, call_id))
            if role == "agent" and final and reply_to is not None:
                db.execute("UPDATE pending_questions SET resolved=1 WHERE message_id=? AND call_id=?", (reply_to, call_id))
            db.execute("UPDATE calls SET updated=? WHERE id=?", (time.time(), call_id))
        return self.get(call_id)

    def agent_messages(self, device_id: str, offset: int = 0) -> dict[str, Any]:
        """Durable message feed, including replies received after the phone hung up."""
        with self.connect() as db:
            rows = db.execute(
                "SELECT m.id,m.call_id,m.text,m.created,m.reply_to,m.is_final,m.is_stream,c.agent_name,c.reason,"
                "COALESCE((SELECT q.text FROM messages q WHERE q.call_id=m.call_id AND q.role='user' "
                "AND ((m.reply_to IS NOT NULL AND q.id=m.reply_to) OR (m.reply_to IS NULL AND q.created<m.created)) "
                "ORDER BY q.created DESC,q.rowid DESC LIMIT 1), c.reason) AS question "
                "FROM messages m JOIN calls c ON c.id=m.call_id WHERE c.device_id=? AND m.role='agent' "
                "ORDER BY m.created DESC,m.rowid DESC LIMIT 51 OFFSET ?", (device_id, offset),
            ).fetchall()
            total = db.execute("SELECT count(*) FROM messages m JOIN calls c ON c.id=m.call_id "
                               "WHERE c.device_id=? AND m.role='agent'", (device_id,)).fetchone()[0]
        return {"messages": [dict(r) for r in rows[:50]], "has_more": len(rows) > 50,
                "next_offset": offset + min(len(rows), 50), "total": total}

    def append_chunk(self, call_id: str, reply_to: str, stream_id: str, sequence: int,
                     text: str, final: bool) -> dict[str, Any]:
        message_id = "stream-" + stream_id
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            self._get(db, call_id)
            if not db.execute("SELECT 1 FROM messages WHERE id=? AND call_id=? AND role='user'", (reply_to, call_id)).fetchone():
                raise ValueError("Stream must answer a user message in this conversation")
            previous = db.execute("SELECT * FROM messages WHERE id=?", (message_id,)).fetchone()
            if previous is not None and (previous["call_id"] != call_id or previous["reply_to"] != reply_to or not previous["is_stream"]):
                raise ValueError("Stream ID already belongs to a different reply")
            chunk = db.execute("SELECT * FROM reply_chunks WHERE message_id=? AND sequence=?", (message_id, sequence)).fetchone()
            if chunk is not None:
                if chunk["text"] != text or bool(chunk["final"]) != final:
                    raise ValueError("Chunk retry must contain identical content")
                return self._get(db, call_id)
            expected = db.execute("SELECT count(*) FROM reply_chunks WHERE message_id=?", (message_id,)).fetchone()[0]
            if sequence != expected:
                raise ValueError("Send chunks in sequence starting at zero")
            if previous is not None and previous["is_final"]:
                raise ValueError("The reply stream is already complete")
            combined = (previous["text"] if previous is not None else "") + text
            if not combined.strip() or len(combined) > 8000:
                raise ValueError("Reply must contain 1 to 8000 characters")
            db.execute("INSERT INTO reply_chunks VALUES (?,?,?,?)", (message_id, sequence, text, int(final)))
            if previous is None:
                db.execute("INSERT INTO messages (id,call_id,role,text,created,reply_to,is_final,is_stream) VALUES (?,?,'agent',?,?,?,?,1)", (message_id, call_id, combined, time.time(), reply_to, int(final)))
            else:
                db.execute("UPDATE messages SET text=?,is_final=? WHERE id=?", (combined, int(final), message_id))
            if final:
                db.execute("UPDATE pending_questions SET resolved=1 WHERE message_id=? AND call_id=?", (reply_to, call_id))
            db.execute("UPDATE calls SET updated=? WHERE id=?", (time.time(), call_id))
            return self._get(db, call_id)

    def request_callback(self, device_id: str, agent_id: str, previous: str | None,
                         reason: str, request_key: str) -> dict[str, Any]:
        with self.connect() as db:
            db.execute("CREATE TABLE IF NOT EXISTS callbacks (id TEXT PRIMARY KEY, device_id TEXT, agent_id TEXT, previous_call_id TEXT, reason TEXT, state TEXT, created REAL, request_key TEXT, call_id TEXT, UNIQUE(device_id, request_key))")
            existing = db.execute("SELECT * FROM callbacks WHERE device_id=? AND request_key=?",
                                  (device_id, request_key)).fetchone()
            if existing:
                return dict(existing)
            if not db.execute("SELECT id FROM credentials WHERE id=? AND kind='agent'", (agent_id,)).fetchone():
                raise ValueError("Agent is no longer available")
            # Coalesce repeated redial presses while an agent has not acknowledged the request.
            pending = db.execute("SELECT * FROM callbacks WHERE device_id=? AND agent_id=? AND state='waiting' AND created>?",
                                 (device_id, agent_id, time.time() - 86400)).fetchone()
            if pending:
                return dict(pending)
            identity = secrets.token_hex(12)
            db.execute("INSERT INTO callbacks VALUES (?,?,?,?,?,'waiting',?,?,NULL)",
                       (identity, device_id, agent_id, previous, reason, time.time(), request_key))
            row = db.execute("SELECT * FROM callbacks WHERE id=?", (identity,)).fetchone()
            return dict(row)

    def callbacks(self, agent_id: str) -> list[dict[str, Any]]:
        with self.connect() as db:
            db.execute("CREATE TABLE IF NOT EXISTS callbacks (id TEXT PRIMARY KEY, device_id TEXT, agent_id TEXT, previous_call_id TEXT, reason TEXT, state TEXT, created REAL, request_key TEXT, call_id TEXT, UNIQUE(device_id, request_key))")
            db.execute("UPDATE callbacks SET state='expired' WHERE state='waiting' AND created<?",
                       (time.time() - 86400,))
            return [dict(r) for r in db.execute("SELECT * FROM callbacks WHERE agent_id=? AND state='waiting' ORDER BY created", (agent_id,))]

    def answer_callback(self, identity: str, agent: dict[str, Any], context: str,
                        questions: list[str]) -> dict[str, Any]:
        if len(context) > 12000 or len(questions) > 12 or any(len(q) > 1000 for q in questions):
            raise ValueError("Callback context is too large")
        rows = self.callbacks(agent["id"])
        row = next((r for r in rows if r["id"] == identity), None)
        if row is None:
            with self.connect() as db:
                done = db.execute("SELECT call_id FROM callbacks WHERE id=? AND agent_id=? AND state='answered'", (identity, agent["id"])).fetchone()
            if done:
                return self.get(done["call_id"])
            raise ValueError("Callback not found or expired")
        call = self.create_call(agent, row["device_id"], "Callback: " + row["reason"],
                                context, questions, 90, "callback-" + identity)
        with self.connect() as db:
            db.execute("UPDATE callbacks SET state='answered',call_id=? WHERE id=?",
                       (call["id"], identity))
        return call

    def tasks_schema(self, db: sqlite3.Connection) -> None:
        db.executescript("""
        CREATE TABLE IF NOT EXISTS scheduled_tasks (
          id TEXT PRIMARY KEY, agent_id TEXT NOT NULL, device_id TEXT NOT NULL,
          task_key TEXT NOT NULL, title TEXT NOT NULL, schedule TEXT NOT NULL,
          timezone TEXT NOT NULL, next_run TEXT, enabled INTEGER NOT NULL,
          description TEXT NOT NULL, updated REAL NOT NULL, UNIQUE(agent_id,device_id,task_key));
        CREATE TABLE IF NOT EXISTS task_results (
          id TEXT PRIMARY KEY, task_id TEXT NOT NULL, run_key TEXT NOT NULL,
          status TEXT NOT NULL, summary TEXT NOT NULL, output TEXT NOT NULL,
          started_at TEXT, finished_at TEXT, received REAL NOT NULL, UNIQUE(task_id,run_key));
        """)

    def report_task(self, agent: dict[str, Any], device_id: str, task_key: str, title: str,
                    schedule: str, timezone: str, next_run: str | None, enabled: bool,
                    description: str) -> dict[str, Any]:
        with self.connect() as db:
            self.tasks_schema(db)
            if not db.execute("SELECT id FROM credentials WHERE kind='device' AND id=?", (device_id,)).fetchone():
                raise ValueError("Unknown device")
            db.execute("""INSERT INTO scheduled_tasks VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(agent_id,device_id,task_key) DO UPDATE SET title=excluded.title,
                schedule=excluded.schedule,timezone=excluded.timezone,next_run=excluded.next_run,
                enabled=excluded.enabled,description=excluded.description,updated=excluded.updated""",
                (secrets.token_hex(12),agent["id"],device_id,task_key,title,schedule,timezone,
                 next_run,int(enabled),description,time.time()))
            row = db.execute("SELECT * FROM scheduled_tasks WHERE agent_id=? AND device_id=? AND task_key=?",
                             (agent["id"],device_id,task_key)).fetchone()
            return dict(row)

    def report_result(self, agent_id: str, task_id: str, run_key: str, status: str,
                      summary: str, output: str, started: str | None, finished: str | None) -> dict[str, Any]:
        with self.connect() as db:
            self.tasks_schema(db)
            if not db.execute("SELECT id FROM scheduled_tasks WHERE id=? AND agent_id=?", (task_id,agent_id)).fetchone():
                raise ValueError("Task not found")
            db.execute("""INSERT INTO task_results VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT(task_id,run_key) DO UPDATE SET status=excluded.status,
                summary=excluded.summary,output=excluded.output,started_at=excluded.started_at,
                finished_at=excluded.finished_at,received=excluded.received""",
                (secrets.token_hex(12),task_id,run_key,status,summary,output,started,finished,time.time()))
            row = db.execute("SELECT * FROM task_results WHERE task_id=? AND run_key=?", (task_id,run_key)).fetchone()
            return dict(row)

    def tasks(self, device_id: str | None = None, agent_id: str | None = None) -> list[dict[str, Any]]:
        with self.connect() as db:
            self.tasks_schema(db)
            rows = db.execute("""SELECT t.*,c.name AS agent_name FROM scheduled_tasks t
                JOIN credentials c ON c.id=t.agent_id WHERE (? IS NULL OR t.device_id=?)
                AND (? IS NULL OR t.agent_id=?) ORDER BY MAX(t.updated,COALESCE((SELECT MAX(received) FROM task_results WHERE task_id=t.id),0)) DESC,t.id LIMIT 100""",
                (device_id,device_id,agent_id,agent_id)).fetchall()
            result = []
            for row in rows:
                task = dict(row)
                task["results"] = [dict(r) for r in db.execute("SELECT * FROM task_results WHERE task_id=? ORDER BY received DESC LIMIT 20", (task["id"],))]
                result.append(task)
            return result
