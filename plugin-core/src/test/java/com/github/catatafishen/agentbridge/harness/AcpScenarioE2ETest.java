package com.github.catatafishen.agentbridge.harness;

import com.github.catatafishen.agentbridge.acp.protocol.PromptRequest;
import com.github.catatafishen.agentbridge.client.ClientPromptException;
import com.github.catatafishen.agentbridge.client.ClientStartException;
import com.github.catatafishen.agentbridge.client.AbstractClient.AgentConfigOption;
import com.github.catatafishen.agentbridge.client.acp.HarnessClient;
import com.github.catatafishen.agentbridge.model.ContentBlock;
import com.github.catatafishen.agentbridge.model.PromptResponse;
import com.github.catatafishen.agentbridge.model.SessionUpdate;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * End-to-end scenario harness: launches the real ACP client stack against the
 * fake ACP agent (fake_acp_agent.py, a real subprocess) and replays wire
 * scenarios recorded from field behavior.
 *
 * <p>Every test here either (a) pins behavior that must never regress
 * (streaming byte-transparency) or (b) pins DESIRED behavior that the paired
 * fix branch implements. Tests that fail on plain master are the harness's
 * reproduction of the reported problem — see harness/README.md.
 */
@SuppressWarnings("UnusedReturnValue")
public class AcpScenarioE2ETest extends BasePlatformTestCase {

