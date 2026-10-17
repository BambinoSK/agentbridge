package com.github.catatafishen.agentbridge.harness;

import com.github.catatafishen.agentbridge.ui.FileNavigator;
import com.github.catatafishen.agentbridge.ui.NativeMarkdownPane;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.text.Element;
import javax.swing.text.View;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Card #110 follow-up harness: does bubble content ever lay out or paint wider
 * than the pane's allocated width, and which content shape triggers it?
 *
 * <p>Renders a matrix of message shapes into a real {@link NativeMarkdownPane}
 * at a fixed width and measures two things per shape:
 * <ol>
 *   <li>View-tree layout: the maximum right edge of every laid-out view
 *       allocation, split into views inside {@code pre} (horizontally
 *       scrollable by design) and views outside it (must never overflow).</li>
 *   <li>Painted pixels: the pane is painted into a canvas twice as wide as the
 *       pane with no clip at the pane edge; any non-background pixel beyond
 *       the pane width is overflow the user would see (minus the pre clip).</li>
 * </ol>
 *
 * <p>Diagnostic by design: every scenario prints its measurement table; only
 * the plain-prose control asserts no overflow, to prove the harness itself
 * measures correctly. The scenario that reproduces the reported IDE overflow
 * is then promoted to a RED assertion.
 */
public class MarkdownBubbleOverflowTest extends BasePlatformTestCase {

    private static final int PANE_WIDTH = 320;
    private static final int CANVAS_WIDTH = 2 * PANE_WIDTH;
    private static final Color SENTINEL = new Color(255, 0, 255);

