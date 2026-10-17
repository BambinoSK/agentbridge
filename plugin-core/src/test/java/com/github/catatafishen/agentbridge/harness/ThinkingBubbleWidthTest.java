package com.github.catatafishen.agentbridge.harness;

import com.github.catatafishen.agentbridge.ui.ChatPanelApi;
import com.github.catatafishen.agentbridge.ui.NativeChatPanel;
import com.github.catatafishen.agentbridge.ui.NativeMarkdownPane;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.JComponent;
import javax.swing.text.View;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.List;

/**
 * Issue #1180 verification harness: does the THINKING bubble (chip-strip path,
 * not the normal row path) lay its text out at the panel width, or wider?
 *
 * <p>Builds a real {@link NativeChatPanel}, streams prose-only thinking text in
 * chunks exactly the way session/update agent_message_chunk notifications
 * arrive, collapses the thinking stream, lays the whole hierarchy out at a fixed
 * width, and measures the actual laid-out width of every markdown pane in the
 * panel against that pane's own component width. A message bubble with the same
 * text is the control: the issue report says prose renders correctly there.
 */
public class ThinkingBubbleWidthTest extends BasePlatformTestCase {

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

    @Override
    protected void tearDown() throws Exception {
        try {
            com.github.catatafishen.agentbridge.session.db.ConversationDatabase
                .getInstance(getProject()).dispose();
            Thread.sleep(100);
        } finally {
            super.tearDown();
        }
    }

    public void testThinkingAndMessageBubblesLayOutAtPanelWidth() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        JComponent root = ((ChatPanelApi) panel).getComponent();
        root.setSize(PANEL_W, 1400);

        // Thinking stream: chunks, the way the wire delivers them. The #1180
        // repro is the bubble EXPANDED while streaming, so measure BEFORE any
        // message text arrives (appendText finalizes the turn and collapses
        // the thinking bubble, exactly like production).
        for (String c : chunk(PROSE, 37)) {
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
        layoutUntilStable(root);
        int thinkingW = thinkingPane.getWidth();
        int thinkingLaidOut = laidOutMaxRight(thinkingPane, thinkingW);

        // Message bubble with identical text, as the control from the report.
        for (String c : chunk(PROSE, 37)) {
            panel.appendText(c);
        }
        layoutUntilStable(root);

        List<NativeMarkdownPane> panes = new ArrayList<>();
        collect(root, panes);
        StringBuilder report = new StringBuilder();
        report.append("\n=== #1180 verification: panel width ").append(PANEL_W).append(" ===\n");
        for (NativeMarkdownPane pane : panes) {
            int w = pane.getWidth();
            String path = ancestorSummary(pane);
            int maxRight = laidOutMaxRight(pane, w);
            report.append(String.format("  pane[w=%d] under %s: laid-out maxRight=%d (delta %d)%n",
                w, path, maxRight, maxRight - w));
        }
        report.append(String.format("  THINKING (expanded, pre-message): pane w=%d, laid-out=%d, delta=%d%n",
            thinkingW, thinkingLaidOut, thinkingLaidOut - thinkingW));
        System.out.println(report);
        fail("diagnostic run - see report:\n" + report);
    }

    /** Lays the tree out repeatedly until component sizes stop changing (max 10 passes). */
    private static void layoutUntilStable(java.awt.Component c) {
        String prev = signature(c);
        for (int i = 0; i < 10; i++) {
            layoutTree(c);
            String now = signature(c);
            if (now.equals(prev)) break;
            prev = now;
        }
    }

    private static String signature(java.awt.Component c) {
        StringBuilder sb = new StringBuilder();
        collect(c, new ArrayList<NativeMarkdownPane>());
        java.util.List<java.awt.Component> all = new ArrayList<>();
        flat(c, all);
        for (java.awt.Component x : all) {
            sb.append(x.getSize().width).append('x').append(x.getSize().height).append(';');
        }
        return sb.toString();
    }

