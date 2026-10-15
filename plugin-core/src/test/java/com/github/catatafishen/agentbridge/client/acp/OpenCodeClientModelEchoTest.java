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
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Pins that a model change on OpenCode v2 — which the plugin routes through
 * {@code session/set_config_option(configId=model)} because v2 removed
 * {@code session/set_model} — applies the {@code configOptions} array echoed
 * in the response to the local option state. OpenCode v2 never pushes
 * {@code config_option_update} notifications; the echo is the only state
 * channel, and it carries cross-effects such as effort resetting to the
 * model's default after a model switch. Without applying it, the session
 * options menu keeps showing the pre-switch (stale) selection.
 */
class OpenCodeClientModelEchoTest {

    /**
     * Transport stub: records the last request and hands out a pre-completed
     * future as the response, so {@code sendSetModel}'s blocking wait and the
     * echo application both run synchronously.
     */
    private static final class StubTransport extends JsonRpcTransport {
        String lastMethod;
        JsonObject lastParams;
        CompletableFuture<JsonElement> nextResponse = new CompletableFuture<>();

        @Override
        public CompletableFuture<JsonElement> sendRequest(String method, JsonObject params) {
            this.lastMethod = method;
            this.lastParams = params;
            return nextResponse;
        }
    }

    private static void reportVersion(AbstractClient client, String version) throws Exception {
        Field capabilities = AcpClient.class.getDeclaredField("capabilities");
        capabilities.setAccessible(true);
        capabilities.set(client, new InitializeResponse(
            1, new InitializeResponse.AgentInfo("OpenCode", null, version), null, null));
    }

    @Test
    void modelChangeRoutesThroughConfigOptionAndAppliesEcho() throws Exception {
        StubTransport transport = new StubTransport();
        OpenCodeClient client = new OpenCodeClient(mock(Project.class), transport);
        reportVersion(client, "2.0.27");

        // Echo of session/set_config_option(model): model switched to m-b AND
        // the effort option reset to the new model's default (cross-effect).
        String echo = "{\"configOptions\":[" +
            "{\"id\":\"model\",\"name\":\"Model\",\"category\":\"model\",\"type\":\"select\"," +
            "\"currentValue\":\"m-b\",\"options\":[{\"value\":\"m-a\",\"name\":\"A\"},{\"value\":\"m-b\",\"name\":\"B\"}]}," +
            "{\"id\":\"effort\",\"name\":\"Effort\",\"description\":\"Available effort levels for this model\"," +
            "\"category\":\"thought_level\",\"type\":\"select\",\"currentValue\":\"default\"," +
            "\"options\":[{\"value\":\"low\",\"name\":\"Low\"},{\"value\":\"medium\",\"name\":\"Medium\"}," +
            "{\"value\":\"high\",\"name\":\"High\"},{\"value\":\"default\",\"name\":\"Default\"}]}" +
            "]}";
        transport.nextResponse.complete(JsonParser.parseString(echo).getAsJsonObject());

        client.setModel("ses-1", "m-b");

        // Wire shape: v2 routes model changes through the config option.
        assertEquals("session/set_config_option", transport.lastMethod);
        assertEquals("ses-1", transport.lastParams.get("sessionId").getAsString());
        assertEquals("model", transport.lastParams.get("configId").getAsString());
        assertEquals("m-b", transport.lastParams.get("value").getAsString());

        // The echoed state (including the effort reset) reached the option state
        // the session options menu reads: initialValue is the agent-reported
        // current value shown when the user has no stored pick.
        var effort = client.listSessionOptions().stream()
            .filter(o -> o.key().equals("effort"))
            .findFirst().orElse(null);
        assertNotNull(effort, "effort option must be listed after the echo");
        assertEquals("default", effort.initialValue(),
            "model switch must reset effort per the echoed cross-effect");
        assertTrue(effort.values().containsAll(java.util.List.of("low", "medium", "high", "default")));
    }

    @Test
    void v1ModelChangeUsesLegacyMethod() throws Exception {
        StubTransport transport = new StubTransport();
        OpenCodeClient client = new OpenCodeClient(mock(Project.class), transport);
        reportVersion(client, "1.179.7");

        transport.nextResponse.complete(JsonParser.parseString("{}").getAsJsonObject());

        client.setModel("ses-1", "m-a");

        assertEquals("session/set_model", transport.lastMethod);
        assertEquals("m-a", transport.lastParams.get("modelId").getAsString());
    }
}