    private NativeMarkdownPane pane;

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

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        pane = new NativeMarkdownPane(new FileNavigator(getProject()));
        pane.setSize(PANE_WIDTH, 4096);
    }

    // ------------------------------------------------------------------
    // Scenarios
    // ------------------------------------------------------------------

    /** Control: plain prose with one long word must wrap within the pane. */
    public void testControlPlainProseWraps() throws Exception {
        String md = "The quick brown fox jumps over the lazy dog and then a "
            + "supercalifragilisticexpialidociouswordthatisfarlongerthanthirtychars appears here.\n";
        Report r = measure(md);
        assertEquals("control prose must not lay out wider than the pane",
            0, r.nonPreOverflowCount);
    }

    public void testScenarioInlineCodeLongSpan() throws Exception {
        String md = "The configuration key "
            + "`verylong.configuration.key.name.that.keeps.going.and.going.and.going.on.forever`"
            + " controls the retry behavior of the client.\n";
        print(measure(md), "inline-code-long-span");
    }

    public void testScenarioFencedCodeLongLine() throws Exception {
        String md = "Before the block.\n\n```java\n"
            + "this.is.a.very.long.chain.of.method.calls().that().goes().on().and().on().and().never().stops().at().all\n"
            + "short();\n```\n\nAfter the block.\n";
        print(measure(md), "fenced-code-long-line");
    }

    public void testScenarioFencedCodeStreamed() throws Exception {
        // The way it actually arrives: chunked mid-line while streaming.
        String md = "Before the block.\n\n```java\n"
            + "this.is.a.very.long.chain.of.method.calls().that().goes().on().and().on().and().never().stops().at().all\n"
            + "short();\n```\n\nAfter the block.\n";
        for (String c : chunk(md, 41)) {
            pane.appendMarkdown(c);
        }
        pane.notifyStreamDone();
        print(measureAlreadySet(), "fenced-code-streamed");
    }

    public void testScenarioIndentedCodeLongLine() throws Exception {
        String md = "Intro line.\n\n    this.is.an.indented.code.line().with().a().very().long().chain().of().calls().that().never().ends\n\nOutro.\n";
        print(measure(md), "indented-code-long-line");
    }

    public void testScenarioInlineCodeInListItem() throws Exception {
        String md = "1. First normal item.\n2. Second item references "
            + "`averylongidentifiername.inside.a.list.item.that.keeps.extending.past.the.bubble.width` here.\n";
        print(measure(md), "inline-code-in-list-item");
    }

    public void testScenarioTableWithLongCodeCell() throws Exception {
        String md = "| Setting | Meaning |\n|---|---|\n"
            + "| `client.retry.maxAttemptsWithBackoffAndJitter` | how many times |\n"
            + "| timeout | seconds |\n";
        print(measure(md), "table-with-long-code-cell");
    }

    public void testScenarioCodeInBlockquote() throws Exception {
        String md = "> The helper `someExtremelyLongFunctionNameThatNeverWrapsAnywhereAtAll()` does the work.\n";
        print(measure(md), "code-in-blockquote");
    }

    // ------------------------------------------------------------------
    // Measurement machinery
    // ------------------------------------------------------------------

    private static List<String> chunk(String text, int size) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            out.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return out;
    }

    private Report measure(String markdown) {
        pane.setCompleteMarkdown(markdown);
        return measureAlreadySet();
    }

    private Report measureAlreadySet() {
        Report r = new Report();
        View root = pane.getUI().getRootView(pane);
        root.setSize((float) PANE_WIDTH, Short.MAX_VALUE);
        float h = root.getPreferredSpan(View.Y_AXIS);
        walk(root, new Rectangle(0, 0, PANE_WIDTH, (int) h), "root", r);

        // Direct span probes: does the pre view hide only its preferred span?
        View body = root.getView(0);
        while (body != null && body.getViewCount() > 0
            && !"body".equals(body.getElement() != null ? body.getElement().getName() : "")) {
            body = body.getView(0);
        }
        if (body != null) {
            r.bodyPreferredX = (int) body.getPreferredSpan(View.X_AXIS);
            r.bodyMinimumX = (int) body.getMinimumSpan(View.X_AXIS);
            for (int i = 0; i < body.getViewCount(); i++) {
                View c = body.getView(i);
                String n = c.getElement() != null ? c.getElement().getName() : "?";
                if ("pre".equals(n)) {
                    r.prePreferredX = (int) c.getPreferredSpan(View.X_AXIS);
                    r.preMinimumX = (int) c.getMinimumSpan(View.X_AXIS);
                }
            }
        }

        // Paint the laid-out view tree directly onto a canvas wider than the
        // pane (no clip at the pane edge) so any text laid out beyond the pane
        // width shows up as painted pixels, bypassing the pane's paint cache.
        int height = Math.max((int) h + 8, 64);
        BufferedImage img = new BufferedImage(CANVAS_WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(SENTINEL);
        g.fillRect(0, 0, CANVAS_WIDTH, height);
        g.setClip(0, 0, CANVAS_WIDTH, height);
        root.paint(g, new Rectangle(0, 0, PANE_WIDTH, height));
        g.dispose();
        r.pixelOverflowMaxX = 0;
        r.pixelOverflowCount = 0;
        for (int x = PANE_WIDTH + 1; x < CANVAS_WIDTH; x++) {
            for (int y = 0; y < height; y++) {
                int rgb = img.getRGB(x, y);
                if (rgb != SENTINEL.getRGB()) {
                    r.pixelOverflowCount++;
                    if (x > r.pixelOverflowMaxX) r.pixelOverflowMaxX = x;
                    break;
                }
            }
        }
        return r;
    }

    private void walk(View parent, Shape alloc, String path, Report r) {
        int n = parent.getViewCount();
        for (int i = 0; i < n; i++) {
            View child = parent.getView(i);
            Shape s = parent.getChildAllocation(i, alloc);
            if (s == null) continue;
            Rectangle b = s.getBounds();
            String name = child.getElement() != null ? child.getElement().getName() : "?";
            String childPath = path + "/" + name;
            int right = b.x + b.width;
            boolean inPre = childPath.contains("/pre");
            if (inPre) {
                if (right > PANE_WIDTH) {
                    r.preOverflowCount++;
                    if (right > r.preMaxRight) r.preMaxRight = right;
                }
            } else {
                if (right > PANE_WIDTH + 2) { // 2px tolerance for borders
                    r.nonPreOverflowCount++;
                    if (right > r.nonPreMaxRight) r.nonPreMaxRight = right;
                    r.offenders.add(childPath + " right=" + right);
                }
            }
            walk(child, s, childPath, r);
        }
    }

    private static final class Report {
        int nonPreOverflowCount;
        int nonPreMaxRight;
        final List<String> offenders = new ArrayList<>();
        int preOverflowCount;
        int preMaxRight;
        int pixelOverflowCount;
        int pixelOverflowMaxX;
        int bodyPreferredX;
        int bodyMinimumX;
        int prePreferredX;
        int preMinimumX;
    }

    private void print(Report r, String label) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== overflow report: ").append(label).append(" ===\n");
        sb.append("  views outside pre overflowing pane(").append(PANE_WIDTH).append("): ")
          .append(r.nonPreOverflowCount).append(", maxRight=").append(r.nonPreMaxRight).append('\n');
        for (String o : r.offenders) sb.append("    offender: ").append(o).append('\n');
        sb.append("  views inside pre exceeding pane (scrollable by design): ")
          .append(r.preOverflowCount).append(", maxRight=").append(r.preMaxRight).append('\n');
        sb.append("  spans: body preferredX=").append(r.bodyPreferredX)
          .append(" body minimumX=").append(r.bodyMinimumX)
          .append(" pre preferredX=").append(r.prePreferredX)
          .append(" pre minimumX=").append(r.preMinimumX).append('\n');
        sb.append("  painted pixels beyond pane edge: count=").append(r.pixelOverflowCount)
          .append(", maxX=").append(r.pixelOverflowMaxX).append('\n');
        System.out.println(sb);
        fail("diagnostic run - see report:\n" + sb);
    }
}
