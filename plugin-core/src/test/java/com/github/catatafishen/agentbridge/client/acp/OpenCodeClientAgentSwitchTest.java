package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.acp.protocol.InitializeResponse;
import com.github.catatafishen.agentbridge.client.AbstractClient;
import com.github.catatafishen.agentbridge.client.acp.transport.JsonRpcTransport;
import com.intellij.openapi.project.Project;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Pins the OpenCode v2 live agent switch: the {@code build}/{@code plan} session modes are
 * exposed as the {@code mode} config option and a switch applies to the live session via
 * {@code session/set_config_option(configId=mode)} (verified against v2.0.27: the agent's
 * self-reported mode changes in-session; {@code general}/{@code explore} are rejected with
 * -32602 "mode not found" and are Task-tool subagents, not primary modes). The menu must not
 * restart the process for switchable slugs, and a selection stored before the session existed
 * is applied on session creation.
 */
class OpenCodeClientAgentSwitchTest {

    /** Records every request; each response future is pre-completed from a script. */
    private static final class StubTransport extends JsonRpcTransport {
        static final class Call {
            final String method;
            final JsonObject params;
            Call(String method, JsonObject params) { this.method = method; this.params = params; }
        }

        final List<Call> calls = new ArrayList<>();
        private final java.util.Deque<JsonElement> scriptedResults = new java.util.ArrayDeque<>();

        void scriptResult(String json) {
            scriptedResults.add(JsonParser.parseString(json));
        }

        @Override
        public CompletableFuture<JsonElement> sendRequest(String method, JsonObject params) {
            calls.add(new Call(method, params));
            CompletableFuture<JsonElement> future = new CompletableFuture<>();
            future.complete(scriptedResults.isEmpty() ? new JsonObject() : scriptedResults.pop());
            return future;
        }

        int countOf(String method) {
            return (int) calls.stream().filter(c -> c.method.equals(method)).count();
        }
    }

    /** Echo carrying the v2 mode option (values build/plan, current build) + effort. */
    private static final String MODE_ECHO =
        "{\"configOptions\":[" +
            "{\"id\":\"mode\",\"name\":\"Session Mode\",\"category\":\"mode\",\"type\":\"select\"," +
            "\"currentValue\":\"build\",\"options\":[{\"value\":\"build\",\"name\":\"Build\"},{\"value\":\"plan\",\"name\":\"Plan\"}]}," +
            "{\"id\":\"effort\",\"name\":\"Effort\",\"category\":\"thought_level\",\"type\":\"select\"," +
            "\"currentValue\":\"default\",\"options\":[{\"value\":\"low\",\"name\":\"Low\"},{\"value\":\"default\",\"name\":\"Default\"}]}" +
            "]}";

    private static OpenCodeClient client(StubTransport transport, String version) throws Exception {
        OpenCodeClient client = new OpenCodeClient(mock(Project.class), transport);
        Field capabilities = AcpClient.class.getDeclaredField("capabilities");
        capabilities.setAccessible(true);
        capabilities.set(client, new InitializeResponse(
            1, new InitializeResponse.AgentInfo("OpenCode", null, version), null, null));
        return client;
    }

    private static void setActiveSession(AbstractClient client, String sessionId) throws Exception {
        Field session = AbstractClient.class.getDeclaredField("currentSessionId");
        session.setAccessible(true);
        session.set(client, sessionId);
    }

    /** Seeds the option state the way a real session/new + echo would. */
    private static void seedModeState(OpenCodeClient client, StubTransport transport) {
        transport.scriptResult(MODE_ECHO);
        client.setConfigOption("ses-1", "mode", "build");
    }

    @Test
    void v2LiveSwitchSendsModeConfigOption() throws Exception {
        StubTransport transport = new StubTransport();
        OpenCodeClient client = client(transport, "2.0.27");
        seedModeState(client, transport);
        setActiveSession(client, "ses-1");
        transport.calls.clear();

        assertTrue(client.canSwitchAgentLive("plan"));
        client.setCurrentAgentSlug("plan");

        assertEquals(1, transport.countOf("session/set_config_option"));
        StubTransport.Call call = transport.calls.get(0);
        assertEquals("ses-1", call.params.get("sessionId").getAsString());
        assertEquals("mode", call.params.get("configId").getAsString());
        assertEquals("plan", call.params.get("value").getAsString());
    }

    @Test
    void nonModeSlugsAreNotLiveSwitchable() throws Exception {
        StubTransport transport = new StubTransport();
        OpenCodeClient client = client(transport, "2.0.27");
        seedModeState(client, transport);
        setActiveSession(client, "ses-1");
        transport.calls.clear();

        // general/explore are Task-tool subagents on v2, not primary modes
        assertFalse(client.canSwitchAgentLive("general"));
        assertFalse(client.canSwitchAgentLive("explore"));
        client.setCurrentAgentSlug("general");
        assertEquals(0, transport.calls.size(), "no request may be sent for a non-mode slug");
    }

    @Test
    void v1KeepsRestartFlow() throws Exception {
        StubTransport transport = new StubTransport();
        OpenCodeClient client = client(transport, "1.179.7");
        seedModeState(client, transport);
        setActiveSession(client, "ses-1");
        transport.calls.clear();

        assertFalse(client.canSwitchAgentLive("plan"));
        client.setCurrentAgentSlug("plan");
        assertEquals(0, transport.calls.size(), "v1 has no config-option channel");
    }

    @Test
    void selectionBeforeSessionIsAppliedOnSessionCreated() throws Exception {
        StubTransport transport = new StubTransport();
        OpenCodeClient client = client(transport, "2.0.27");
        seedModeState(client, transport);
        transport.calls.clear(); // drop the seeding call
        // no active session yet
        client.setCurrentAgentSlug("plan");
        assertEquals(0, transport.countOf("session/set_config_option"),
            "no live session yet: the switch must be deferred, not sent");

        client.onSessionCreated("ses-2");
        assertEquals(1, transport.countOf("session/set_config_option"));
        StubTransport.Call call = transport.calls.stream()
            .filter(c -> c.method.equals("session/set_config_option")).findFirst().orElseThrow();
        assertEquals("ses-2", call.params.get("sessionId").getAsString());
        assertEquals("plan", call.params.get("value").getAsString());
    }

    @Test
    void sessionAlreadyRunningSelectedModeSendsNothing() throws Exception {
        StubTransport transport = new StubTransport();
        OpenCodeClient client = client(transport, "2.0.27");
        seedModeState(client, transport); // mode option current = build
        transport.calls.clear();

        client.setCurrentAgentSlug("build"); // matches the session's reported mode
        client.onSessionCreated("ses-3");
        assertEquals(0, transport.calls.size(),
            "redundant mode switch must not be sent");
    }
}