    private Path tempDir;
    private Path agentScript;
    private Path logFile;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        tempDir = Files.createTempDirectory("acp-harness");
        agentScript = extractResource("/harness/fake_acp_agent.py", "fake_acp_agent.py");
        logFile = tempDir.resolve("harness-log.json");
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            // The streamed conversation gets persisted into the test project's
            // .agentbridge/conversation.db; close the SQLite connection BEFORE
            // the framework deletes the temp project dir, otherwise Windows
            // fails the delete with "being used by another process".
            try {
                com.github.catatafishen.agentbridge.session.db.ConversationDatabase
                        .getInstance(getProject()).dispose();
            } catch (Exception ignored) {
            }
            Thread.sleep(200);
            deleteRecursively(tempDir);
        } finally {
            super.tearDown();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private Path extractResource(String resourcePath, String fileName) throws Exception {
        Path target = tempDir.resolve(fileName);
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            assertNotNull("missing harness resource: " + resourcePath, in);
            Files.copy(in, target);
        }
        return target;
    }

    private HarnessClient startClient(String scenarioResource, String scenarioFile) throws Exception {
        String sibling = scenarioResource.endsWith(".json")
            ? scenarioResource.substring(0, scenarioResource.length() - 5) + ".md"
            : null;
        if (sibling != null && getClass().getResource(sibling) != null) {
            extractResource(sibling, scenarioFile.substring(0, scenarioFile.length() - 5) + ".md");
        }
        Path scenario = extractResource(scenarioResource, scenarioFile);
        HarnessClient client = new HarnessClient(getProject(), agentScript, scenario, logFile);
        client.start();
        return client;
    }

    private PromptResponse runPrompt(HarnessClient client, List<SessionUpdate> updates)
            throws ClientPromptException, com.github.catatafishen.agentbridge.client.ClientSessionException {
        String sessionId = client.createSession(getProject().getBasePath());
        PromptRequest request = new PromptRequest(
            sessionId, List.of(new ContentBlock.Text("run scenario")), null, null);
        return client.sendPrompt(request, updates::add);
    }

    private String readHarnessLog() throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(logFile)) {
                String content = Files.readString(logFile, StandardCharsets.UTF_8);
                if (!content.isEmpty()) return content;
            }
            Thread.sleep(100);
        }
        return "";
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception ignored) {
                }
            });
        }
    }

    private static @NotNull AgentConfigOption option(List<AgentConfigOption> options, String id) {
        return options.stream().filter(o -> o.id().equals(id)).findFirst().orElseThrow();
    }

    /** Waits until the client's config-option state matches, or fails with the last seen value. */
    private void awaitOptionState(HarnessClient client, String id, String expectedSelectedValue)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            AgentConfigOption opt = option(client.getAvailableConfigOptions(), id);
            last = opt.selectedValueId();
            if (expectedSelectedValue.equals(last)) return;
            Thread.sleep(100);
        }
        assertEquals("config option '" + id + "' never reached the expected state",
            expectedSelectedValue, last);
    }

    // ── card 110: streamed reply must arrive byte-exact ────────────────

    public void testCard110StreamedReplyIsByteExact() throws Exception {
        HarnessClient client = startClient(
            "/harness/scenarios/card110-numbered-reply.json", "card110-numbered-reply.json");
        try {
            List<SessionUpdate> updates = new CopyOnWriteArrayList<>();
            PromptResponse response = runPrompt(client, updates);

            assertNotNull(response);
            assertEquals("end_turn", response.stopReason());

            List<SessionUpdate.AgentMessageChunk> chunks = updates.stream()
                .filter(u -> u instanceof SessionUpdate.AgentMessageChunk)
                .map(u -> (SessionUpdate.AgentMessageChunk) u)
                .toList();
            // The scenario streams the reply as many small chunks; if this drops
            // to a handful, the harness stopped exercising the streaming path.
            assertTrue("expected a chunked stream, got " + chunks.size() + " chunks",
                chunks.size() >= 40);

            String joined = chunks.stream()
                .flatMap(c -> c.content().stream())
                .filter(b -> b instanceof ContentBlock.Text)
                .map(b -> ((ContentBlock.Text) b).text())
                .collect(Collectors.joining());

            try (InputStream in = getClass().getResourceAsStream(
                "/harness/scenarios/card110-numbered-reply.md")) {
                assertNotNull(in);
                String expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                // Byte-transparency of the whole pipeline: process pipes,
                // JSON-RPC transport, message parser, SessionUpdate model.
                assertEquals("streamed text diverged from the source fixture", expected, joined);
            }
        } finally {
            client.stop();
        }
    }

    // ── card 112: OpenCode v2 session options over the real wire shape ──

    public void testCard112SessionOptionsRoundTrip() throws Exception {
        HarnessClient client = startClient(
            "/harness/scenarios/card112-session-options.json", "card112-session-options.json");
        try {
            List<AgentConfigOption> options = client.getAvailableConfigOptions();
            AgentConfigOption effort = option(options, "effort");
            assertEquals(4, effort.values().size());
            assertEquals("default", effort.selectedValueId());
            AgentConfigOption model = option(options, "model");
            assertEquals("provider/glm-5.3", model.selectedValueId());

            String sessionId = client.createSession(getProject().getBasePath());

            // Setting effort must be delivered on the wire and reflected in state.
            client.setSessionOption(sessionId, "effort", "low");
            awaitOptionState(client, "effort", "low");

            // Switching the model resets effort on the agent side (real OpenCode
            // cross-effect); the client state must follow the echoed options.
            client.setSessionOption(sessionId, "model", "provider/claude-sonnet-5-5");
            awaitOptionState(client, "model", "provider/claude-sonnet-5-5");
            awaitOptionState(client, "effort", "default");

            // An option the agent does not know must be a harmless no-op, never a crash.
            client.setSessionOption(sessionId, "agent", "explore");
            Thread.sleep(300);
            assertEquals(3, client.getAvailableConfigOptions().size());

            // config_options_changed pushed mid-turn must update client state.
            List<SessionUpdate> updates = new CopyOnWriteArrayList<>();
            runPrompt(client, updates);
            awaitOptionState(client, "model", "provider/claude-sonnet-5-5");
            awaitOptionState(client, "effort", "default");

            // The wire log proves what the client actually sent.
            JsonObject log = JsonParser.parseString(readHarnessLog()).getAsJsonObject();
            var setRequests = log.getAsJsonArray("requests").asList().stream()
                .map(e -> e.getAsJsonObject())
                .filter(r -> r.has("method") && "session/set_config_option".equals(r.get("method").getAsString()))
                .toList();
            assertTrue("expected session/set_config_option on the wire", setRequests.size() >= 3);
        } catch (AssertionError | Exception e) {
            // Keep the harness log for diagnosis on failure.
            System.err.println("harness log: " + readHarnessLog());
            throw e;
        } finally {
            client.stop();
        }
    }

    // ── card 109: run_command tool results arrive as ToolCallUpdates ────

    public void testCard109RunCommandToolCallUpdates() throws Exception {
        HarnessClient client = startClient(
            "/harness/scenarios/card109-run-command.json", "card109-run-command.json");
        try {
            List<SessionUpdate> updates = new CopyOnWriteArrayList<>();
            PromptResponse response = runPrompt(client, updates);
            assertNotNull(response);

            List<SessionUpdate.ToolCallUpdate> toolUpdates = updates.stream()
                .filter(u -> u instanceof SessionUpdate.ToolCallUpdate)
                .map(u -> (SessionUpdate.ToolCallUpdate) u)
                .toList();

            assertTrue("expected run_command tool updates", toolUpdates.size() >= 4);
            var success = toolUpdates.stream()
                .filter(t -> t.toolCallId().equals("tc-harness-1")
                    && t.status() == SessionUpdate.ToolCallStatus.COMPLETED)
                .findFirst().orElseThrow();
            assertTrue(success.result(), success.result().startsWith("Command succeeded"));
            var failure = toolUpdates.stream()
                .filter(t -> t.toolCallId().equals("tc-harness-2")
                    && t.status() == SessionUpdate.ToolCallStatus.COMPLETED)
                .findFirst().orElseThrow();
            assertTrue(failure.result(), failure.result().startsWith("Command failed (exit code 1)"));
        } finally {
            client.stop();
        }
    }
}
