package com.github.catatafishen.agentbridge.ui;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import com.intellij.ide.util.PropertiesComponent;

import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

/**
 * The user can turn the thinking (reasoning) bubble display off.
 *
 * <p>The Thinking…/Thought chips ALWAYS stay in the chat so the user keeps seeing
 * that (and what) the agent thought; the toggle only controls whether the thought
 * bubble auto-expands while streaming, and collapses live-expanded bubbles when
 * turned off mid-stream. Thinking chunks are still recorded by the entry store,
 * and the chip remains clickable to peek at the full text.
 */
public class NativeChatThinkingDisplayToggleTest extends NativeMarkdownPaneTestBase {

    /**
     * The light fixture reuses one project across test methods, so a preference written by
     * one test would otherwise leak into the next and make results depend on run order.
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        PropertiesComponent.getInstance(getProject()).unsetValue(NativeChatPanel.PREF_SHOW_THINKING);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            PropertiesComponent.getInstance(getProject()).unsetValue(NativeChatPanel.PREF_SHOW_THINKING);
        } finally {
            super.tearDown();
        }
    }

    /** With the toggle off before streaming, the chip mounts but the bubble stays hidden. */
    public void testChipMountsButBubbleHiddenWhenDisabledBeforeStream() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        JComponent root = ((ChatPanelApi) panel).getComponent();
        root.setSize(400, 800);

        panel.setThinkingDisplayEnabled(false);
        for (String c : chunk("The runner stages the files and computes the diff for each one.", 23)) {
            panel.appendThinkingText(c);
        }

        assertEquals("the thinking chip must still mount with the display off",
            1, countThinkingChips(root));
        ChipStripPanel strip = findStripWithThinkingChip(root);
        assertNotNull(strip);
        assertFalse("the thinking bubble must stay hidden with the display off",
            strip.isThinkingBubbleVisible());

        // Agent text is unaffected by the toggle.
        panel.appendText("Here is the answer.");
        assertTrue("agent text must still render with thinking off",
            countNonStripPanes(root) > 0);

        // The choice persists and a fresh panel picks it up from the project prefs.
        assertFalse("preference must be persisted as false",
            PropertiesComponent.getInstance(getProject())
                .getBoolean(NativeChatPanel.PREF_SHOW_THINKING, true));
        NativeChatPanel reloaded = new NativeChatPanel(getProject());
        assertFalse("a new panel must start with thinking display off",
            reloaded.getThinkingDisplayEnabled());

