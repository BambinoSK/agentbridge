import {describe, expect, it} from 'vitest';
import {renderMarkdown} from '../chat-ui/src/renderMarkdown.ts';

// Card #110 harness — ordered-list support in the web streaming renderer.
// The web renderer mirrors the Kotlin one; numbered lines ("1. ...") fell
// through to the paragraph fallback, so the web streaming bubble showed
// flush-left paragraphs instead of a list. These cases fail on plain master
// and must pass on the ordered-list fix (chat-ui/src/renderMarkdown.ts;
// chat.css already styles ol/li, no CSS change needed).

describe('renderMarkdown ordered lists', () => {
    it('renders consecutive numbered items as an ordered list', () => {
        const html = renderMarkdown('1. first item\n2. second item\n3. third item');
        expect(html).toContain('<ol>');
        expect(html).toContain('<li>first item</li>');
        expect(html).toContain('<li>second item</li>');
        expect(html).toContain('</ol>');
    });

    it('keeps one list across blank lines between items', () => {
        const html = renderMarkdown('1. first item\n\n2. second item');
        expect((html.match(/<ol>/g) || []).length).toBe(1);
        expect((html.match(/<li>/g) || []).length).toBe(2);
    });

    it('closes the list before a following paragraph', () => {
        const html = renderMarkdown('1. first item\n2. second item\n\nClosing paragraph.');
        expect(html.indexOf('</ol>')).toBeGreaterThan(-1);
        expect(html.indexOf('</ol>')).toBeLessThan(html.indexOf('<p>Closing'));
    });

    it('closes an unterminated list at end of stream', () => {
        const html = renderMarkdown('1. only item');
        expect(html).toContain('<ol>');
        expect(html).toContain('</ol>');
    });

    it('accepts the parenthesized marker variant', () => {
        const html = renderMarkdown('1) first item\n2) second item');
        expect(html).toContain('<ol>');
    });

    it('does not treat decimal numbers as list markers', () => {
        const html = renderMarkdown('The shaft is 12.5 mm long.');
        expect(html).not.toContain('<ol>');
        expect(html).not.toContain('<li>');
    });

    it('keeps inline formatting inside ordered items', () => {
        const html = renderMarkdown('1. item with `code` and **bold**');
        expect(html).toContain('<li>');
        expect(html).toContain('<code>code</code>');
        expect(html).toContain('<b>bold</b>');
    });
});
