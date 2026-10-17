package com.github.catatafishen.agentbridge.ui;


import javax.swing.text.View;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Interaction with the per-code-block horizontal scrollbar.
 *
 * <ul>
 *   <li>The strip is draggable with the mouse (press, drag, release) and a
 *       press on the track jumps the thumb immediately.</li>
 *   <li>Strip interaction must never select text.</li>
 *   <li>Shift+wheel over an overflowing block scrolls it horizontally; a plain
 *       wheel passes through so the chat keeps scrolling vertically.</li>
 *   <li>A text selection dragged past the block edge auto-scrolls the block.</li>
 * </ul>
 *
 * <p>Drives the pane's real event handlers with synthetic MouseEvents and
 * asserts on the internal state of the file-private ScrollableCodeView,
 * reached through reflection: Kotlin mangles internal member names with a
 * module suffix, so methods are matched by simple-name prefix.
 */
public class NativeMarkdownCodeScrollInteractionTest extends NativeMarkdownPaneTestBase {

    private static final int PANE_WIDTH = 320;

    private NativeMarkdownPane pane;
    private View codeView;

    private static final String MD = "Before the block.\n\n```java\n"
        + "this.is.a.very.long.chain.of.method.calls().that().goes().on().and().on().and().never().stops().at().all\n"
        + "short();\n```\n\nAfter the block.\n";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        pane = new NativeMarkdownPane(new FileNavigator(getProject()));
        pane.setSize(PANE_WIDTH, 4096);
        pane.setCompleteMarkdown(MD);

        // Lay out at the pane width, then paint once so ScrollableCodeView.paint
        // records its allocation (the drag geometry is derived from it).
        View root = pane.getUI().getRootView(pane);
        root.setSize((float) PANE_WIDTH, Short.MAX_VALUE);
        int height = (int) root.getPreferredSpan(View.Y_AXIS) + 8;
        BufferedImage img = new BufferedImage(PANE_WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setClip(0, 0, PANE_WIDTH, height);
        root.paint(g, new Rectangle(0, 0, PANE_WIDTH, height));
        g.dispose();

        codeView = findScrollableCodeView(root);
        assertNotNull("code block must render as a ScrollableCodeView", codeView);
        assertTrue("block with a long line must be scrollable", (Boolean) inv(codeView, "isScrollableNow"));
    }

    public void testPlainWheelOverBlockPassesThroughToChat() throws Exception {
        Rectangle strip = (Rectangle) inv(codeView, "stripHitZone");
        assertNotNull(strip);
        int before = (Integer) inv(codeView, "currentScrollX");

        int x = strip.x + strip.width / 2;
        int y = strip.y + 2;
        MouseWheelEvent e = new MouseWheelEvent(
            pane, MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
            x, y, x, y, 1, false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 2, 2d);
        invokePane("handleWheel", e);

        assertFalse("plain wheel must pass through so the chat can scroll vertically", e.isConsumed());
        assertEquals("plain wheel must not scroll the code block",
            before, (int) (Integer) inv(codeView, "currentScrollX"));
    }

    public void testShiftWheelOverBlockIsConsumed() throws Exception {
        Rectangle strip = (Rectangle) inv(codeView, "stripHitZone");
        int x = strip.x + strip.width / 2;
        int y = strip.y + 2;
        MouseWheelEvent e = new MouseWheelEvent(
            pane, MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(),
            InputEvent.SHIFT_DOWN_MASK,
            x, y, x, y, 1, false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1, 1d);
        invokePane("handleWheel", e);
        assertTrue("shift+wheel over an overflowing block is the block's scroll gesture",
            e.isConsumed());
    }

