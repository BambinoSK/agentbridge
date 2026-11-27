package com.github.catatafishen.agentbridge.psi;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Reads and writes the project's {@link FollowAgentScope} under
 * {@link ToolLayerSettings#FOLLOW_AGENT_SCOPE_KEY}. Shared by both {@link ToolLayerSettings}
 * implementations so the agent plugin and the standalone MCP plugin read the same value.
 */
public final class FollowAgentScopeSetting {

    private static final Logger LOG = Logger.getInstance(FollowAgentScopeSetting.class);
    private static final FollowAgentScope DEFAULT = FollowAgentScope.READS_AND_EDITS;

    private FollowAgentScopeSetting() {
    }

    public static @NotNull FollowAgentScope read(@NotNull PropertiesComponent properties) {
        String stored = properties.getValue(ToolLayerSettings.FOLLOW_AGENT_SCOPE_KEY);
        FollowAgentScope scope = FollowAgentScope.fromStored(stored);
        if (scope != null) return scope;
        if (stored != null && !stored.isBlank()) {
            LOG.warn("Unknown Follow Agent scope '" + stored + "', using " + DEFAULT.name());
        }
        return DEFAULT;
    }

    public static void write(@NotNull PropertiesComponent properties, @NotNull FollowAgentScope scope) {
        properties.setValue(ToolLayerSettings.FOLLOW_AGENT_SCOPE_KEY, scope.name(), DEFAULT.name());
    }
}
