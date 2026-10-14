#!/usr/bin/env python3
"""Fake ACP agent for the AgentBridge real-behavior test harness.

Speaks the ACP JSON-RPC protocol over stdio exactly the way real agents
(OpenCode, Hermes, Copilot) do: newline-delimited JSON, one message per line
(the framing JsonRpcTransport expects). Everything it does is driven by a
scenario JSON file, so a scenario is a faithful replay of wire behavior that
caused a real problem.

Usage:  python fake_acp_agent.py <scenario.json> <harness-log.json>

The harness log records every request the CLIENT sent (method + params) plus
every permission response the client gave to agent-initiated requests, so
JUnit-side assertions can verify what the plugin actually put on the wire.
"""

import json
import os
import re
import sys
import time


def build_chunks(text, policy):
    """Deterministic streaming chunker.

    Splits text into word-boundary chunks of varying sizes, with forced
    splits after specific tokens (the exact boundaries where field reports
    suspected data loss), splits after newline-terminated tokens, and
    occasional mid-token splits — reproducing how a real LLM agent streams
    agent_message_chunk notifications. Roundtrip-safe by construction:
    ''.join(build_chunks(t, p)) == t.
    """
    seed = policy.get("seed", 1)
    forced = set(policy.get("forcedSplits", []))
    nl_prob = policy.get("newlineSplitProbability", 0.5)
    mid_every = policy.get("midTokenSplitEvery", 0)

    state = seed & 0xFFFFFFFF

    def rnd():
        nonlocal state
        state = (1103515245 * state + 12345) & 0x7FFFFFFF
        return state / 0x7FFFFFFF

    tokens = re.findall(r"\S+\s*", text)
    chunks = []
    cur = ""
    words_in_chunk = 0
    next_word_target = 3 + int(rnd() * 7)

    def flush():
        nonlocal cur, words_in_chunk, next_word_target
        if cur:
            chunks.append(cur)
        cur = ""
        words_in_chunk = 0
        next_word_target = 3 + int(rnd() * 7)

    i = 0
    seen_tokens = 0
    while i < len(tokens):
        tok = tokens[i]
        word = tok.strip()
        cur += tok
        words_in_chunk += 1
        seen_tokens += 1

        if word in forced and rnd() < 0.9:
            flush()
            i += 1
            continue
        if tok.endswith("\n") and rnd() < nl_prob:
            flush()
            i += 1
            continue
        if words_in_chunk >= next_word_target and rnd() < 0.6:
            flush()
            i += 1
            continue

        if mid_every and seen_tokens % mid_every == 0 and len(word) > 6 and not word.startswith("`"):
            # Mid-token split: first half stays in the current chunk, the
            # remainder is re-queued with the original trailing whitespace.
            cut = 2 + int(rnd() * (len(word) - 5))
            cur = cur[: -len(tok)] + word[:cut]
            chunks.append(cur)
            cur = ""
            words_in_chunk = 0
            tokens[i] = word[cut:] + tok[len(word):]
            continue

        i += 1

    if cur:
        chunks.append(cur)
    # The re-queued remainder keeps the original trailing whitespace, so the
    # concatenation of all chunks always reproduces the input exactly.
    assert "".join(chunks) == text, "chunker lost data"
    return chunks


