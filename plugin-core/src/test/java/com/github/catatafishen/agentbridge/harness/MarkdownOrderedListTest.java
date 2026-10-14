package com.github.catatafishen.agentbridge.harness;

import com.github.catatafishen.agentbridge.ui.MarkdownRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card #110 harness — ordered-list support in the markdown renderer.
 *
 * <p>Agents routinely emit plain numbered lists ("1. ... 2. ..."), and the
 * chat panel collapsed them into flush-left paragraphs with no list
 * structure: the renderer recognized only "-" / "*" markers. These tests pin
 * the DESIRED behavior; they fail on plain master (that failure is the
 * harness's reproduction of the reported problem) and must pass on the fix
 * branch that adds ordered-list support to BOTH the Kotlin renderer and the
 * web renderer (chat-ui/src/renderMarkdown.ts).
 */
class MarkdownOrderedListTest {

    private static String render(String markdown) {
        return MarkdownRenderer.INSTANCE.markdownToHtml(markdown);
    }

    private static int count(String html, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = html.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @Test
    void consecutiveNumberedItemsRenderAsOrderedList() {
        String html = render("1. first item\n2. second item\n3. third item");
        assertTrue(html.contains("<ol"), "expected an ordered list, got: " + html);
        assertTrue(count(html, "<li>") >= 3, "expected three list items, got: " + html);
        assertTrue(html.contains("</ol>"), "list must be closed, got: " + html);
    }

    @Test
    void blankLineBetweenItemsKeepsOneList() {
        String html = render("1. first item\n\n2. second item");
        assertTrue(html.contains("<ol"), "expected an ordered list, got: " + html);
        assertTrue(count(html, "<ol") == 1, "blank line must not split the list, got: " + html);
        assertTrue(count(html, "<li>") == 2, "expected two items in one list, got: " + html);
    }

    @Test
    void paragraphAfterListClosesIt() {
        String html = render("1. first item\n2. second item\n\nClosing paragraph.");
        int closeIdx = html.indexOf("</ol>");
        int paraIdx = html.indexOf("<p>Closing");
        assertTrue(closeIdx != -1 && paraIdx != -1, "expected list close before paragraph, got: " + html);
        assertTrue(closeIdx < paraIdx, "list must close before the following paragraph, got: " + html);
    }

    @Test
    void unterminatedListClosesAtEndOfStream() {
        // Streaming fragment: the renderer must close constructs at EOF.
        String html = render("1. only item");
        assertTrue(html.contains("<ol"), "expected an ordered list, got: " + html);
        assertTrue(html.contains("</ol>"), "EOF must close the open list, got: " + html);
    }

    @Test
    void parenthesizedMarkerVariantIsAccepted() {
        String html = render("1) first item\n2) second item");
        assertTrue(html.contains("<ol"), "expected the 'N)' marker variant to render a list, got: " + html);
    }

    @Test
    void decimalNumbersAreNotListMarkers() {
        // "12.5 mm" has no whitespace after the dot — must stay a paragraph.
        String html = render("The shaft is 12.5 mm long.");
        assertFalse(html.contains("<ol"), "decimals must not start a list, got: " + html);
        assertFalse(html.contains("<li>"), "decimals must not become items, got: " + html);
    }

    @Test
    void orderedAndUnorderedListsInterleave() {
        String html = render("1. ordered\n- bullet\n2. ordered again");
        assertTrue(html.contains("<ol"), "expected an ordered list, got: " + html);
        assertTrue(html.contains("<ul"), "expected an unordered list, got: " + html);
        assertTrue(html.contains("</ol>"), "lists must close, got: " + html);
        assertTrue(html.contains("</ul>"), "lists must close, got: " + html);
    }

    @Test
    void inlineFormattingSurvivesInsideOrderedItems() {
        String html = render("1. item with `code` and **bold**");
        assertTrue(html.contains("<li>"), "expected a list item, got: " + html);
        // The renderer pads inline code with narrow no-break spaces (&#8239;)
        // on both sides — same as unordered items — so match the real format.
        assertTrue(html.contains("<code>&#8239;code&#8239;</code>"),
            "inline code must survive, got: " + html);
        assertTrue(html.contains("<b>bold</b>"), "inline bold must survive, got: " + html);
    }
}
