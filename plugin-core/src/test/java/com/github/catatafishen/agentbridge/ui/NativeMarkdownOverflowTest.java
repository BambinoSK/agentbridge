package com.github.catatafishen.agentbridge.ui;


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
 * Bubble content must never lay out or paint wider than the pane's allocated
 * width.
 *
 * <p>Renders a matrix of message shapes into a real {@link NativeMarkdownPane}
 * at a fixed width and measures two things per shape:
 * <ol>
 *   <li>View-tree layout: the maximum right edge of every laid-out view
 *       allocation outside {@code pre} (views inside {@code pre} are
 *       horizontally scrollable by design and are excluded).</li>
 *   <li>Painted pixels: the laid-out view tree is painted onto a canvas twice
 *       as wide as the pane with no clip at the pane edge; any non-background
 *       pixel beyond the pane width is overflow the user would see.</li>
 * </ol>
 *
 * <p>The regression these tests guard: a fenced code block with one long line
 * used to lay the WHOLE document body, prose included, out at the code width,
 * clipping every prose line at the bubble edge (see issue #1180). The fix is
 * {@code ScrollableCodeView.getMinimumSpan(X_AXIS) = 0}, mirroring the existing
 * preferred-span override; {@code BoxView.layoutMinorAxis} never allocates a
 * child below its minimum span, so the uncapped code-line minimum used to
 * propagate up through the whole box hierarchy.
 */
public class NativeMarkdownOverflowTest extends NativeMarkdownPaneTestBase {

    private static final int PANE_WIDTH = 320;
    private static final int CANVAS_WIDTH = 2 * PANE_WIDTH;
    /**
     * Glyph rasterization can overshoot the laid-out line advance by a few
     * pixels, so lines that wrap flush against the pane edge may paint a thin
     * fringe just past it. A real overflow paints hundreds of full columns
     * (the regression this guards measured 319); the fringe stays within a
     * dozen pixels on one or two rows.
     */
    private static final int PIXEL_TOLERANCE = 12;
    private static final Color SENTINEL = new Color(255, 0, 255);

    private NativeMarkdownPane pane;

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
    public void testPlainProseStaysInsidePane() {
        String md = "The quick brown fox jumps over the lazy dog and then a "
            + "supercalifragilisticexpialidociouswordthatisfarlongerthanthirtychars appears here.\n";
        Report r = measure(md);
        assertEquals("plain prose must not lay out wider than the pane", 0, r.nonPreOverflowCount);
        assertEquals("plain prose must not paint past the pane edge", 0, r.pixelOverflowCount);
    }

    public void testInlineCodeSpanStaysInsidePane() {
        String md = "The configuration key "
            + "`verylong.configuration.key.name.that.keeps.going.and.going.and.going.on.forever`"
            + " controls the retry behavior of the client.\n";
        Report r = measure(md);
        assertEquals("views outside pre must stay inside the pane", 0, r.nonPreOverflowCount);
        assertEquals("painted pixels must stay inside the pane " + r.offenders, 0, r.pixelOverflowCount);
    }

    public void testIndentedCodeLineStaysInsidePane() {
        String md = "Intro line.\n\n"
            + "    this.is.an.indented.code.line().with().a().very().long().chain().of().calls().that().never().ends\n"
            + "\nOutro.\n";
        Report r = measure(md);
        assertEquals("views outside pre must stay inside the pane", 0, r.nonPreOverflowCount);
        assertEquals("painted pixels must stay inside the pane " + r.offenders, 0, r.pixelOverflowCount);
    }

    public void testInlineCodeInListItemStaysInsidePane() {
        String md = "1. First normal item.\n2. Second item references "
            + "`averylongidentifiername.inside.a.list.item.that.keeps.extending.past.the.bubble.width` here.\n";
        Report r = measure(md);
        assertEquals("views outside pre must stay inside the pane", 0, r.nonPreOverflowCount);
        assertEquals("painted pixels must stay inside the pane " + r.offenders, 0, r.pixelOverflowCount);
    }

    public void testTableCodeCellStaysInsidePane() {
        String md = "| Setting | Meaning |\n|---|---|\n"
            + "| `client.retry.maxAttemptsWithBackoffAndJitter` | how many times |\n"
            + "| timeout | seconds |\n";
        Report r = measure(md);
        assertEquals("views outside pre must stay inside the pane", 0, r.nonPreOverflowCount);
        assertEquals("painted pixels must stay inside the pane " + r.offenders, 0, r.pixelOverflowCount);
    }

    public void testCodeInBlockquoteStaysInsidePane() {
        String md = "> The helper `someExtremelyLongFunctionNameThatNeverWrapsAnywhereAtAll()` does the work.\n";
        Report r = measure(md);
        assertEquals("views outside pre must stay inside the pane", 0, r.nonPreOverflowCount);
        assertEquals("painted pixels must stay inside the pane " + r.offenders, 0, r.pixelOverflowCount);
    }

    /** The reported regression: prose around a fenced block with one long line. */
    public void testFencedCodeBlockLongLineKeepsProseInsidePane() {
        String md = "Before the block.\n\n```java\n"
            + "this.is.a.very.long.chain.of.method.calls().that().goes().on().and().on().and().never().stops().at().all\n"
            + "short();\n```\n\nAfter the block.\n";
        Report r = measure(md);
        assertEquals("prose around a fenced code block must not lay out at the code width",
            0, r.nonPreOverflowCount);
        assertEquals("prose around a fenced code block must not paint past the pane edge",
            0, r.pixelOverflowCount);
        // The core of the fix: the pre view must not leak its natural width into
        // the box minimums, or BoxView.layoutMinorAxis widens the whole body.
        assertTrue("body minimum width must not exceed the pane width (was " + r.bodyMinimumX + ")",
            r.bodyMinimumX <= PANE_WIDTH);
        assertEquals("pre must report a zero X minimum, like its preferred span",
            0, r.preMinimumX);
    }

    /** Same shape, but parsed through the streaming path the way chunks arrive. */
    public void testFencedCodeBlockStreamedKeepsProseInsidePane() {
        String md = "Before the block.\n\n```java\n"
            + "this.is.a.very.long.chain.of.method.calls().that().goes().on().and().on().and().never().stops().at().all\n"
            + "short();\n```\n\nAfter the block.\n";
        for (String c : chunk(md, 41)) {
            pane.appendMarkdown(c);
        }
        pane.notifyStreamDone();
        Report r = measureAlreadySet();
        assertEquals("views outside pre must stay inside the pane", 0, r.nonPreOverflowCount);
        assertEquals("painted pixels must stay inside the pane " + r.offenders, 0, r.pixelOverflowCount);
        assertTrue(r.bodyMinimumX <= PANE_WIDTH);
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

        // Span probes on the body box: the regression showed up as a body
        // minimum of the longest code line, so both are recorded.
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
        for (int x = PANE_WIDTH + PIXEL_TOLERANCE; x < CANVAS_WIDTH; x++) {
            for (int y = 0; y < height; y++) {
                int rgb = img.getRGB(x, y);
                if (rgb != SENTINEL.getRGB()) {
                    r.pixelOverflowCount++;
                    if (r.pixelOverflowCount <= 8) r.offenders.add("pixel x=" + x + " y=" + y + " rgb=" + Integer.toHexString(rgb));
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
                // Code blocks are clipped and horizontally scrollable by design.
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
}