class FakeAgent:
    def __init__(self, scenario, log_path):
        self.scenario = scenario
        self.scenario_path = scenario.get("_path", ".")
        self.log_path = log_path
        self.log = {"requests": [], "permissionResponses": [], "notifications": []}
        self.session_id = None
        self.config_options = None
        self.next_id = 1000
        self.alive = True

    # ── wire helpers ────────────────────────────────────────────────

    def send(self, msg):
        sys.stdout.write(json.dumps(msg) + "\n")
        sys.stdout.flush()

    def respond(self, req_id, result):
        self.send({"jsonrpc": "2.0", "id": req_id, "result": result})

    def respond_error(self, req_id, code, message, data=None):
        err = {"code": code, "message": message}
        if data is not None:
            err["data"] = data
        self.send({"jsonrpc": "2.0", "id": req_id, "error": err})

    def notify(self, method, params):
        self.send({"jsonrpc": "2.0", "method": method, "params": params})

    def request(self, method, params):
        """Agent-to-client request; blocks for the client's response."""
        rid = self.next_id
        self.next_id += 1
        self.send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        return self.wait_response(rid)

    def flush_log(self):
        with open(self.log_path, "w", encoding="utf-8") as f:
            json.dump(self.log, f, indent=1)

    # ── main loop ───────────────────────────────────────────────────

    def run(self):
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except json.JSONDecodeError:
                continue
            self.handle(msg)
            if not self.alive:
                break
        self.flush_log()

    def handle(self, msg):
        method = msg.get("method")
        req_id = msg.get("id")
        params = msg.get("params") or {}

        if method is None:
            # A response to one of our agent-to-client requests.
            self.log["permissionResponses"].append(msg)
            return

        self.log["requests"].append({"method": method, "params": params})

        if method == "initialize":
            self.respond(req_id, self.initialize_result())
        elif method == "initialized":
            pass  # notification
        elif method == "session/new":
            self.session_id = self.scenario["sessionNewResult"].get(
                "sessionId", "ses_harness_0001"
            )
            result = dict(self.scenario["sessionNewResult"])
            result["sessionId"] = self.session_id
            self.config_options = result.get("configOptions")
            self.respond(req_id, result)
        elif method == "session/set_model":
            # Real OpenCode v2 behavior: not implemented.
            self.respond_error(req_id, -32601, "Method not found")
        elif method == "session/set_config_option":
            self.handle_set_config_option(req_id, params)
        elif method == "session/prompt":
            self.run_prompt_script(req_id, params)
        elif method == "session/cancel":
            self.respond(req_id, {"status": "ok"})
            self.alive = False
        else:
            self.respond(req_id, {})

    # ── method bodies ───────────────────────────────────────────────

    def initialize_result(self):
        agent = self.scenario.get("agentInfo", {"name": "harness-fake-agent", "version": "0.1"})
        return {
            "protocolVersion": 1,
            "agentInfo": agent,
            "authMethods": [],
            "agentCapabilities": {},
        }

    def handle_set_config_option(self, req_id, params):
        config_id = params.get("configId")
        behavior = self.scenario.get("setConfigOption", {}).get(config_id)
        if behavior is None:
            # Unknown option: mirror real OpenCode v2.0.27 behavior.
            self.respond_error(
                req_id,
                -32602,
                "Invalid params: unknown config option: " + str(config_id),
                {"configId": config_id},
            )
            return
        if "error" in behavior:
            self.respond_error(req_id, behavior["error"]["code"], behavior["error"]["message"])
            return
        # Echo behavior: apply the new value and return the updated option list.
        new_value = params.get("value")
        for opt in self.config_options or []:
            if opt["id"] == config_id:
                opt["currentValue"] = new_value
            # Cross-effects, e.g. real OpenCode resets effort when the model changes.
            for effect in behavior.get("effects", []):
                for other in self.config_options or []:
                    if other["id"] == effect["id"]:
                        other["currentValue"] = effect["currentValue"]
        self.respond(req_id, {"configOptions": self.config_options})

    def wait_response(self, rid, timeout_s=30):
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            line = sys.stdin.readline()
            if not line:
                return None
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except json.JSONDecodeError:
                continue
            if msg.get("id") == rid:
                self.log["permissionResponses"].append(msg)
                return msg
            # Something else arrived while waiting; log it.
            self.log["requests"].append({"method": msg.get("method"), "params": msg.get("params")})
        return None

    def run_prompt_script(self, req_id, params):
        # Optional replayText: stream a fixture file as chunked
        # agent_message_chunk notifications before the scripted steps.
        replay = self.scenario.get("replayText")
        if replay:
            text_path = os.path.join(os.path.dirname(self.scenario_path), replay["textFile"])
            with open(text_path, "r", encoding="utf-8") as f:
                text = f.read()
            for chunk in build_chunks(text, replay.get("chunking", {})):
                self.notify(
                    "session/update",
                    {
                        "sessionId": self.session_id,
                        "update": {
                            "sessionUpdate": "agent_message_chunk",
                            "content": {"type": "text", "text": chunk},
                        },
                    },
                )
        script = self.scenario.get("promptScript", [])
        for step in script:
            kind = step.get("type")
            if kind == "update":
                p = step["params"]
                if "sessionId" not in p:
                    p["sessionId"] = self.session_id
                self.notify("session/update", p)
                self.log["notifications"].append(p)
            elif kind == "sleep":
                time.sleep(step.get("ms", 10) / 1000.0)
            elif kind == "permission":
                resp = self.request("session/request_permission", step["params"])
                step.setdefault("_response", {})
                step["_response"] = resp
            elif kind == "result":
                self.respond(req_id, step.get("result", {"stopReason": "end_turn"}))
                self.flush_log()
                return
        # Script ended without an explicit result: end the turn.
        self.respond(req_id, {"stopReason": "end_turn"})
        self.flush_log()


def main():
    if len(sys.argv) < 3:
        sys.stderr.write("usage: fake_acp_agent.py <scenario.json> <harness-log.json>\n")
        sys.exit(2)
    with open(sys.argv[1], "r", encoding="utf-8") as f:
        scenario = json.load(f)
    scenario["_path"] = sys.argv[1]
    FakeAgent(scenario, sys.argv[2]).run()


if __name__ == "__main__":
    main()
