package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.client.acp.transport.JsonRpcTransport;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Test-only ACP client that launches the harness fake agent
 * (fake_acp_agent.py) as a REAL subprocess, so every fix branch is verified
 * through the exact production path: ProcessBuilder launch, PATH resolution,
 * JsonRpcTransport over real pipes, initialize/authenticate/session flows,
 * streaming session/update notifications and config-option round trips.
 *
 * <p>The fake agent replays a scenario JSON (see
 * src/test/resources/harness/scenarios/) that mirrors wire behavior observed
 * in the field. Scenario payloads are sanitized: no real user names, machine
 * paths, or client identifiers.
 */
public class HarnessClient extends AcpClient {

    private final List<String> harnessCommand;

    /**
     * @param agentScript  absolute path to fake_acp_agent.py (copied to a temp dir by the test)
     * @param scenarioFile absolute path to the scenario JSON
     * @param logFile      where the fake agent records what the client sent
     */
    public HarnessClient(Project project, java.nio.file.Path agentScript,
                         java.nio.file.Path scenarioFile, java.nio.file.Path logFile) {
        super(project, new JsonRpcTransport());
        String python = System.getProperty("agentbridge.harness.python", "python");
        this.harnessCommand = List.of(
            python,
            agentScript.toAbsolutePath().toString(),
            scenarioFile.toAbsolutePath().toString(),
            logFile.toAbsolutePath().toString()
        );
    }

    @Override
    public @NotNull String agentId() {
        return "harness";
    }

    @Override
    public @NotNull String displayName() {
        return "Harness Fake Agent";
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    protected boolean isMcpToolTitle(@NotNull String protocolTitle) {
        return protocolTitle.contains("-");
    }

    @Override
    protected boolean isAgentBridgeMcpToolTitle(@NotNull String protocolTitle) {
        return protocolTitle.startsWith("agentbridge-");
    }

    @Override
    protected boolean shouldSynthesizeApprovedToolCompletion() {
        return true;
    }

    @Override
    protected @NotNull List<String> buildCommand(String cwd, int mcpPort) {
        return harnessCommand;
    }

    @Override
    protected void beforeLaunch(String cwd, int mcpPort) throws IOException {
        // The IntelliJ test fixture reuses one shared temp project dir across
        // the test methods of a class and deletes it after each method's
        // teardown. Recreate it so ProcessBuilder's cwd is always valid.
        java.nio.file.Files.createDirectories(java.nio.file.Path.of(cwd));
    }

    @Override
    String loadResumeSessionId() {
        return null;
    }

    @Override
    protected int resolveMcpPort() {
        return 0;
    }

    @Override
    protected void onSessionCreated(String sessionId) {
        // no-op for tests
    }

    @Override
    protected void beforeCreateSession(String cwd) {
        // no-op for tests
    }

    @Override
    protected void tryBranchSessionAtStartup() {
        // no-op for tests
    }

    @Override
    protected @NotNull Executor permissionRequestExecutor() {
        return Runnable::run;
    }
}
