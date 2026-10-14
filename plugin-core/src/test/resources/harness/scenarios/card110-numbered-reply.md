All evidence is in. Here's the complete picture:

## Direct answer: No — AgentBridge isn't stripping subagent tools. But your v1/v2 instinct is half-right, just about a different symptom.

**1. Subagents DO have tools — proven by probe:**

| Probe | Result |
|---|---|
| `general` subagent | `skill` + `execute` (Code Mode) → 22+ tools, including the full agentbridge MCP catalog (157 tools), internal-registry, opencode tools |
| `explore` subagent | **1 tool** — `skill` only (permission-gated) |

So "subagents have no tools" was an over-generalization from one broken agent type. `general` subagents are fully functional — including AgentBridge's IDE tools reaching the child session, which disproves my earlier claim that "child sessions don't inherit Code Mode/MCP." I've retracted that and stored the corrected facts in memory.

**2. Why `explore` specifically is broken:** its built-in definition assumes native `read`/`grep`/`glob`-style tools. This deployment's tool surface is Code Mode — no native file tools at top level, everything funnels through `execute`. `explore`'s allowlist doesn't include `execute`, so it resolves to nothing but `skill`. It's an agent-definition-vs-surface mismatch inside OpenCode's subagent system — not removal by AgentBridge. (The first subagent also didn't help itself by trying to load tool names as *skills*, but even behaving perfectly it had nothing to work with.)

**3. AgentBridge's actual role:** it *adds* the IDE tools (they reach child sessions too — the probe saw them). It even ships its own OpenCode agent definitions tuned for this setup — `plugin-core/src/main/resources/agents/opencode/ide-general.md` plus an `intellij-explore` variant per its docs. The mismatch is that OpenCode's `subagent` tool exposes the *built-in* explore/general instead of those.

**4. Your v1/v2 theory — vindicated, different symptom:** you're on **OpenCode 2.0.27**, and the IDE log shows real v1→v2 drift being handled:

```
OpenCodeClient: OpenCode 2.0.27: `opencode acp` has no --port flag, so subagent transcripts are unavailable
```

AgentBridge expected the v1-style port flag, detects 2.0.27, and degrades gracefully — the cost is **subagent transcript visibility in the chat UI**, not tools. Notably, that string doesn't exist in your clone of the agentbridge repo, so the installed plugin is *newer* than your clone — the v2 handling was added actively. And OpenCode's migration guide confirms the only intentional v1→v2 breaking changes are the server API and plugin API — exactly the layer AgentBridge touches.

**5. Side finding:** the IDE log is spamming `spawn wmic.exe ENOENT` — the plugin's Node layer calls `wmic`, which Windows 11 removed. A genuine, separate AgentBridge bug worth reporting.

**Practical rule from now on:** delegate with `general`, never `explore`.

[quick-reply: Continue the XML search | Report the wmic bug | File explore-toolset issue]
