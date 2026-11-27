package com.github.catatafishen.agentbridge.psi;

import com.github.catatafishen.agentbridge.services.ToolPermission;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Read-only settings contract for the MCP tool layer.
 * Tool handlers use this interface instead of referencing any agent-specific
 * settings class, keeping the tool layer agent-agnostic.
 *
 * <p>Each agent plugin registers its own implementation as a project service
 * (e.g., {@code ActiveAgentToolLayerSettings}). The standalone MCP plugin registers
 * {@link DefaultToolLayerSettings}.</p>
 */
public interface ToolLayerSettings {

    /**
     * PropertiesComponent key for the "follow agent files" per-project setting.
     * Shared across implementations so the setting is consistent regardless of
     * which agent plugin is active.
     */
    String FOLLOW_AGENT_FILES_KEY = "agent.followAgentFiles";

    String ALLOW_TRANSIENT_FILE_OPENS_KEY = "agent.allowTransientFileOpens";

    String RAISE_RUN_WINDOW_KEY = "agent.raiseRunWindow";

    /** Stores the {@link FollowAgentScope} constant's name. */
    String FOLLOW_AGENT_SCOPE_KEY = "agent.followAgentScope";

    String FOLLOW_AGENT_TOOL_WINDOWS_KEY = "agent.followAgentToolWindows";

    @NotNull
    static ToolLayerSettings getInstance(@NotNull Project project) {
        ToolLayerSettings service = (ToolLayerSettings) PlatformApiCompat.getServiceByRawClass(project, ToolLayerSettings.class);
        if (service == null) {
            throw new IllegalStateException("ToolLayerSettings service not registered for project: " + project.getName());
        }
        return service;
    }

    /**
     * Whether to auto-navigate to files as the agent reads or edits them.
     */
    boolean getFollowAgentFiles();

    /**
     * Which file operations Follow Agent opens in the editor. Only has effect when
     * {@link #getFollowAgentFiles()} returns {@code true}. Defaults to reads and edits.
     */
    default @NotNull FollowAgentScope getFollowAgentScope() {
        return FollowAgentScope.READS_AND_EDITS;
    }

    /**
     * Whether Follow Agent also brings up the tool windows that show a tool's result (build output,
     * the git log at a new commit, the commit view, the database view, chat history search). Only
     * has effect when {@link #getFollowAgentFiles()} returns {@code true}. Defaults to {@code true}.
     */
    default boolean getFollowAgentToolWindows() {
        return true;
    }

    /**
     * Whether Follow Agent opens, and leaves open, the files of {@code operation}: Follow Agent is
     * on and its scope includes the operation. Callers that keep or close a file use this instead of
     * {@link #getFollowAgentFiles()}.
     */
    default boolean followsFiles(@NotNull FollowAgentScope.Operation operation) {
        return getFollowAgentFiles() && getFollowAgentScope().includes(operation);
    }

    /** Whether Follow Agent brings up result tool windows: it is on and the tool-window option is too. */
    default boolean followsToolWindows() {
        return getFollowAgentFiles() && getFollowAgentToolWindows();
    }

    /**
     * Whether tools are allowed to temporarily open files in the editor to collect
     * live metadata (daemon highlights, intention actions) when Follow Agent Files
     * is disabled. Defaults to {@code true}. Only has effect when
     * {@link #getFollowAgentFiles()} returns {@code false}.
     */
    default boolean getAllowTransientFileOpens() {
        return true;
    }

    /**
     * Whether the Run tool window may be brought forward (activated and focused) when a
     * command the agent started is shown in a Run tab. When {@code false} the tab is still
     * added, but the window is never raised. Defaults to {@code true}.
     */
    default boolean getRaiseRunWindow() {
        return true;
    }

    /**
     * Display label for the currently active agent or sub-agent (e.g., "ui-reviewer").
     * Returns {@code null} when the main agent is active (no sub-agent running).
     */
    @Nullable
    String getActiveAgentLabel();

    /**
     * Currently selected model name (e.g., "claude-sonnet-4.5").
     * Returns {@code null} if no model is selected.
     */
    @Nullable
    String getSelectedModel();

    /**
     * Resolve the effective permission for a tool, considering path-based sub-permissions.
     */
    @NotNull
    ToolPermission resolveEffectivePermission(@NotNull String toolId, boolean insideProject);

    /**
     * Get the base permission level for a tool (ignoring path context).
     */
    @NotNull
    ToolPermission getToolPermission(@NotNull String toolId);
}
