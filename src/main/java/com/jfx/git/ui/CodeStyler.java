package com.jfx.git.ui;

import com.jfx.git.ui.syntax.Highlighter;
import com.jfx.git.ui.syntax.ScopedTokenizer;

import org.fxmisc.richtext.StyleClassedTextArea;

import java.util.ArrayList;
import java.util.List;

/**
 * Syntax colours for plain source text, outside the diff viewer.
 *
 * <p>The diff viewer lexes rows - hunk headers, expanders, padding - and resets the lexer
 * at each one. The merge dialog has no rows, only a file, and until this existed its panes
 * had no colours at all: the one place a user has to read code most carefully was the one
 * place it arrived as flat text.
 */
public final class CodeStyler {

    private CodeStyler() {
    }

    /**
     * Per-line syntax ranges for {@code lines}, lexed in order with the grammar's state
     * carried from line to line.
     *
     * @return one list per line, or null when the path has no grammar or the text is past
     *         what is worth highlighting
     */
    public static List<List<DiffTextArea.StyleRange>> lex(Highlighter highlighter, String path,
                                                          List<String> lines) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        ScopedTokenizer tokenizer = Highlighter.tokenizerForPath(path);
        if (tokenizer == null || Highlighter.refusal(lines) != null) {
            return null;
        }
        List<List<DiffTextArea.StyleRange>> ranges = new ArrayList<>(lines.size());
        Object state = tokenizer.initialState();
        for (String line : lines) {
            if (!Highlighter.lexable(line)) {
                ranges.add(List.of());
                state = tokenizer.initialState();
                continue;
            }
            ScopedTokenizer.Lexed lexed = highlighter.lexScoped(tokenizer, line, state);
            List<DiffTextArea.StyleRange> lineRanges = new ArrayList<>();
            for (ScopedTokenizer.Span span : lexed.spans()) {
                for (String styleClass : span.styleClasses()) {
                    lineRanges.add(new DiffTextArea.StyleRange(span.start(), span.end(), styleClass));
                }
            }
            ranges.add(lineRanges);
            state = lexed.stateOut();
        }
        return ranges;
    }

    /**
     * Applies {@code ranges} to one paragraph of {@code area}, replacing whatever it had.
     *
     * <p>For text the user edits, where there is no display-to-source mapping to go
     * through: offsets are the paragraph's own. An empty list clears the paragraph, so a
     * line that lost its last token loses its colour too.
     */
    public static void apply(StyleClassedTextArea area, int paragraph, List<DiffTextArea.StyleRange> ranges) {
        if (paragraph < 0 || paragraph >= area.getParagraphs().size()) {
            return;
        }
        int length = area.getParagraphLength(paragraph);
        if (length == 0) {
            return;
        }
        area.setStyleSpans(paragraph, 0, DiffTextArea.spans(length, ranges, offset -> offset));
    }
}
