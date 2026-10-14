package com.github.catatafishen.agentbridge.harness;

import com.github.catatafishen.agentbridge.ui.FileNavigator;
import com.github.catatafishen.agentbridge.ui.NativeMarkdownPane;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;

import javax.swing.text.Element;
import javax.swing.text.View;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Card #110 harness — streaming accumulation and Swing layout of the real
 * chat markdown pane.
 *
 * <p>Streams a numbered-list reply into a real {@link NativeMarkdownPane}
 * in chunks (the way session/update agent_message_chunk notifications arrive)
 * and asserts both data integrity and the rendered list structure: list
 * items must exist as {@code li} elements, sit in a marker gutter to the
 * right of paragraph text, and continuation lines must align under the item
 * text (hanging indent).
 *
 * <p>The raw-text integrity assertions pass on master (the pipeline is
 * byte-transparent); the list-structure assertions fail on plain master —
 * that failure is the harness's reproduction of the reported problem and
 * must be turned green by the ordered-list fix.
 */
public class MarkdownStreamingPaneTest extends BasePlatformTestCase {

    private static final int PANE_WIDTH = 320;

    private NativeMarkdownPane pane;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        pane = new NativeMarkdownPane(new FileNavigator(getProject()));
        pane.setSize(PANE_WIDTH, 4096);
    }

    private String fixture(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/harness/scenarios/" + name)) {
            assertNotNull("missing fixture: " + name, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Splits text into fixed-size chunks at arbitrary (often mid-word) offsets. */
    private static List<String> chunk(String text, int size) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            out.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return out;
    }

    public void testStreamedReplyAccumulatesByteExact() throws Exception {
        String text = fixture("card110-numbered-reply.md");
        for (String c : chunk(text, 37)) {
            pane.appendMarkdown(c);
        }
        pane.notifyStreamDone();
        assertEquals("streaming accumulation must be byte-transparent",
            text, pane.getRawText());
    }

    public void testNumberedListStreamsIntoLiElements() throws Exception {
        String text = fixture("ordered-list-sample.md");
        for (String c : chunk(text, 23)) {
            pane.appendMarkdown(c);
        }
        pane.notifyStreamDone();
        assertEquals(text, pane.getRawText());

        List<Element> items = elementsNamed(pane.getDocument().getDefaultRootElement(), "li");
        assertEquals("numbered items must render as li elements (got " + items.size() + ")",
            3, items.size());
    }

    public void testNumberedListHasMarkerGutterAndHangingIndent() throws Exception {
        String text = fixture("ordered-list-sample.md");
        pane.setCompleteMarkdown(text);

        List<Element> items = elementsNamed(pane.getDocument().getDefaultRootElement(), "li");
        assertEquals(3, items.size());

        // Force layout at the pane width, then measure row left-edge x positions.
        View root = pane.getUI().getRootView(pane);
        root.setSize((float) PANE_WIDTH, Short.MAX_VALUE);
        int paragraphX = xOfOffset(root, 1); // "Para before the list." starts here

        for (Element li : items) {
            // x of the item's first text character = left edge of the item text
            // (the numeral is painted by Swing in the padding-left marker band).
            int itemX = xOfOffset(root, li.getStartOffset());
            assertTrue("list item text must sit in a marker gutter right of paragraph text (x="
                    + itemX + " vs " + paragraphX + ")",
                itemX >= paragraphX + 15);
            // Hanging indent: the LEFT EDGE of any wrapped continuation row must
            // equal the item text's left edge. (xOfOffset of an arbitrary mid-row
            // character would just be that character's position, not the edge.)
            int mid = (li.getStartOffset() + li.getEndOffset()) / 2;
            if (mid > li.getStartOffset()) {
                int midRowStart = javax.swing.text.Utilities.getRowStart(pane, mid);
                int midX = xOfOffset(root, midRowStart);
                assertTrue("continuation lines must keep the hanging indent (x=" + midX
                    + " vs " + itemX + ")", Math.abs(midX - itemX) <= 2);
            }
            int lastRowStart = javax.swing.text.Utilities.getRowStart(pane, li.getEndOffset() - 2);
            int lastX = xOfOffset(root, lastRowStart);
            assertTrue("last row must keep the hanging indent (x=" + lastX + " vs " + itemX + ")",
                Math.abs(lastX - itemX) <= 2);
        }
    }

    public void testUnorderedListAlsoKeepsMarkerGutter() throws Exception {
        // Existing bulleted lists overlap their markers the same way the
        // screenshots showed for numbers — the stylesheet fix must cover both.
        pane.setCompleteMarkdown("Para before.\n\n- bullet one\n- bullet two\n\nPara after.");
        View root = pane.getUI().getRootView(pane);
        root.setSize((float) PANE_WIDTH, Short.MAX_VALUE);
        List<Element> items = elementsNamed(pane.getDocument().getDefaultRootElement(), "li");
        assertEquals(2, items.size());
        int paragraphX = xOfOffset(root, 1);
        int itemX = xOfOffset(root, items.get(0).getStartOffset() + 1);
        assertTrue("bullet item text must sit right of paragraph text (x=" + itemX + ")",
            itemX >= paragraphX + 15);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static @NotNull List<Element> elementsNamed(Element root, String name) {
        List<Element> out = new ArrayList<>();
        collectElements(root, name, out);
        return out;
    }

    private static void collectElements(Element el, String name, List<Element> out) {
        if (name.equals(el.getName())) {
            out.add(el);
        }
        for (int i = 0; i < el.getElementCount(); i++) {
            collectElements(el.getElement(i), name, out);
        }
    }

    private int xOfOffset(View root, int offset) throws Exception {
        // modelToView needs the pane laid out at the target width.
        pane.setSize(PANE_WIDTH, 4096);
        var rect = pane.modelToView2D(offset);
        assertNotNull("no layout for offset " + offset, rect);
        return (int) rect.getX();
    }
}
