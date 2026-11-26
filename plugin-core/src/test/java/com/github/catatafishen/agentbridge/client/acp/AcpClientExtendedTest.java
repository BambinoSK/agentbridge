package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.client.acp.transport.JsonRpcException;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Additional tests for AcpClient static methods not covered by AcpClientTest.
 */
class AcpClientExtendedTest {

    @Nested
    class ExtractRootCauseMessage {
        @Test
        void skipsPromptFailedPrefix() {
            RuntimeException cause = new RuntimeException("Real problem here");
            RuntimeException e = new RuntimeException("Prompt failed for agent: details", cause);
            String result = AcpClient.extractRootCauseMessage(e);
            assertEquals("Real problem here", result);
        }

        @Test
        void skipsPromptInterruptedPrefix() {
            RuntimeException cause = new RuntimeException("Actual error");
            RuntimeException e = new RuntimeException("Prompt interrupted for copilot", cause);
            String result = AcpClient.extractRootCauseMessage(e);
            assertEquals("Actual error", result);
        }

        @Test
        void returnsLastNonFilteredMessage() {
            RuntimeException deep = new RuntimeException("deepest");
            RuntimeException mid = new RuntimeException("middle", deep);
            RuntimeException top = new RuntimeException("top", mid);
            assertEquals("deepest", AcpClient.extractRootCauseMessage(top));
        }

        @Test
        void returnsNullForOnlyFilteredMessages() {
            RuntimeException e = new RuntimeException("Prompt failed for agent: x");
            assertNull(AcpClient.extractRootCauseMessage(e));
        }

        @Test
        void returnsNullForNullMessage() {
            RuntimeException e = new RuntimeException((String) null);
            assertNull(AcpClient.extractRootCauseMessage(e));
        }

        @Test
        void returnsNullForBlankMessage() {
            RuntimeException e = new RuntimeException("   ");
            assertNull(AcpClient.extractRootCauseMessage(e));
        }
    }

    @Nested
    class DescribePromptFailure {
        private final JsonRpcException rpcError =
            new JsonRpcException(-32603, "Internal error", JsonParser.parseString("{\"status\":429}"));

        @Test
        void agentDescriptionOfWrappedRpcErrorWins() {
            RuntimeException e = new RuntimeException("wrapper", rpcError);
            assertEquals("described", AcpClient.describePromptFailure(e, error -> "described"));
        }

        @Test
        void describerGetsTheRpcErrorFromTheCauseChain() {
            JsonRpcException[] seen = new JsonRpcException[1];
            AcpClient.describePromptFailure(new RuntimeException("wrapper", rpcError), error -> {
                seen[0] = error;
                return null;
            });
            assertSame(rpcError, seen[0]);
        }

        @Test
        void noAgentDescriptionKeepsMessageWithRawData() {
            RuntimeException e = new RuntimeException("wrapper", rpcError);
            assertEquals("Internal error: {\"status\":429}", AcpClient.describePromptFailure(e, error -> null));
        }

        @Test
        void withoutRpcErrorDescriberIsNotAsked() {
            RuntimeException e = new RuntimeException("timeout");
            assertEquals("timeout", AcpClient.describePromptFailure(e, error -> "described"));
        }
    }
}
