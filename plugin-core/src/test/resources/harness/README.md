# AgentBridge real-behavior test harness

Scenario-driven harness that reproduces wire and rendering behavior which
caused real problems in the field, so fix branches can be verified against
the actual failure modes instead of synthetic happy paths.

## Layout

- `fake_acp_agent.py` — fake ACP agent: a real subprocess speaking the
  newline-delimited JSON-RPC protocol the plugin's `JsonRpcTransport`
  expects. Replays a scenario JSON; records everything the client sent to
  a harness log for assertions.
- `scenarios/*.json` + `*.md` — scenarios and text fixtures, derived from
  real observed wire behavior (sanitized: no user names, machine paths, or
  client identifiers).
- `HarnessClient` (test sources, `client.acp` package) — an `AcpClient`
  subclass whose `buildCommand` launches the fake agent, so scenarios run
  through the full production path: ProcessBuilder, PATH resolution,
  transport over real pipes, initialize/session/prompt lifecycle.
- `AcpScenarioE2ETest` — end-to-end scenario replay.
- `MarkdownOrderedListTest` / `MarkdownStreamingPaneTest` — renderer and
  real-pane layout tests.
- `js-tests/ordered-lists.test.js` — web renderer twin (`./gradlew :plugin-core:jsTests`).

## Scenario format

```json
{
  "agentInfo": {...},
  "sessionNewResult": { "sessionId": "...", "configOptions": [...] },
  "setConfigOption": { "<configId>": { "effects": [...] } | { "error": {...} } },
  "replayText": { "textFile": "fixture.md", "chunking": { "seed": 110, ... } },
  "promptScript": [
    { "type": "update", "params": {...} },
    { "type": "sleep", "ms": 50 },
    { "type": "result", "result": { "stopReason": "end_turn" } }
  ]
}
```

`replayText` streams the fixture file as `agent_message_chunk` notifications,
chunked deterministically with adversarial boundaries (mid-token splits,
splits directly after the tokens where field reports suspected data loss).

## Red / green contract

Tests in this harness pin DESIRED behavior. On plain `origin/master` some of
them FAIL — by design: that failure is the reproduction of the reported
problem. The paired fix branch (stacked on top of this harness branch) must
turn them green without weakening any assertion:

| Scenario / test | Master | Fix branch |
|---|---|---|
| card110 E2E streamed-reply byte-exactness | green (must stay green) | green |
| card112 E2E session-option round trip | effort/model state sync assertions red | green |
| card109 E2E run_command tool updates | green (fixtures for the renderer fix) | green |
| `MarkdownOrderedListTest` | red | green |
| `MarkdownStreamingPaneTest` list structure + indent | red | green |
| `js-tests/ordered-lists.test.js` | red | green |

## Running

```
./gradlew :plugin-core:test --tests '*harness*'
./gradlew :plugin-core:jsTests          # web renderer cases
```

The fake agent needs `python` on PATH (override with
`-Dagentbridge.harness.python=...`). Scenario payloads are sanitized.
