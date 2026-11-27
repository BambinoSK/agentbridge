package com.github.catatafishen.agentbridge.psi;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Which of the agent's file operations Follow Agent opens in the editor (and leaves open).
 * An operation outside the scope is treated as if Follow Agent were off: a file opened only
 * to collect analysis data is closed again afterwards.
 */
public enum FollowAgentScope {

    READS_AND_EDITS("Reads and edits", true, true),
    EDITS("Edits only", false, true),
    READS("Reads only", true, false);

    /** The kind of file operation a tool performs, as far as Follow Agent is concerned. */
    public enum Operation {
        READ,
        EDIT;

        /**
         * The operation of an agent's own tool call by its ACP tool kind ({@code read}, {@code edit},
         * {@code delete}, {@code move}, {@code search}...): the kinds that change files are edits,
         * every other kind (and none) only looks at the file it names.
         */
        public static @NotNull Operation ofAcpKind(@Nullable String kind) {
            return switch (kind == null ? "" : kind) {
                case "edit", "delete", "move" -> EDIT;
                default -> READ;
            };
        }
    }

    private final String displayName;
    private final boolean followsReads;
    private final boolean followsEdits;

    FollowAgentScope(String displayName, boolean followsReads, boolean followsEdits) {
        this.displayName = displayName;
        this.followsReads = followsReads;
        this.followsEdits = followsEdits;
    }

    public @NotNull String displayName() {
        return displayName;
    }

    public boolean includes(@NotNull Operation operation) {
        return operation == Operation.READ ? followsReads : followsEdits;
    }

    /**
     * The scope stored under {@code value} (the constant's name), or {@code null} when nothing
     * valid is stored, so the caller decides the default and can tell a broken value apart.
     */
    public static @Nullable FollowAgentScope fromStored(@Nullable String value) {
        if (value == null || value.isBlank()) return null;
        for (FollowAgentScope scope : values()) {
            if (scope.name().equals(value)) return scope;
        }
        return null;
    }
}
