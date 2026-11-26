package com.github.catatafishen.agentbridge.client.acp.transport;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Exception wrapping a JSON-RPC error response. {@link #getMessage()} is the agent's message followed by its
 * {@code data}; {@link #getAgentMessage()} and {@link #getData()} keep them apart for agents whose {@code data}
 * has a known shape.
 */
public class JsonRpcException extends Exception {

    private final int code;
    private final @NotNull String agentMessage;
    private final transient @Nullable JsonElement data;

    public JsonRpcException(int code, @NotNull String message) {
        this(code, message, null);
    }

    public JsonRpcException(int code, @NotNull String agentMessage, @Nullable JsonElement data) {
        super(compose(agentMessage, data));
        this.code = code;
        this.agentMessage = agentMessage;
        this.data = data;
    }

    private static String compose(String agentMessage, @Nullable JsonElement data) {
        if (data == null || data.isJsonNull()) return agentMessage;
        String detail;
        if (data.isJsonObject()) {
            JsonObject dataObj = data.getAsJsonObject();
            detail = dataObj.has("details") ? dataObj.get("details").getAsString() : data.toString();
        } else {
            detail = data.isJsonPrimitive() ? data.getAsString() : data.toString();
        }
        return detail.isBlank() ? agentMessage : agentMessage + ": " + detail;
    }

    public int getCode() {
        return code;
    }

    public @NotNull String getAgentMessage() {
        return agentMessage;
    }

    /**
     * The error's {@code data} member. ACP leaves its shape to each agent and error code.
     */
    public @Nullable JsonElement getData() {
        return data;
    }

    @Override
    public String toString() {
        return "JsonRpcException{code=" + code + ", message='" + getMessage() + "'}";
    }
}