    public void testStripDragScrollsToEndWithoutSelectingText() throws Exception {
        Rectangle strip = (Rectangle) inv(codeView, "stripHitZone");
        Object geo = inv(codeView, "dragGeometry");
        assertNotNull(geo);
        int trackX = fld(geo, "trackX");
        int trackW = fld(geo, "trackW");
        int thumbW = fld(geo, "thumbW");
        int thumbX = fld(geo, "thumbX");
        int maxScroll = fld(geo, "maxScroll");

        // Press on the thumb. The constructor variant with the trailing button
        // parameter carries the button; the xAbs/yAbs variant without it
        // hard-wires NOBUTTON and fails isLeftMouseButton.
        MouseEvent press = new MouseEvent(pane, MouseEvent.MOUSE_PRESSED,
            System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK,
            thumbX + 4, strip.y + strip.height / 2, 1, false, MouseEvent.BUTTON1);
        assertTrue("press on the strip must be consumed", (Boolean) invokePane("handleStripMouse", press));
        assertTrue(press.isConsumed());

        // Drag the thumb to the far right of the track. The drag target adds the
        // grab offset (4) so the grab point, not the thumb's left edge, reaches
        // the end; the thumb follows the grab point.
        int dragX = trackX + (trackW - thumbW) + 4;
        MouseEvent drag = new MouseEvent(pane, MouseEvent.MOUSE_DRAGGED,
            System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK,
            dragX, strip.y + strip.height / 2, 1, false, MouseEvent.BUTTON1);
        assertTrue("drag on the strip must be consumed", (Boolean) invokePane("handleStripMouse", drag));

        int scroll = (Integer) inv(codeView, "currentScrollX");
        assertEquals("dragging the thumb to the track end must reach max scroll",
            maxScroll, scroll);

        // Release ends the drag.
        MouseEvent release = new MouseEvent(pane, MouseEvent.MOUSE_RELEASED,
            System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK,
            dragX, strip.y + strip.height / 2, 1, false, MouseEvent.BUTTON1);
        assertTrue((Boolean) invokePane("handleStripMouse", release));

        // No text selection may result from the interaction.
        assertEquals("strip drag must not select text",
            pane.getSelectionStart(), pane.getSelectionEnd());
    }

    public void testSelectionDragAutoScrollsBlock() throws Exception {
        Rectangle strip = (Rectangle) inv(codeView, "stripHitZone");
        Object geo = inv(codeView, "dragGeometry");
        int maxScroll = fld(geo, "maxScroll");
        int y = strip.y - strip.height; // inside the code text area, above the strip

        // Drag the selection to the right edge of the block, then past it.
        MouseEvent dragEdge = new MouseEvent(pane, MouseEvent.MOUSE_DRAGGED,
            System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK,
            PANE_WIDTH - 2, y, PANE_WIDTH - 2, y, 1, false, MouseEvent.BUTTON1);
        invokePane("handleSelectionAutoScroll", dragEdge);
        int afterEdge = (Integer) inv(codeView, "currentScrollX");
        assertTrue("dragging the selection to the right edge must start scrolling the block",
            afterEdge > 0);

        // Further right: the block scrolls further with the selection.
        MouseEvent dragFar = new MouseEvent(pane, MouseEvent.MOUSE_DRAGGED,
            System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK,
            PANE_WIDTH + 60, y, PANE_WIDTH + 60, y, 1, false, MouseEvent.BUTTON1);
        invokePane("handleSelectionAutoScroll", dragFar);
        int afterFar = (Integer) inv(codeView, "currentScrollX");
        assertTrue("dragging further right must scroll further (afterEdge=" + afterEdge
            + " afterFar=" + afterFar + ")", afterFar > afterEdge);
        assertTrue(afterFar <= maxScroll);
    }

    public void testTrackClickJumpsThumb() throws Exception {
        Rectangle strip = (Rectangle) inv(codeView, "stripHitZone");
        Object geo = inv(codeView, "dragGeometry");
        int trackX = fld(geo, "trackX");
        int trackW = fld(geo, "trackW");
        int maxScroll = fld(geo, "maxScroll");

        // Press far right on the track, past the thumb: the thumb must jump there
        // immediately, without any drag movement.
        int x = trackX + trackW - 2;
        int y = strip.y + strip.height / 2;
        MouseEvent press = new MouseEvent(pane, MouseEvent.MOUSE_PRESSED,
            System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK,
            x, y, 1, false, MouseEvent.BUTTON1);
        assertTrue((Boolean) invokePane("handleStripMouse", press));

        int scroll = (Integer) inv(codeView, "currentScrollX");
        assertEquals("clicking the far right of the track must jump to max scroll",
            maxScroll, scroll);

        MouseEvent release = new MouseEvent(pane, MouseEvent.MOUSE_RELEASED,
            System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK,
            x, y, 1, false, MouseEvent.BUTTON1);
        assertTrue((Boolean) invokePane("handleStripMouse", release));
        assertEquals("strip click must not select text",
            pane.getSelectionStart(), pane.getSelectionEnd());
    }