        // Cleanup so later assertions in the same fixture are not polluted.
        PropertiesComponent.getInstance(getProject())
            .unsetValue(NativeChatPanel.PREF_SHOW_THINKING);
    }

    /**
     * Toggling off mid-stream collapses the visible bubble but keeps the chip; toggling
     * back on re-expands it because the block is still streaming. Once the block is
     * finished (collapsed), re-enabling must NOT re-expand it.
     */
    public void testToggleOffAndOnAroundStreamingBubble() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        JComponent root = ((ChatPanelApi) panel).getComponent();
        root.setSize(400, 800);

        for (String c : chunk("Deciding which files to review first and why.", 17)) {
            panel.appendThinkingText(c);
        }
        ChipStripPanel strip = findStripWithThinkingChip(root);
        assertNotNull("streaming thinking must mount a chip strip", strip);
        assertTrue("bubble must be visible while streaming with the display on",
            strip.isThinkingBubbleVisible());

        panel.setThinkingDisplayEnabled(false);
        assertFalse("toggling off must collapse the streaming bubble",
            strip.isThinkingBubbleVisible());
        assertEquals("the thinking chip must stay mounted after toggling off",
            1, countThinkingChips(root));

        panel.setThinkingDisplayEnabled(true);
        assertTrue("re-enabling mid-stream re-expands the still-streaming bubble",
            strip.isThinkingBubbleVisible());

        panel.collapseThinking();
        assertFalse("end of the thinking block collapses the bubble", strip.isThinkingBubbleVisible());

        panel.setThinkingDisplayEnabled(false);
        panel.setThinkingDisplayEnabled(true);
        assertFalse("a finished thinking block must stay collapsed after the toggle cycle",
            strip.isThinkingBubbleVisible());
    }

    /** Chunks streamed while off still build the chip and text; enabling shows the bubble. */
    public void testEnablingMidStreamExpandsStreamingBubble() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        JComponent root = ((ChatPanelApi) panel).getComponent();
        root.setSize(400, 800);

        panel.setThinkingDisplayEnabled(false);
        panel.appendThinkingText("hidden deliberation");
        assertEquals("the chip must mount even while the display is off",
            1, countThinkingChips(root));
        ChipStripPanel strip = findStripWithThinkingChip(root);
        assertFalse("the bubble must stay hidden while the display is off",
            strip.isThinkingBubbleVisible());

        panel.setThinkingDisplayEnabled(true);
        panel.appendThinkingText(" more deliberation");
        assertTrue("enabling mid-stream must expand the streaming bubble",
            strip.isThinkingBubbleVisible());
    }

    /**
     * History replay always mounts collapsed Thought chips; the toggle does not remove
     * them, and replayed bubbles stay collapsed in both states.
     */
    public void testHistoryReplayKeepsChipsInBothStates() {
        List<EntryData> entries = new ArrayList<>();
        entries.add(new EntryData.Prompt("hello"));
        entries.add(new EntryData.Thinking("pondering"));
        entries.add(new EntryData.Text("answer"));

        NativeChatPanel off = new NativeChatPanel(getProject());
        off.setThinkingDisplayEnabled(false);
        ((ChatPanelApi) off).getComponent().setSize(400, 800);
        off.appendEntries(entries, -1);
        JComponent offRoot = ((ChatPanelApi) off).getComponent();
        assertEquals("replayed thinking must still mount a chip with the display off",
            1, countThinkingChips(offRoot));
        assertFalse("replayed thinking must stay collapsed with the display off",
            findStripWithThinkingChip(offRoot).isThinkingBubbleVisible());
        assertTrue("replayed text must still render while the display is off",
            countNonStripPanes(offRoot) > 0);

        // The off panel persisted its preference; clear it so the on panel starts enabled.
        PropertiesComponent.getInstance(getProject())
            .unsetValue(NativeChatPanel.PREF_SHOW_THINKING);

        NativeChatPanel on = new NativeChatPanel(getProject());
        ((ChatPanelApi) on).getComponent().setSize(400, 800);
        on.appendEntries(entries, -1);
        JComponent onRoot = ((ChatPanelApi) on).getComponent();
        assertEquals("replayed thinking must render with the display on",
            1, countThinkingChips(onRoot));
        assertFalse("replayed thinking must stay collapsed with the display on too",
            findStripWithThinkingChip(onRoot).isThinkingBubbleVisible());
    }

    /**
     * With the display off the chip is still the way to read the thought: clicking it
     * opens the bubble, and the bubble already holds the streamed text.
     */
    public void testChipStillOpensBubbleWhileDisplayIsOff() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        JComponent root = ((ChatPanelApi) panel).getComponent();
        root.setSize(400, 800);

        panel.setThinkingDisplayEnabled(false);
        panel.appendThinkingText("peekable deliberation");
        panel.collapseThinking();

        ChipStripPanel strip = findStripWithThinkingChip(root);
        assertNotNull(strip);
        assertFalse("bubble starts hidden with the display off", strip.isThinkingBubbleVisible());

        strip.toggleThinkingBubble();
        assertTrue("clicking the chip must open the bubble even with the display off",
            strip.isThinkingBubbleVisible());

        List<NativeMarkdownPane> panes = new ArrayList<>();
        collectPanes(root, panes);
        boolean found = false;
        for (NativeMarkdownPane p : panes) {
            if (inChipStrip(p) && p.getRawText().contains("peekable deliberation")) found = true;
        }
        assertTrue("the hidden bubble must still hold the streamed text", found);

        strip.toggleThinkingBubble();
        assertFalse("clicking again closes it", strip.isThinkingBubbleVisible());
    }

    // ------------------------------------------------------------------
    // Machinery
    // ------------------------------------------------------------------

    private static List<String> chunk(String text, int size) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            out.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return out;
    }

    private static int countThinkingChips(Component c) {
        int n = (c instanceof ThinkingChipComponent) ? 1 : 0;
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                n += countThinkingChips(child);
            }
        }
        return n;
    }

    private static void collectPanes(Component c, List<NativeMarkdownPane> out) {
        if (c instanceof NativeMarkdownPane) out.add((NativeMarkdownPane) c);
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                collectPanes(child, out);
            }
        }
    }

    private static int countNonStripPanes(Component c) {
        int n = (c instanceof NativeMarkdownPane && !inChipStrip(c)) ? 1 : 0;
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                n += countNonStripPanes(child);
            }
        }
        return n;
    }

    private static boolean inChipStrip(Component c) {
        Container p = c.getParent();
        while (p != null) {
            if (p instanceof ChipStripPanel) return true;
            p = p.getParent();
        }
        return false;
    }

    private static ChipStripPanel findStripWithThinkingChip(Component c) {
        if (c instanceof ChipStripPanel && hasThinkingChip((ChipStripPanel) c)) {
            return (ChipStripPanel) c;
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                ChipStripPanel found = findStripWithThinkingChip(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean hasThinkingChip(ChipStripPanel strip) {
        for (Component child : strip.getComponents()) {
            if (countThinkingChips(child) > 0) return true;
        }
        return false;
    }
}