    private static void flat(java.awt.Component c, java.util.List<java.awt.Component> out) {
        out.add(c);
        if (c instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) c).getComponents()) {
                flat(child, out);
            }
        }
    }

    /** Max right edge of the laid-out views, ignoring pre blocks. */
    private static int laidOutMaxRight(NativeMarkdownPane pane, int w) {
        View rv = pane.getUI().getRootView(pane);
        rv.setSize((float) w, Short.MAX_VALUE);
        float h = rv.getPreferredSpan(View.Y_AXIS);
        int[] maxRight = {0};
        walk(rv, new Rectangle(0, 0, w, (int) h), maxRight);
        return maxRight[0];
    }

    /**
     * The #1180 symptom as a whole: prose that wraps fine on its own, PLUS a
     * fenced code block with one long line, in the THINKING bubble. Before the
     * min-span fix the whole body (prose included) laid out at the code width
     * and every prose line clipped at the bubble edge.
     */
    public void testThinkingWithCodeBlockKeepsProseInsidePane() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        JComponent root = ((ChatPanelApi) panel).getComponent();
        root.setSize(PANEL_W, 1400);

        String text = PROSE + "\n\n```bash\n"
            + "git log --graph --pretty=format:'%C(auto)%h%d %s %C(white)(%cr by %an)' --all --date-order\n"
            + "```\n\n" + PROSE;
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
        layoutUntilStable(root);
        int w = thinkingPane.getWidth();
        View rv = thinkingPane.getUI().getRootView(thinkingPane);
        rv.setSize((float) w, Short.MAX_VALUE);
        // DIAGNOSTIC: dump the top two levels of the view tree with spans.
        StringBuilder dbg = new StringBuilder("[view-tree] pane w=" + w + "\n");
        for (int i = 0; i < rv.getViewCount(); i++) {
            View c1 = rv.getView(i);
            Shape s1 = rv.getChildAllocation(i, new Rectangle(0, 0, w, Short.MAX_VALUE));
            Rectangle b1 = s1 != null ? s1.getBounds() : new Rectangle();
            dbg.append(String.format("  L1 [%s] %s bounds=%s min=%.0f pref=%.0f%n",
                c1.getElement().getName(), c1.getClass().getSimpleName(), b1,
                c1.getMinimumSpan(View.X_AXIS), c1.getPreferredSpan(View.X_AXIS)));
            for (int j = 0; j < c1.getViewCount(); j++) {
                View c2 = c1.getView(j);
                Shape s2 = c1.getChildAllocation(j, s1 != null ? s1 : new Rectangle());
                Rectangle b2 = s2 != null ? s2.getBounds() : new Rectangle();
                if (b2.x + b2.width > w || "pre".equals(String.valueOf(c2.getElement().getName()))) {
                    dbg.append(String.format("    L2 [%s] %s bounds=%s min=%.0f pref=%.0f%n",
                        c2.getElement().getName(), c2.getClass().getSimpleName(), b2,
                        c2.getMinimumSpan(View.X_AXIS), c2.getPreferredSpan(View.X_AXIS)));
                }
            }
        }
        System.out.println(dbg);
        int maxRight = laidOutMaxRight(thinkingPane, w);
        System.out.println("[codeblock-thinking] pane w=" + w + " prose laid-out maxRight=" + maxRight
            + " (delta " + (maxRight - w) + ")");
        fail("diagnostic run - see report:\n" + dbg);
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

    /** Short ancestor chain so the report shows which container each pane lives in. */
    private static String ancestorSummary(java.awt.Component c) {
        List<String> names = new ArrayList<>();
        java.awt.Container p = c.getParent();
        int depth = 0;
        while (p != null && depth < 4) {
            names.add(p.getClass().getSimpleName());
            p = p.getParent();
            depth++;
        }
        return String.join(" < ", names);
    }

    private static boolean inChipStrip(java.awt.Component c) {
        java.awt.Container p = c.getParent();
        while (p != null) {
            if (p.getClass().getSimpleName().equals("ChipStripPanel")) return true;
            p = p.getParent();
        }
        return false;
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

    private static void layoutTree(java.awt.Component c) {
        if (c instanceof java.awt.Container) {
            java.awt.Container cont = (java.awt.Container) c;
            cont.doLayout();
            for (java.awt.Component child : cont.getComponents()) {
                layoutTree(child);
            }
        }
    }

    private static void dumpChain(java.awt.Component c, String label, int depth) {
        if (depth > 6) return;
        System.out.println("[chain] " + "  ".repeat(depth) + label + ": "
            + c.getClass().getSimpleName() + " size=" + c.getSize().width + "x" + c.getSize().height
            + " visible=" + c.isVisible() + " pref=" + c.getPreferredSize().width + "x" + c.getPreferredSize().height
            + " max=" + c.getMaximumSize().width);
        if (c instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) c).getComponents()) {
                dumpChain(child, child.getClass().getSimpleName(), depth + 1);
            }
        }
    }
}
