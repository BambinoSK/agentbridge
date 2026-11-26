package com.github.catatafishen.agentbridge.acp.transport;

import com.github.catatafishen.agentbridge.client.acp.transport.JsonRpcException;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JsonRpcException")
class JsonRpcExceptionTest {

    @Nested
    @DisplayName("constructor")
    class ConstructorTest {

        @Test
        @DisplayName("stores code and message")
        void storesCodeAndMessage() {
            JsonRpcException ex = new JsonRpcException(-32603, "Internal error");

            assertEquals(-32603, ex.getCode());
            assertEquals("Internal error", ex.getMessage());
        }
    }

    @Nested
    @DisplayName("getCode")
    class GetCodeTest {

        @Test
        @DisplayName("returns the code passed to constructor")
        void returnsCode() {
            JsonRpcException ex = new JsonRpcException(-32600, "Invalid Request");

            assertEquals(-32600, ex.getCode());
        }
    }

    @Nested
    @DisplayName("getMessage")
    class GetMessageTest {

        @Test
        @DisplayName("returns the message passed to constructor")
        void returnsMessage() {
            JsonRpcException ex = new JsonRpcException(-32700, "Parse error");

            assertEquals("Parse error", ex.getMessage());
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTest {

        @Test
        @DisplayName("formats as JsonRpcException{code=..., message='...'}")
        void formatsCorrectly() {
            JsonRpcException ex = new JsonRpcException(-32603, "Internal error");

            assertEquals("JsonRpcException{code=-32603, message='Internal error'}", ex.toString());
        }
    }

    @Nested
    @DisplayName("inheritance")
    class InheritanceTest {

        @Test
        @DisplayName("extends Exception")
        void extendsException() {
            JsonRpcException ex = new JsonRpcException(-32000, "Server error");

            assertInstanceOf(Exception.class, ex);
        }
    }

    @Nested
    @DisplayName("data")
    class DataTest {

        @Test
        @DisplayName("keeps the agent message and data apart from the composed message")
        void keepsAgentMessageAndData() {
            JsonElement data = JsonParser.parseString("{\"errorName\":\"provider.rate-limit\",\"status\":429}");
            JsonRpcException ex = new JsonRpcException(-32603, "Rate limit reached", data);

            assertEquals("Rate limit reached", ex.getAgentMessage());
            assertSame(data, ex.getData());
            assertEquals("Rate limit reached: {\"errorName\":\"provider.rate-limit\",\"status\":429}", ex.getMessage());
        }

        @Test
        @DisplayName("details in object data replace the raw JSON in the message")
        void detailsReplaceRawJson() {
            JsonRpcException ex = new JsonRpcException(-32603, "Internal error",
                JsonParser.parseString("{\"details\":\"stack\"}"));

            assertEquals("Internal error: stack", ex.getMessage());
        }

        @Test
        @DisplayName("array data is appended as JSON")
        void arrayDataAppendedAsJson() {
            JsonRpcException ex = new JsonRpcException(-32603, "Internal error", JsonParser.parseString("[1,2]"));

            assertEquals("Internal error: [1,2]", ex.getMessage());
        }

        @Test
        @DisplayName("the two-argument constructor has no data")
        void twoArgumentConstructorHasNoData() {
            JsonRpcException ex = new JsonRpcException(-32603, "Internal error");

            assertNull(ex.getData());
            assertEquals("Internal error", ex.getAgentMessage());
        }
    }
}
