package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.acp.protocol.InitializeResponse;
import com.github.catatafishen.agentbridge.client.acp.transport.JsonRpcTransport;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/** Whether OpenCode's main stream carries a sub-agent's updates depends on its version. */
class OpenCodeSubAgentStreamTest {

    @ParameterizedTest(name = "OpenCode {0}: sub-agent updates separate = {1}")
    @CsvSource({"2.4.1, true", "2.0.0, true", "1.19.0, false"})
    void subAgentUpdatesAreSeparateFromVersion2(String version, boolean separate) throws Exception {
        OpenCodeClient client = new OpenCodeClient(mock(Project.class), mock(JsonRpcTransport.class));
        Field capabilities = AcpClient.class.getDeclaredField("capabilities");
        capabilities.setAccessible(true);
        capabilities.set(client, new InitializeResponse(1,
            new InitializeResponse.AgentInfo("OpenCode", null, version), null, null));

        assertEquals(separate, client.streamsSubAgentUpdatesSeparately());
    }
}
