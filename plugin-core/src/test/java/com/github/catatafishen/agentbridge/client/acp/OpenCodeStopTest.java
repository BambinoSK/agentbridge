package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.acp.protocol.InitializeResponse;
import com.github.catatafishen.agentbridge.client.AbstractClient;
import com.github.catatafishen.agentbridge.client.acp.transport.JsonRpcTransport;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stop in OpenCode: cancelling the turn leaves sub-agents running, so their sessions are closed first, and the
 * turn is cancelled once they have ended.
 */
class OpenCodeStopTest {

    private static final String ROOT = "ses_root";

    private JsonRpcTransport transport;
    private OpenCodeClient client;

    @BeforeEach
    void setUp() throws Exception {
        transport = mock(JsonRpcTransport.class);
        CompletableFuture<JsonElement> done = CompletableFuture.completedFuture(JsonNull.INSTANCE);
        when(transport.sendRequest(anyString(), any())).thenReturn(done);
        client = new OpenCodeClient(mock(Project.class), transport);
        setField(AbstractClient.class, "currentSessionId", ROOT);
    }

    private void setField(Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(client, value);
    }

    private void advertiseClose(boolean close) throws Exception {
        var sessionCapabilities = new InitializeResponse.SessionCapabilities(
            close ? new InitializeResponse.SessionCapabilities.CapabilityPresent() : null, null, null);
        setField(AcpClient.class, "capabilities", new InitializeResponse(1,
            new InitializeResponse.AgentInfo("OpenCode", null, "2.4.1"),
            new InitializeResponse.AgentCapabilities(null, null, null, sessionCapabilities), null));
    }

    private void childStatus(String childId, String status) {
        client.handleAgentSpecificNotification(OpenCodeChildSessions.METHOD, JsonParser.parseString("""
            {"rootSessionId": "%s", "childSessionId": "%s", "parentSessionId": "%s", "depth": 1,
             "title": "Run sleep 90 then DONE", "type": "status", "status": "%s"}"""
            .formatted(ROOT, childId, ROOT, status)).getAsJsonObject());
    }

    private List<String> closedSessions() {
        ArgumentCaptor<JsonObject> params = ArgumentCaptor.forClass(JsonObject.class);
        verify(transport, times(2)).sendRequest(eq("session/close"), params.capture());
        return params.getAllValues().stream().map(p -> p.get("sessionId").getAsString()).sorted().toList();
    }

    private void verifyCancelled() {
        verify(transport).sendNotification(eq("session/cancel"), any(JsonObject.class));
    }

    private void verifyNotCancelled() {
        verify(transport, never()).sendNotification(eq("session/cancel"), any(JsonObject.class));
    }

    @Test
    @DisplayName("Stop closes the session of every sub-agent still working, and cancels the turn once they have ended")
    void stopClosesWorkingSubAgentsBeforeCancellingTheTurn() throws Exception {
        advertiseClose(true);
        childStatus("ses_a", "running");
        childStatus("ses_b", "created");

        client.cancelSession(ROOT);

        assertEquals(List.of("ses_a", "ses_b"), closedSessions());
        verifyNotCancelled();
        childStatus("ses_a", "interrupted");
        verifyNotCancelled();
        childStatus("ses_b", "interrupted");
        InOrder order = inOrder(transport);
        order.verify(transport, times(2)).sendRequest(eq("session/close"), any());
        order.verify(transport).sendNotification(eq("session/cancel"), any(JsonObject.class));
    }

    @Test
    @DisplayName("a sub-agent that never reports its end holds the cancel back for two seconds at most")
    void cancelIsSentWhenASubAgentNeverReports() throws Exception {
        advertiseClose(true);
        childStatus("ses_a", "running");
        childStatus("ses_b", "running");

        client.cancelSession(ROOT);

        verifyNotCancelled();
        verify(transport, timeout(3000)).sendNotification(eq("session/cancel"), any(JsonObject.class));
        assertEquals(List.of("ses_a", "ses_b"), closedSessions());
    }

    @Test
    @DisplayName("a sub-agent that has finished is not closed, and neither is the main session")
    void finishedSubAgentsAndTheMainSessionStayOpen() throws Exception {
        advertiseClose(true);
        childStatus("ses_a", "running");
        childStatus("ses_a", "completed");

        client.cancelSession(ROOT);

        verify(transport, never()).sendRequest(eq("session/close"), any());
        verifyCancelled();
    }

    @Test
    @DisplayName("an OpenCode that does not offer session/close gets only the cancel")
    void withoutTheCloseCapabilityNothingIsClosed() throws Exception {
        advertiseClose(false);
        childStatus("ses_a", "running");

        client.cancelSession(ROOT);

        verifyCancelled();
        verify(transport, never()).sendRequest(eq("session/close"), any());
    }
}