    public void testPressOutsideStripPassesThrough() throws Exception {
        // A press in the prose area (top-left of the pane) is not strip business.
        MouseEvent press = new MouseEvent(pane, MouseEvent.MOUSE_PRESSED,
            System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK,
            10, 10, 1, false);
        assertFalse("press outside the strip must pass through",
            (Boolean) invokePane("handleStripMouse", press));
        assertFalse(press.isConsumed());
    }

    /** A widening resize must clear a scroll offset that is now stale. */
    public void testWideningResizeClearsStaleScroll() throws Exception {
        Object geo = inv(codeView, "dragGeometry");
        int maxScroll = fld(geo, "maxScroll");
        assertTrue(maxScroll > 0);
        inv(codeView, "scrollTo", maxScroll);
        assertEquals(maxScroll, (int) (Integer) inv(codeView, "currentScrollX"));

        // Widen past the block's natural width, lay out and paint again: the
        // block no longer overflows, so the scroll must reset to zero instead
        // of keeping the children shifted left with no scrollbar in sight.
        int wide = 800;
        View root = pane.getUI().getRootView(pane);
        root.setSize((float) wide, Short.MAX_VALUE);
        int height = (int) root.getPreferredSpan(View.Y_AXIS) + 8;
        BufferedImage img = new BufferedImage(wide, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setClip(0, 0, wide, height);
        root.paint(g, new Rectangle(0, 0, wide, height));
        g.dispose();

        assertEquals("a widening resize must clear the stale scroll offset",
            0, (int) (Integer) inv(codeView, "currentScrollX"));
    }

    /** Shift+wheel only owns blocks that actually overflow. */
    public void testShiftWheelOverNonOverflowingBlockPassesThrough() throws Exception {
        pane.setCompleteMarkdown("Before.\n\n```java\nshort();\n```\n\nAfter.\n");
        View root = pane.getUI().getRootView(pane);
        root.setSize((float) PANE_WIDTH, Short.MAX_VALUE);
        int height = (int) root.getPreferredSpan(View.Y_AXIS) + 8;
        BufferedImage img = new BufferedImage(PANE_WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setClip(0, 0, PANE_WIDTH, height);
        root.paint(g, new Rectangle(0, 0, PANE_WIDTH, height));
        g.dispose();

        View shortBlock = findScrollableCodeView(root);
        assertNotNull(shortBlock);
        assertFalse("short block must not be scrollable", (Boolean) inv(shortBlock, "isScrollableNow"));
        Rectangle alloc = (Rectangle) inv(shortBlock, "allocationBounds");
        assertNotNull(alloc);
        int x = alloc.x + alloc.width / 2;
        int y = alloc.y + alloc.height / 2;
        MouseWheelEvent e = new MouseWheelEvent(
            pane, MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(),
            InputEvent.SHIFT_DOWN_MASK,
            x, y, x, y, 1, false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1, 1d);
        invokePane("handleWheel", e);
        assertFalse("shift+wheel over a non-overflowing block must pass through to the chat",
            e.isConsumed());
    }

    // ------------------------------------------------------------------
    // Reflection helpers (ScrollableCodeView is file-private; Kotlin mangles
    // internal member names with the module suffix)
    // ------------------------------------------------------------------

    private static View findScrollableCodeView(View v) {
        if ("ScrollableCodeView".equals(v.getClass().getSimpleName())) return v;
        for (int i = 0; i < v.getViewCount(); i++) {
            View found = findScrollableCodeView(v.getView(i));
            if (found != null) return found;
        }
        return null;
    }

    /** Invokes a method on the pane by simple-name prefix, mangling-proof. */
    private Object invokePane(String name, Object arg) throws Exception {
        Method m = findMethod(NativeMarkdownPane.class, name);
        return m.invoke(pane, arg);
    }

    /** Invokes a method by simple-name prefix so name mangling does not matter. */
    private static Object inv(Object target, String name, Object... args) throws Exception {
        Method m = findMethod(target.getClass(), name);
        return m.invoke(target, args);
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name) || m.getName().startsWith(name + "$")) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new AssertionError("method not found: " + name + " on " + type);
    }

    private static int fld(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(target);
    }
}
