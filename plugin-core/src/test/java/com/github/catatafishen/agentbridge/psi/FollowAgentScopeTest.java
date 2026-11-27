package com.github.catatafishen.agentbridge.psi;

import com.github.catatafishen.agentbridge.psi.FollowAgentScope.Operation;
import com.github.catatafishen.agentbridge.services.ToolPermission;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FollowAgentScope} and the Follow Agent checks {@link ToolLayerSettings} builds on it.
 */
class FollowAgentScopeTest {

    @Nested
    @DisplayName("includes")
    class Includes {

        @Test
        @DisplayName("reads and edits follows both kinds of operation")
        void readsAndEdits() {
            assertTrue(FollowAgentScope.READS_AND_EDITS.includes(Operation.READ));
            assertTrue(FollowAgentScope.READS_AND_EDITS.includes(Operation.EDIT));
        }

        @Test
        @DisplayName("edits only leaves reads out")
        void editsOnly() {
            assertFalse(FollowAgentScope.EDITS.includes(Operation.READ));
            assertTrue(FollowAgentScope.EDITS.includes(Operation.EDIT));
        }

        @Test
        @DisplayName("reads only leaves edits out")
        void readsOnly() {
            assertTrue(FollowAgentScope.READS.includes(Operation.READ));
            assertFalse(FollowAgentScope.READS.includes(Operation.EDIT));
        }
    }

    @Nested
    @DisplayName("fromStored")
    class FromStored {

        @Test
        @DisplayName("a stored constant name reads back as that scope")
        void storedName() {
            for (FollowAgentScope scope : FollowAgentScope.values()) {
                assertEquals(scope, FollowAgentScope.fromStored(scope.name()));
            }
        }

        @Test
        @DisplayName("nothing stored gives no scope, so the caller picks the default")
        void nothingStored() {
            assertNull(FollowAgentScope.fromStored(null));
            assertNull(FollowAgentScope.fromStored(" "));
        }

        @Test
        @DisplayName("an unknown or display-name value is not taken for a scope")
        void unknownValue() {
            assertNull(FollowAgentScope.fromStored("EVERYTHING"));
            assertNull(FollowAgentScope.fromStored("Edits only"));
            assertNull(FollowAgentScope.fromStored("edits"));
        }
    }

    @Nested
    @DisplayName("Operation.ofAcpKind")
    class OfAcpKind {

        @ParameterizedTest
        @ValueSource(strings = {"edit", "delete", "move"})
        @DisplayName("kinds that change files are edits")
        void changingKindsAreEdits(String kind) {
            assertEquals(Operation.EDIT, Operation.ofAcpKind(kind));
        }

        @ParameterizedTest
        @ValueSource(strings = {"read", "search", "execute", "fetch", "think", "other", ""})
        @DisplayName("every other kind is a read")
        void otherKindsAreReads(String kind) {
            assertEquals(Operation.READ, Operation.ofAcpKind(kind));
        }

        @Test
        @DisplayName("a tool call without a kind is a read")
        void noKind() {
            assertEquals(Operation.READ, Operation.ofAcpKind(null));
        }
    }

    @Nested
    @DisplayName("ToolLayerSettings checks")
    class SettingsChecks {

        @Test
        @DisplayName("Follow Agent off follows no files, whatever the scope")
        void followAgentOff() {
            ToolLayerSettings settings = new FakeSettings(false, FollowAgentScope.READS_AND_EDITS, true);
            assertFalse(settings.followsFiles(Operation.READ));
            assertFalse(settings.followsFiles(Operation.EDIT));
        }

        @Test
        @DisplayName("Follow Agent on follows only the operations its scope includes")
        void followAgentOnScoped() {
            ToolLayerSettings settings = new FakeSettings(true, FollowAgentScope.EDITS, true);
            assertFalse(settings.followsFiles(Operation.READ));
            assertTrue(settings.followsFiles(Operation.EDIT));
        }

        @Test
        @DisplayName("tool windows need both Follow Agent and the tool-window option")
        void toolWindows() {
            assertTrue(new FakeSettings(true, FollowAgentScope.READS, true).followsToolWindows());
            assertFalse(new FakeSettings(true, FollowAgentScope.READS, false).followsToolWindows());
            assertFalse(new FakeSettings(false, FollowAgentScope.READS, true).followsToolWindows());
        }

        @Test
        @DisplayName("an implementation that does not override the new options keeps today's behaviour")
        void defaults() {
            ToolLayerSettings settings = new MinimalSettings();
            assertEquals(FollowAgentScope.READS_AND_EDITS, settings.getFollowAgentScope());
            assertTrue(settings.followsFiles(Operation.READ));
            assertTrue(settings.followsFiles(Operation.EDIT));
            assertTrue(settings.followsToolWindows());
        }
    }

    /** A {@link ToolLayerSettings} that only implements what it must, to test the interface's defaults. */
    private static class MinimalSettings implements ToolLayerSettings {

        @Override
        public boolean getFollowAgentFiles() {
            return true;
        }

        @Override
        public @Nullable String getActiveAgentLabel() {
            return null;
        }

        @Override
        public @Nullable String getSelectedModel() {
            return null;
        }

        @Override
        public @NotNull ToolPermission resolveEffectivePermission(@NotNull String toolId, boolean insideProject) {
            return ToolPermission.ALLOW;
        }

        @Override
        public @NotNull ToolPermission getToolPermission(@NotNull String toolId) {
            return ToolPermission.ALLOW;
        }
    }

    private static final class FakeSettings extends MinimalSettings {
        private final boolean followAgent;
        private final FollowAgentScope scope;
        private final boolean toolWindows;

        FakeSettings(boolean followAgent, FollowAgentScope scope, boolean toolWindows) {
            this.followAgent = followAgent;
            this.scope = scope;
            this.toolWindows = toolWindows;
        }

        @Override
        public boolean getFollowAgentFiles() {
            return followAgent;
        }

        @Override
        public @NotNull FollowAgentScope getFollowAgentScope() {
            return scope;
        }

        @Override
        public boolean getFollowAgentToolWindows() {
            return toolWindows;
        }
    }
}
