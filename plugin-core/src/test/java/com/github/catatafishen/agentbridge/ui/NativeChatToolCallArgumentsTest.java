package com.github.catatafishen.agentbridge.ui;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Issue: the tool-call popup shows "Inputs 0 chars" for live OpenCode sessions.
 *
 * <p>OpenCode emits session/tool_call with an empty rawInput and only reveals the
 * input in a later session/tool_call_update. The conversation entry store already
 * captures those late arguments (issue #1008, ConversationEntryStoreTest), but
 * NativeChatPanel dropped them: the popup reads toolCallData[id].arguments, which
 * stayed null for the rest of the live session. History replay was unaffected
 * because it re-adds chips from the stored entries.
 *
 * <p>These tests build a real panel and drive the same API the orchestrator uses:
 * addToolCallEntry with null arguments, then updateToolCall carrying the input.
 */
public class NativeChatToolCallArgumentsTest extends BasePlatformTestCase {

    private static final String CODE_ARGS = "{\"code\":\"return 1 + 1;\"}";
    private static final String TITLE_ARGS = "{\"title\":\"Fetch build docs\",\"url\":\"https://example.test/docs\"}";

    @Override
    protected void tearDown() throws Exception {
        try {
            // Same Windows teardown race as MarkdownStreamingPaneTest: a project service
            // may hold the conversation DB open; close it BEFORE the framework deletes
            // the temp project dir ("being used by another process").
            try {
                com.github.catatafishen.agentbridge.session.db.ConversationDatabase
                    .getInstance(getProject()).dispose();
            } catch (Exception ignored) {
            }
            Thread.sleep(100);
        } finally {
            super.tearDown();
        }
    }

    /** Late-arriving arguments must land in toolCallData so the popup shows them. */
    public void testLateArgumentsReachToolCallData() throws Exception {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panel.addToolCallEntry("tc-late", "execute", null, "other", false);
        assertNull("no arguments before the update", argumentsOf(panel, "tc-late"));

        panel.updateToolCall("tc-late", "complete",
            new ChatPanelApi.ToolCallUpdate("done", null, null, false, null, CODE_ARGS, null));

        assertEquals("late arguments must be applied to the live panel state",
            CODE_ARGS, argumentsOf(panel, "tc-late"));
    }

    /** A later blank update must not wipe arguments captured earlier. */
    public void testBlankUpdateKeepsExistingArguments() throws Exception {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panel.addToolCallEntry("tc-keep", "execute", CODE_ARGS, "other", false);

        panel.updateToolCall("tc-keep", "complete",
            new ChatPanelApi.ToolCallUpdate(null, null, null, false, null, null, null));

        assertEquals("arguments must survive an update without them",
            CODE_ARGS, argumentsOf(panel, "tc-keep"));
    }

    /** A blank late argument must not overwrite a real one (store keeps, UI keeps too). */
    public void testBlankLateArgumentsDoNotOverwrite() throws Exception {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panel.addToolCallEntry("tc-blank", "execute", null, "other", false);

        panel.updateToolCall("tc-blank", "running",
            new ChatPanelApi.ToolCallUpdate(null, null, null, false, null, "  ", null));
        assertNull("blank arguments must be ignored", argumentsOf(panel, "tc-blank"));

        panel.updateToolCall("tc-blank", "complete",
            new ChatPanelApi.ToolCallUpdate("done", null, null, false, null, CODE_ARGS, null));
        assertEquals("a real argument after a blank one must still apply",
            CODE_ARGS, argumentsOf(panel, "tc-blank"));
    }

    /** Arguments carrying a "title" must re-label the chip that was created without them. */
    public void testLateTitleArgumentsRelabelChip() throws Exception {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panel.addToolCallEntry("tc-title", "webfetch", null, "other", false);
        assertEquals("chip falls back to the tool name while arguments are unknown",
            "webfetch", chipText(panel, "tc-title"));

        panel.updateToolCall("tc-title", "complete",
            new ChatPanelApi.ToolCallUpdate("done", null, null, false, null, TITLE_ARGS, null));

        assertEquals("chip label must be re-derived once the arguments arrive",
            "Fetch build docs", chipText(panel, "tc-title"));
        assertEquals("popup title must match the re-derived chip label",
            "Fetch build docs", titleOf(panel, "tc-title"));
    }

    /** Arguments without a usable label key must leave the chip label alone. */
    public void testLateArgumentsWithoutTitleKeepChipLabel() throws Exception {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panel.addToolCallEntry("tc-notitle", "execute", null, "other", false);

        panel.updateToolCall("tc-notitle", "complete",
            new ChatPanelApi.ToolCallUpdate("done", null, null, false, null, CODE_ARGS, null));

        assertEquals("no title key in the arguments means the tool name stays",
            "execute", chipText(panel, "tc-notitle"));
        assertEquals("arguments still captured for the popup",
            CODE_ARGS, argumentsOf(panel, "tc-notitle"));
    }

    /** Arguments present at creation must not be re-processed into a new chip label. */
    public void testInitialArgumentsAreNotReprocessed() throws Exception {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panel.addToolCallEntry("tc-init", "webfetch", TITLE_ARGS, "other", false);
        assertEquals("chip labeled from the initial arguments",
            "Fetch build docs", chipText(panel, "tc-init"));

        panel.updateToolCall("tc-init", "complete",
            new ChatPanelApi.ToolCallUpdate("done", null, null, false, null, TITLE_ARGS, null));

        assertEquals("chip label must stay stable across updates",
            "Fetch build docs", chipText(panel, "tc-init"));
    }

    // ------------------------------------------------------------------
    // Machinery: reflection into the panel's private per-chip state
    // ------------------------------------------------------------------

    private static Object toolCallData(NativeChatPanel panel, String id) throws Exception {
        Field field = NativeChatPanel.class.getDeclaredField("toolCallData");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> map = (java.util.Map<String, Object>) field.get(panel);
        assertNotNull("no tool call data for id " + id, map.get(id));
        return map.get(id);
    }

    private static Object property(Object data, String name) throws Exception {
        Field field = data.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(data);
    }

    private static String argumentsOf(NativeChatPanel panel, String id) throws Exception {
        Object value = property(toolCallData(panel, id), "arguments");
        return (String) value;
    }

    private static String titleOf(NativeChatPanel panel, String id) throws Exception {
        Object value = property(toolCallData(panel, id), "title");
        return (String) value;
    }

    /** Text label of the chip rendered for the given tool call id. */
    private static String chipText(NativeChatPanel panel, String id) {
        ToolChipComponent chip = findChip(((ChatPanelApi) panel).getComponent(), id);
        assertNotNull("chip not found for id " + id, chip);
        for (JLabel label : collectLabels(chip)) {
            if (label.getText() != null && !label.getText().isEmpty()) return label.getText();
        }
        fail("chip has no text label");
        return null;
    }

    private static ToolChipComponent findChip(Component root, String id) {
        List<ToolChipComponent> chips = new ArrayList<>();
        collectChips(root, chips);
        // Every test here creates exactly one tool call, so one chip must exist.
        assertEquals("expected exactly one chip in the panel (tool call id " + id + ")",
            1, chips.size());
        return chips.get(0);
    }

    private static void collectChips(Component c, List<ToolChipComponent> out) {
        if (c instanceof ToolChipComponent) out.add((ToolChipComponent) c);
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) collectChips(child, out);
        }
    }

    private static List<JLabel> collectLabels(Component c) {
        List<JLabel> out = new ArrayList<>();
        collectLabels(c, out);
        return out;
    }

    private static void collectLabels(Component c, List<JLabel> out) {
        if (c instanceof JLabel) out.add((JLabel) c);
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) collectLabels(child, out);
        }
    }
}
