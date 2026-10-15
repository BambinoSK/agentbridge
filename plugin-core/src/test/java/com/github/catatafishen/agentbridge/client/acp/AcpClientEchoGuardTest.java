package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.client.acp.transport.JsonRpcTransport;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the generic echo application in {@link AcpClient#setConfigOption} for agents other
 * than OpenCode: the hook runs for every ACP agent, so a rejected option (JSON-RPC error),
 * a response without a {@code configOptions} key, or malformed entries must leave the local
 * option state untouched. A successful echo replaces state by id (idempotent).
 */
class AcpClientEchoGuardTest {

    private static final class StubTransport extends JsonRpcTransport {
        final List<CompletableFuture<JsonElement>> futures = new java.util.ArrayList<>();

        @Override
        public CompletableFuture<JsonElement> sendRequest(String method, JsonObject params) {
            CompletableFuture<JsonElement> future = new CompletableFuture<>();
            futures.add(future);
            return future;
        }
    }

    /** Minimal concrete client; the package-private constructor injects the stub transport. */
    private static final class TestableAcpClient extends AcpClient {
        TestableAcpClient(StubTransport transport) {
            super(null, transport);
        }

        @Override public String agentId() { return "test"; }
        @Override public String displayName() { return "Test"; }
        @Override protected List<String> buildCommand(String cwd, int mcpPort) { return List.of(); }
        @Override protected boolean isMcpToolTitle(@org.jetbrains.annotations.NotNull String protocolTitle) { return false; }
    }

    private static void setOption(AcpClient client, StubTransport transport, String configId, String value) {
        client.setConfigOption("ses-1", configId, value);
    }

    @Test
    void successfulEchoReplacesStateByIdempotently() {
        StubTransport transport = new StubTransport();
        TestableAcpClient client = new TestableAcpClient(transport);

        setOption(client, transport, "effort", "low");
        transport.futures.get(0).complete(JsonParser.parseString(
            "{\"configOptions\":[{\"id\":\"effort\",\"name\":\"Effort\",\"category\":\"thought_level\"," +
            "\"type\":\"select\",\"currentValue\":\"low\"," +
            "\"options\":[{\"value\":\"low\",\"name\":\"Low\"},{\"value\":\"high\",\"name\":\"High\"}]}]}"));
        assertEquals(1, client.getAvailableConfigOptions().size());
        assertEquals("low", client.getAvailableConfigOptions().get(0).selectedValueId());

        // second echo with the same option replaces, does not append
        setOption(client, transport, "effort", "high");
        transport.futures.get(1).complete(JsonParser.parseString(
            "{\"configOptions\":[{\"id\":\"effort\",\"name\":\"Effort\",\"category\":\"thought_level\"," +
            "\"type\":\"select\",\"currentValue\":\"high\"," +
            "\"options\":[{\"value\":\"low\",\"name\":\"Low\"},{\"value\":\"high\",\"name\":\"High\"}]}]}"));
        assertEquals(1, client.getAvailableConfigOptions().size());
        assertEquals("high", client.getAvailableConfigOptions().get(0).selectedValueId());
    }

    @Test
    void rejectedOptionLeavesStateUntouched() {
        StubTransport transport = new StubTransport();
        TestableAcpClient client = new TestableAcpClient(transport);

        setOption(client, transport, "effort", "low");
        transport.futures.get(0).completeExceptionally(new RuntimeException("simulated -32602"));
        assertEquals(0, client.getAvailableConfigOptions().size(),
            "an exceptionally-completed response (rejected option) must not touch state");
    }

    @Test
    void responseWithoutConfigOptionsLeavesStateUntouched() {
        StubTransport transport = new StubTransport();
        TestableAcpClient client = new TestableAcpClient(transport);

        setOption(client, transport, "effort", "low");
        transport.futures.get(0).complete(JsonParser.parseString("{}").getAsJsonObject());
        assertEquals(0, client.getAvailableConfigOptions().size(),
            "agents that answer without an echo must not have their state touched");
    }

    @Test
    void malformedEntriesAreSkipped() {
        StubTransport transport = new StubTransport();
        TestableAcpClient client = new TestableAcpClient(transport);

        setOption(client, transport, "effort", "low");
        transport.futures.get(0).complete(JsonParser.parseString(
            "{\"configOptions\":[1, \"x\", null]}"));
        assertEquals(0, client.getAvailableConfigOptions().size(),
            "non-object entries must be skipped");
    }
}
