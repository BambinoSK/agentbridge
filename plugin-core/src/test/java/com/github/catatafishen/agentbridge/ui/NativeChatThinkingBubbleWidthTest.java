package com.github.catatafishen.agentbridge.ui;


import javax.swing.JComponent;
import javax.swing.text.View;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.List;

/**
 * Issue #1180 regression tests: text in the THINKING bubble (the chip-strip
 * path, not the normal message-row path) must lay out at the bubble width.
 *
 * <p>Builds a real {@link NativeChatPanel}, streams thinking text in chunks
 * exactly the way session/update agent_message_chunk notifications arrive,
 * lays the whole hierarchy out at a fixed width, and measures the laid-out
 * width of the thinking pane's content against the pane's own component
 * width. Code blocks are excluded from the measurement: they are clipped and
 * horizontally scrollable by design; everything else (prose, paragraphs) must
 * stay inside the pane.
 */
public class NativeChatThinkingBubbleWidthTest extends NativeMarkdownPaneTestBase {

    private static final int PANEL_W = 400;

    private static final String PROSE =
        "The runner collects the staged files first and then computes the diff "
            + "for each of them in turn. It skips anything outside the scope of the "
            + "change and records the rest. When a file was renamed the old and new "
            + "paths are matched before the diff is built, so the review panel can "
            + "show a single item instead of a delete plus an add. The same pass "
            + "also gathers the line counts shown under the header of each entry. "
            + "Anything that did not change textually is dropped here, which keeps "
            + "the review small enough to read in one screen. The final list is then "
            + "sorted so that the entries with the most changed lines come first, "
            + "because those tend to need the most attention during review.";

    /** Prose-only thinking text wraps at the pane width. */
    public void testThinkingBubbleProseLaysOutAtPaneWidth() {
        Result r = measureThinkingStream(PROSE);
        assertTrue("prose in the thinking bubble must stay inside the pane (pane w="
            + r.paneWidth + ", laid-out right edge=" + r.laidOutMaxRight + ")",
            r.laidOutMaxRight <= r.paneWidth + 2);
    }

    /**
     * The reported shape: prose plus a fenced code block with one long line in
     * the same thinking entry. Before the min-span fix the whole body, prose
     * included, laid out at the code width and every prose line clipped at the
     * bubble edge.
     */
    public void testThinkingBubbleWithCodeBlockKeepsProseInsidePane() {
        String text = PROSE + "\n\n```bash\n"
            + "git log --graph --pretty=format:'%C(auto)%h%d %s %C(white)(%cr by %an)' --all --date-order\n"
            + "```\n\n" + PROSE;
        Result r = measureThinkingStream(text);
        assertTrue("prose around a code block in the thinking bubble must stay inside the pane (pane w="
            + r.paneWidth + ", laid-out right edge=" + r.laidOutMaxRight + ")",
            r.laidOutMaxRight <= r.paneWidth + 2);
    }

    // ------------------------------------------------------------------
    // Machinery
    // ------------------------------------------------------------------

    private static final class Result {
        int paneWidth;
        int laidOutMaxRight;
    }

    /**
     * Streams the given thinking text into a real panel at a fixed width and
     * measures the thinking pane. Measurement happens before any message text
     * is appended: appendText finalizes the turn and collapses the thinking
     * bubble, exactly like production, and the issue reproduces while the
     * bubble is expanded during streaming.
     */
    private Result measureThinkingStream(String text) {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        JComponent root = ((ChatPanelApi) panel).getComponent();
        root.setSize(PANEL_W, 1400);

        for (String c : chunk(text, 37)) {
            panel.appendThinkingText(c);
        }
        List<NativeMarkdownPane> all = new ArrayList<>();
        collect(root, all);
        NativeMarkdownPane thinkingPane = null;
        for (NativeMarkdownPane p : all) {
            if (inChipStrip(p)) { thinkingPane = p; break; }
        }
        assertNotNull("thinking pane not found in chip strip", thinkingPane);
        thinkingPane.notifyStreamDone();

        // Headless layout: validate() alone does not cascade through the scroll
        // pane viewport, so lay the tree out explicitly until component sizes
        // stop changing. This mirrors the revalidate cycles the IDE runs, where
        // pane preferred sizes are computed from parent widths established by
        // earlier passes.
        layoutUntilStable(root);

        Result r = new Result();
        r.paneWidth = thinkingPane.getWidth();
        assertTrue("thinking pane must have been laid out with a positive width", r.paneWidth > 0);

        View rv = thinkingPane.getUI().getRootView(thinkingPane);
        rv.setSize((float) r.paneWidth, Short.MAX_VALUE);
        float h = rv.getPreferredSpan(View.Y_AXIS);
        int[] maxRight = {0};
        walk(rv, new Rectangle(0, 0, r.paneWidth, (int) h), maxRight);
        r.laidOutMaxRight = maxRight[0];
        return r;
    }

    private static List<String> chunk(String text, int size) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            out.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return out;
    }

    private static void collect(java.awt.Component c, List<NativeMarkdownPane> out) {
        if (c instanceof NativeMarkdownPane) {
            out.add((NativeMarkdownPane) c);
        }
        if (c instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) c).getComponents()) {
                collect(child, out);
            }
        }
    }

    private static boolean inChipStrip(java.awt.Component c) {
        java.awt.Container p = c.getParent();
        while (p != null) {
            if ("ChipStripPanel".equals(p.getClass().getSimpleName())) return true;
            p = p.getParent();
        }
        return false;
    }

    private static void layoutUntilStable(java.awt.Component c) {
        String prev = signature(c);
        for (int i = 0; i < 10; i++) {
            layoutTree(c);
            String now = signature(c);
            if (now.equals(prev)) break;
            prev = now;
        }
    }

    private static void layoutTree(java.awt.Component c) {
        if (c instanceof java.awt.Container) {
            java.awt.Container cont = (java.awt.Container) c;
            cont.doLayout();
            for (java.awt.Component child : cont.getComponents()) {
                layoutTree(child);
            }
        }
    }

    private static String signature(java.awt.Component c) {
        StringBuilder sb = new StringBuilder();
        List<java.awt.Component> all = new ArrayList<>();
        flat(c, all);
        for (java.awt.Component x : all) {
            sb.append(x.getSize().width).append('x').append(x.getSize().height).append(';');
        }
        return sb.toString();
    }

    private static void flat(java.awt.Component c, List<java.awt.Component> out) {
        out.add(c);
        if (c instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) c).getComponents()) {
                flat(child, out);
            }
        }
    }

    private static void walk(View parent, Shape alloc, int[] maxRight) {
        for (int i = 0; i < parent.getViewCount(); i++) {
            View child = parent.getView(i);
            String name = child.getElement() != null ? String.valueOf(child.getElement().getName()) : "?";
            if ("pre".equals(name)) {
                // Code blocks are clipped and horizontally scrollable by design;
                // their internal line views lay out at natural width. Do not
                // measure and do not descend.
                continue;
            }
            Shape s = parent.getChildAllocation(i, alloc);
            if (s == null) continue;
            Rectangle b = s.getBounds();
            int right = b.x + b.width;
            if (right > maxRight[0]) maxRight[0] = right;
            walk(child, s, maxRight);
        }
    }
}
