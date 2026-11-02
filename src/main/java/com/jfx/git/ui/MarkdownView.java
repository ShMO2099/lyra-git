package com.jfx.git.ui;

import com.jfx.git.core.TextSearch.Match;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Path;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import java.io.ByteArrayInputStream;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Markdown file, rendered.
 *
 * <p>Into JavaFX nodes, not into HTML for a web view. A web view is a browser engine the
 * size of the rest of the application, drawn in its own fonts and colours, and it does
 * not run at all in the off-screen toolkit the tests use. What a README needs - headings,
 * paragraphs, lists, quotes, code, tables, links and the repository's own images - is a
 * handful of node types, and drawn with the application's own type scale and theme it
 * looks like part of the application rather than a page pasted into it.
 *
 * <p>What it does not do: raw HTML is shown as the source it is, not interpreted, and
 * images from the web are not fetched - opening a README should not tell a server who
 * opened it. Their alt text stands in. Images in the repository are shown.
 *
 * <p>CommonMark, plus the three things GitHub adds that READMEs lean on: tables,
 * strikethrough, and bare URLs as links.
 *
 * <p>The text can be selected and copied, and searched with the {@link FindBar}, as a page
 * in a browser can. JavaFX text nodes do neither on their own, so the view keeps its own
 * selection over the runs of text it drew - each paragraph, heading, cell and code block
 * a {@link Block} - and paints it, and the search's marks, as shapes behind the text.
 */
public final class MarkdownView extends ScrollPane {

    private static final List<Extension> EXTENSIONS = List.of(
            TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

    /** Between two paragraphs when copied; list items and table rows take one line break. */
    private static final String PARAGRAPH_BREAK = "\n\n";

    private final VBox body = new VBox(UiScale.GAP);

    /**
     * Repository-relative path to the file's bytes, for images; null for none. Called off
     * the FX thread: with the native engine every read is a git process.
     */
    private Function<String, byte[]> images;
    /**
     * Bytes already read, by resolved path; an empty array is a read that found nothing.
     * Kept while the source stays the same, so the re-render that follows typing does not
     * read every image again.
     */
    private final Map<String, byte[]> imageCache = new HashMap<>();
    /** Open {@code <b>}/{@code <strong>} and {@code <i>}/{@code <em>} tags in the paragraph being drawn. */
    private int htmlStrong;
    private int htmlEmphasis;

    /** One HTML tag, opening or closing: its slash, its name, and its attributes. */
    private static final Pattern TAG = Pattern.compile("<\\s*(/?)\\s*([a-zA-Z][a-zA-Z0-9]*)([^>]*)>");
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");
    private static final Pattern PIXELS = Pattern.compile("\\s*(\\d+(?:\\.\\d+)?)\\s*(?:px)?\\s*");
    /** Inline tags that only dress their text: dropped, the text kept. */
    private static final Set<String> FORMATTING = Set.of(
            "a", "b", "strong", "i", "em", "u", "s", "del", "sup", "sub", "span", "kbd", "code", "small", "mark");
    /** Block tags that only arrange the images inside them, as a README's centred header does. */
    private static final Set<String> IMAGE_WRAPPERS = Set.of("p", "div", "a", "picture", "source", "center", "span");
    /** The rendered file's own path, so a relative image or link is resolved against its folder. */
    private String documentPath = "";

    /** Every run of selectable text, in reading order. */
    private final List<Block> blocks = new ArrayList<>();
    /** The page's text as the search sees it: every block, joined by its separator. */
    private final StringBuilder document = new StringBuilder();
    /** What the next block registered is preceded by, when it is not a paragraph break. */
    private String nextSeparator;

    /** Where a selection started and where it has been dragged to; null for none. */
    private Position anchor;
    private Position caret;
    /** The search's marks, kept so a reflow can paint them again. */
    private List<Match> marks = List.of();
    private final List<Runnable> renderListeners = new ArrayList<>();
    private final FindBar.Surface surface = new PreviewSurface();

    public MarkdownView() {
        getStyleClass().add("markdown-view");
        body.getStyleClass().add("markdown-body");
        body.setPadding(new Insets(UiScale.EDGE, UiScale.SECTION, UiScale.SECTION, UiScale.SECTION));
        body.setCursor(Cursor.TEXT);
        setContent(body);
        setFitToWidth(true);
        setFocusTraversable(true);
        // A paragraph wraps at the view's width, less the body's own side padding.
        body.maxWidthProperty().bind(widthProperty());
        // The shapes are in each paragraph's own coordinates, and a narrower page moves
        // the line breaks under them.
        body.widthProperty().addListener((obs, old, width) -> Platform.runLater(this::repaint));

        body.addEventFilter(MouseEvent.MOUSE_PRESSED, this::onPress);
        body.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onDrag);
        addEventHandler(KeyEvent.KEY_PRESSED, this::onKey);
        setContextMenu(buildContextMenu());
    }

    /**
     * Where images are read from, for a source that touches no repository: its reads run
     * on the network lane.
     */
    public void setImageSource(Function<String, byte[]> images) {
        setImageSource(null, images);
    }

    /**
     * Where images in the repository are read from: a repository-relative path to its
     * bytes, read on {@code repository}'s lane - the repository {@code images} reads.
     */
    public void setImageSource(com.jfx.git.core.GitBackend repository, Function<String, byte[]> images) {
        if (images != this.images || repository != this.imageRepository) {
            this.images = images;
            this.imageRepository = repository;
            imageCache.clear();
        }
    }

    /** The repository {@link #images} reads, whose lane the reads run on; null for none. */
    private com.jfx.git.core.GitBackend imageRepository;

    /** Renders {@code markdown}, the content of the file at repository-relative {@code path}. */
    public void render(String markdown, String path) {
        String source = markdown == null ? "" : markdown;
        String where = path == null ? "" : path;
        // The page on screen already is this source, read the same way. The viewer redraws
        // the preview on every render that ends in File view - each display toggle - and
        // each one re-parsed the document, rebuilt every block and image slot, and threw
        // the reader back to the top of the page.
        if (rendered && renderedImages == images && where.equals(documentPath)
                && source.equals(renderedSource)) {
            return;
        }
        rendered = true;
        renderedSource = source;
        renderedImages = images;
        documentPath = where;
        body.getChildren().clear();
        blocks.clear();
        document.setLength(0);
        nextSeparator = null;
        anchor = null;
        caret = null;
        marks = List.of();
        org.commonmark.node.Node parsed = PARSER.parse(markdown == null ? "" : markdown);
        for (org.commonmark.node.Node block = parsed.getFirstChild(); block != null; block = block.getNext()) {
            Node rendered = block(block, 0);
            if (rendered != null) {
                body.getChildren().add(rendered);
            }
        }
        setVvalue(0);
        List.copyOf(renderListeners).forEach(Runnable::run);
    }

    /** What the page on screen was rendered from; see {@link #render}. */
    private boolean rendered;
    private String renderedSource;
    private Function<String, byte[]> renderedImages;

    /**
     * Forgets the source the page was rendered from, so the next {@link #render} draws
     * afresh and the old document's text is not held for the comparison.
     */
    public void forgetSource() {
        rendered = false;
        renderedSource = null;
        renderedImages = null;
    }

    /** Whether {@code path} names a Markdown file: what the preview is offered for. */
    public static boolean isMarkdown(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".mdown")
                || lower.endsWith(".mkd");
    }

    /** The page as something the find bar can search. */
    public FindBar.Surface findSurface() {
        return surface;
    }

    /** The rendered blocks, for tests. */
    VBox body() {
        return body;
    }

    // ------------------------------------------------------------------
    // Blocks
    // ------------------------------------------------------------------

    private Node block(org.commonmark.node.Node node, int depth) {
        if (node instanceof Heading heading) {
            TextFlow flow = inlines(heading);
            flow.getStyleClass().addAll("md-heading", "md-h" + heading.getLevel());
            // Spacing above a heading is the wrapper's, which also holds the rule under the
            // top two levels. Selection would cope with it on the flow itself: a flow's hit
            // testing and the shapes it draws both count its own padding.
            VBox wrapper = new VBox(UiScale.SNUG, flow);
            wrapper.getStyleClass().add("md-heading-block");
            if (heading.getLevel() <= 2) {
                // A rule under the two top levels, as GitHub draws them: the structure of
                // a long README is read from these.
                wrapper.getChildren().add(rule());
            }
            return wrapper;
        }
        if (node instanceof Paragraph paragraph) {
            return inlines(paragraph);
        }
        if (node instanceof BulletList || node instanceof OrderedList) {
            return list(node, depth);
        }
        if (node instanceof BlockQuote) {
            VBox quote = new VBox(UiScale.STEP);
            quote.getStyleClass().add("md-quote");
            for (org.commonmark.node.Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                Node rendered = block(child, depth);
                if (rendered != null) quote.getChildren().add(rendered);
            }
            return quote;
        }
        if (node instanceof FencedCodeBlock fenced) {
            return codeBlock(fenced.getLiteral());
        }
        if (node instanceof IndentedCodeBlock indented) {
            return codeBlock(indented.getLiteral());
        }
        if (node instanceof HtmlBlock html) {
            // Images and the tags that arrange them are drawn; anything else is shown, not
            // run: the source of the HTML, as a code block.
            Node images = htmlImages(html.getLiteral());
            return images != null ? images : codeBlock(html.getLiteral());
        }
        if (node instanceof ThematicBreak) {
            return rule();
        }
        if (node instanceof TableBlock table) {
            return table(table);
        }
        // Anything else a future CommonMark adds: its text, rather than nothing.
        TextFlow fallback = inlines(node);
        return fallback.getChildren().isEmpty() ? null : fallback;
    }

    private Node list(org.commonmark.node.Node list, int depth) {
        VBox items = new VBox(UiScale.SNUG);
        items.getStyleClass().add("md-list");
        int number = list instanceof OrderedList ordered && ordered.getMarkerStartNumber() != null
                ? ordered.getMarkerStartNumber() : 1;
        boolean first = true;
        for (org.commonmark.node.Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem)) continue;
            String marker = list instanceof OrderedList ? (number++) + "." : (depth % 2 == 0 ? "•" : "◦");
            Label bullet = new Label(marker);
            bullet.getStyleClass().add("md-list-marker");
            bullet.setMinWidth(UiScale.SECTION);
            VBox content = new VBox(UiScale.SNUG);
            // Copied, the items of a list are lines, not paragraphs.
            if (!first || depth > 0) nextSeparator = "\n";
            first = false;
            for (org.commonmark.node.Node child = item.getFirstChild(); child != null; child = child.getNext()) {
                Node rendered = block(child, depth + 1);
                if (rendered != null) content.getChildren().add(rendered);
            }
            HBox.setHgrow(content, Priority.ALWAYS);
            HBox row = new HBox(UiScale.SNUG, bullet, content);
            row.getStyleClass().add("md-list-item");
            items.getChildren().add(row);
        }
        return items;
    }

    private Node codeBlock(String literal) {
        String code = literal == null ? "" : literal.replaceAll("\\n$", "");
        Text text = new Text(code);
        text.getStyleClass().addAll("md-code-text", "t-mono");
        TextFlow flow = new TextFlow(text);
        register(flow);
        // The padding and the ground are the wrapper's; the flow is only the code.
        StackPane box = new StackPane(flow);
        box.getStyleClass().add("md-code-block");
        box.setPadding(new Insets(UiScale.GAP));
        StackPane.setAlignment(flow, javafx.geometry.Pos.TOP_LEFT);
        return box;
    }

    private static Region rule() {
        Region rule = new Region();
        rule.getStyleClass().add("md-rule");
        rule.setMinHeight(1);
        rule.setPrefHeight(1);
        rule.setMaxHeight(1);
        return rule;
    }

    private Node table(TableBlock table) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("md-table");
        int row = 0;
        for (org.commonmark.node.Node section = table.getFirstChild(); section != null; section = section.getNext()) {
            boolean head = section instanceof TableHead;
            if (!head && !(section instanceof TableBody)) continue;
            for (org.commonmark.node.Node tableRow = section.getFirstChild(); tableRow != null;
                 tableRow = tableRow.getNext()) {
                if (!(tableRow instanceof TableRow)) continue;
                int column = 0;
                for (org.commonmark.node.Node cell = tableRow.getFirstChild(); cell != null; cell = cell.getNext()) {
                    if (!(cell instanceof TableCell tableCell)) continue;
                    // Copied, a row is a line and its cells are separated by tabs - what a
                    // spreadsheet makes columns of when the copy is pasted into one.
                    if (column > 0) nextSeparator = "\t";
                    else if (row > 0) nextSeparator = "\n";
                    TextFlow content = inlines(tableCell);
                    if (tableCell.getAlignment() == TableCell.Alignment.RIGHT) {
                        content.setTextAlignment(javafx.scene.text.TextAlignment.RIGHT);
                    } else if (tableCell.getAlignment() == TableCell.Alignment.CENTER) {
                        content.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
                    }
                    StackPane box = new StackPane(content);
                    box.getStyleClass().add(head ? "md-table-head" : "md-table-cell");
                    box.setPadding(new Insets(UiScale.SNUG, UiScale.STEP, UiScale.SNUG, UiScale.STEP));
                    grid.add(box, column++, row);
                }
                row++;
            }
        }
        return grid;
    }

    // ------------------------------------------------------------------
    // Inlines
    // ------------------------------------------------------------------

    /** How the text inside some markup is drawn. */
    private record Style(boolean strong, boolean emphasis, boolean strike) {
        static final Style PLAIN = new Style(false, false, false);
    }

    private TextFlow inlines(org.commonmark.node.Node parent) {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("md-text");
        List<Node> nodes = new ArrayList<>();
        htmlStrong = 0;
        htmlEmphasis = 0;
        for (org.commonmark.node.Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            inline(child, Style.PLAIN, nodes);
        }
        flow.getChildren().setAll(nodes);
        if (!nodes.isEmpty()) register(flow);
        return flow;
    }

    private void inline(org.commonmark.node.Node node, Style style, List<Node> into) {
        if (node instanceof org.commonmark.node.Text text) {
            into.add(text(text.getLiteral(), new Style(style.strong() || htmlStrong > 0,
                    style.emphasis() || htmlEmphasis > 0, style.strike())));
        } else if (node instanceof Code code) {
            Text text = new Text(code.getLiteral());
            text.getStyleClass().addAll("md-inline-code", "t-mono");
            into.add(text);
        } else if (node instanceof Emphasis) {
            children(node, new Style(style.strong(), true, style.strike()), into);
        } else if (node instanceof StrongEmphasis) {
            children(node, new Style(true, style.emphasis(), style.strike()), into);
        } else if (node instanceof Strikethrough) {
            children(node, new Style(style.strong(), style.emphasis(), true), into);
        } else if (node instanceof SoftLineBreak) {
            into.add(text(" ", style));
        } else if (node instanceof HardLineBreak) {
            into.add(text("\n", style));
        } else if (node instanceof Link link) {
            into.add(link(link));
        } else if (node instanceof org.commonmark.node.Image image) {
            into.add(imageNode(image.getDestination(), plainText(image), 0, 0));
        } else if (node instanceof HtmlInline html) {
            inlineHtml(html.getLiteral(), style, into);
        } else {
            children(node, style, into);
        }
    }

    private void children(org.commonmark.node.Node parent, Style style, List<Node> into) {
        for (org.commonmark.node.Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            inline(child, style, into);
        }
    }

    private static Text text(String literal, Style style) {
        Text text = new Text(literal);
        text.getStyleClass().add("md-run");
        if (style.strong()) text.getStyleClass().add("md-strong");
        if (style.emphasis()) text.getStyleClass().add("md-em");
        if (style.strike()) text.setStrikethrough(true);
        return text;
    }

    /** All the plain text under {@code node}, for a link's label or an image's alt text. */
    private static String plainText(org.commonmark.node.Node node) {
        StringBuilder out = new StringBuilder();
        for (org.commonmark.node.Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof org.commonmark.node.Text text) out.append(text.getLiteral());
            else if (child instanceof Code code) out.append(code.getLiteral());
            else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) out.append(' ');
            else out.append(plainText(child));
        }
        return out.toString();
    }

    /**
     * A link: opened in the browser when it is a web or mail address. A link to another
     * file of the repository is shown with its target in a tooltip; following it would
     * mean opening that file here, which is a navigation this view does not own.
     */
    private Node link(Link link) {
        String label = plainText(link);
        String target = link.getDestination() == null ? "" : link.getDestination();
        Hyperlink hyperlink = new Hyperlink(label.isEmpty() ? target : label);
        hyperlink.getStyleClass().add("md-link");
        hyperlink.setTooltip(new Tooltip(target));
        if (isExternal(target)) {
            hyperlink.setOnAction(e -> com.jfx.git.core.UrlOpener.open(target));
        } else {
            hyperlink.setOnAction(e -> hyperlink.setVisited(false));
        }
        return hyperlink;
    }

    static boolean isExternal(String target) {
        String lower = target.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:");
    }

    /**
     * One inline HTML tag. An image is drawn; a line break breaks; the tags that only dress
     * text - a link around a badge, bold, a superscript - are dropped and their text kept,
     * with bold and italic still applied. Anything else is shown as the source it is.
     */
    private void inlineHtml(String literal, Style style, List<Node> into) {
        String tag = literal.trim();
        if (tag.startsWith("<!--")) {
            return; // a comment: invisible on GitHub, invisible here
        }
        Matcher matcher = TAG.matcher(tag);
        if (!matcher.matches()) {
            into.add(text(literal, style));
            return;
        }
        boolean closing = !matcher.group(1).isEmpty();
        String name = matcher.group(2).toLowerCase(Locale.ROOT);
        switch (name) {
            case "img" -> {
                Map<String, String> attributes = attributes(matcher.group(3));
                into.add(imageNode(attributes.get("src"), attributes.getOrDefault("alt", ""),
                        pixels(attributes.get("width")), pixels(attributes.get("height"))));
            }
            case "br" -> into.add(text("\n", style));
            case "b", "strong" -> htmlStrong = Math.max(0, htmlStrong + (closing ? -1 : 1));
            case "i", "em" -> htmlEmphasis = Math.max(0, htmlEmphasis + (closing ? -1 : 1));
            default -> {
                if (!FORMATTING.contains(name)) into.add(text(literal, style));
            }
        }
    }

    /**
     * An HTML block that holds only images and the tags arranging them - the centred logo
     * at the top of a README - as those images, in a row; {@code null} for any other HTML.
     */
    private Node htmlImages(String literal) {
        String html = literal.replaceAll("(?s)<!--.*?-->", "");
        List<Map<String, String>> found = new ArrayList<>();
        boolean centred = false;
        StringBuilder outside = new StringBuilder();
        Matcher matcher = TAG.matcher(html);
        int last = 0;
        while (matcher.find()) {
            outside.append(html, last, matcher.start());
            last = matcher.end();
            String name = matcher.group(2).toLowerCase(Locale.ROOT);
            Map<String, String> attributes = attributes(matcher.group(3));
            if (name.equals("img")) {
                found.add(attributes);
            } else if (!IMAGE_WRAPPERS.contains(name) && !name.equals("br")) {
                return null;
            }
            if (name.equals("center") || "center".equalsIgnoreCase(attributes.get("align"))) {
                centred = true;
            }
        }
        outside.append(html, last, html.length());
        if (found.isEmpty() || !outside.toString().replace("&nbsp;", " ").isBlank()) {
            return null;
        }
        TextFlow row = new TextFlow();
        row.getStyleClass().addAll("md-text", "md-image-row");
        for (int i = 0; i < found.size(); i++) {
            // A space between, so a row of badges wraps the way text does.
            if (i > 0) row.getChildren().add(text(" ", Style.PLAIN));
            Map<String, String> image = found.get(i);
            row.getChildren().add(imageNode(image.get("src"), image.getOrDefault("alt", ""),
                    pixels(image.get("width")), pixels(image.get("height"))));
        }
        if (centred) row.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        register(row);
        return row;
    }

    /** An HTML tag's attributes, names lower-cased: quoted either way, or bare. */
    static Map<String, String> attributes(String tagBody) {
        Map<String, String> attributes = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(tagBody == null ? "" : tagBody);
        while (matcher.find()) {
            String value = matcher.group(2) != null ? matcher.group(2)
                    : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            attributes.putIfAbsent(matcher.group(1).toLowerCase(Locale.ROOT), value);
        }
        return attributes;
    }

    /** A width or height in pixels; 0 for none, or for one this view cannot honour (a percentage). */
    static double pixels(String value) {
        if (value == null) return 0;
        Matcher matcher = PIXELS.matcher(value);
        return matcher.matches() ? Double.parseDouble(matcher.group(1)) : 0;
    }

    /**
     * An image of the repository's own, read through the image source; anything else - a
     * remote image, one that is missing - is its alt text, so the reader knows what is
     * not shown.
     *
     * <p>Read in the background: the slot goes into the text at once, holding its place,
     * and is filled when the bytes arrive. A slot rather than the image itself, because a
     * flow counts an embedded node as one character, and the selection is measured in them.
     *
     * @param width  the width asked for, or 0 for the image's own
     * @param height the height asked for, or 0; used only when no width is
     */
    private Node imageNode(String destination, String alt, double width, double height) {
        String target = destination == null ? "" : destination.trim();
        String shown = alt == null || alt.isEmpty() ? "image" : alt;
        String resolved = images == null || target.isEmpty() || isExternal(target) || target.startsWith("data:")
                ? null : resolve(documentPath, target);
        if (resolved == null) {
            Text placeholder = new Text("[" + shown + "]");
            placeholder.getStyleClass().addAll("md-run", "md-image-alt");
            return placeholder;
        }
        StackPane slot = new StackPane();
        slot.getStyleClass().add("md-image-slot");
        String tooltip = alt == null || alt.isEmpty() ? target : alt;
        byte[] cached = imageCache.get(resolved);
        if (cached != null) {
            fillImage(slot, cached, shown, tooltip, width, height);
            return slot;
        }
        Function<String, byte[]> source = images;
        java.util.function.Supplier<byte[]> read = () -> {
            try {
                byte[] bytes = source.apply(resolved);
                return bytes == null ? new byte[0] : bytes;
            } catch (RuntimeException unreadable) {
                return new byte[0];
            }
        };
        java.util.function.Consumer<byte[]> show = bytes -> {
            if (source == images) imageCache.put(resolved, bytes);
            fillImage(slot, bytes, shown, tooltip, width, height);
        };
        java.util.function.Consumer<String> failed = error -> fillImage(slot, new byte[0], shown, tooltip, width, height);
        if (imageRepository != null) {
            com.jfx.git.core.GitTaskRunner.query(imageRepository, read, show, failed);
        } else {
            com.jfx.git.core.GitTaskRunner.queryDetached(read, show, failed);
        }
        return slot;
    }

    private void fillImage(StackPane slot, byte[] bytes, String shown, String tooltip, double width, double height) {
        Image loaded = bytes.length == 0 ? null : new Image(new ByteArrayInputStream(bytes));
        if (loaded == null || loaded.isError() || loaded.getWidth() <= 0) {
            Label placeholder = new Label("[" + shown + "]");
            placeholder.getStyleClass().add("md-image-alt");
            slot.getChildren().setAll(placeholder);
            return;
        }
        double wanted = width > 0 ? width
                : height > 0 ? loaded.getWidth() * height / loaded.getHeight()
                : loaded.getWidth();
        ImageView view = new ImageView(loaded);
        view.setPreserveRatio(true);
        // As wide as asked - its own width unless the file says otherwise - but never wider
        // than the page.
        view.fitWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(() -> {
            double page = getWidth() - UiScale.SECTION * 3;
            return page > 0 ? Math.min(wanted, page) : wanted;
        }, widthProperty()));
        view.getStyleClass().add("md-image");
        Tooltip.install(view, new Tooltip(tooltip));
        slot.getChildren().setAll(view);
    }

    /**
     * {@code target} resolved against the folder of {@code document}, both repository-
     * relative with forward slashes; {@code null} for a target that climbs out of the
     * repository. A leading slash means the repository root, as GitHub reads it.
     */
    static String resolve(String document, String target) {
        String path = target;
        int cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        path = java.net.URLDecoder.decode(path.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        List<String> parts = new ArrayList<>();
        if (!path.startsWith("/") && document != null && document.contains("/")) {
            for (String part : document.substring(0, document.lastIndexOf('/')).split("/")) {
                if (!part.isEmpty()) parts.add(part);
            }
        }
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (parts.isEmpty()) return null;
                parts.remove(parts.size() - 1);
            } else {
                parts.add(part);
            }
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }

    // ------------------------------------------------------------------
    // The text, as runs that can be selected
    // ------------------------------------------------------------------

    /**
     * One run of text in a flow: a {@link Text}, which is as many characters in the flow's
     * layout as it has, or an embedded node - a link, an image - which the flow counts as
     * one character whatever it says.
     */
    private record Segment(int layoutStart, int layoutLength, int contentStart, String content, boolean embedded) {
        int contentEnd() {
            return contentStart + content.length();
        }
    }

    /** A paragraph, heading, cell or code block: one flow of text, selected as a unit of lines. */
    private static final class Block {
        final TextFlow flow;
        final String separator;
        final List<Segment> segments = new ArrayList<>();
        final String content;
        /** The flow's characters as its layout counts them; an embedded node is U+FFFC. */
        final String layoutText;
        final int docStart;
        Path selection;
        Path marks;

        Block(TextFlow flow, String separator, int docStart) {
            this.flow = flow;
            this.separator = separator;
            this.docStart = docStart;
            StringBuilder content = new StringBuilder();
            StringBuilder layout = new StringBuilder();
            for (Node child : flow.getChildren()) {
                if (!child.isManaged()) continue;
                String text;
                boolean embedded;
                if (child instanceof Text t) {
                    text = t.getText() == null ? "" : t.getText();
                    embedded = false;
                } else {
                    text = child instanceof Hyperlink link ? link.getText() : "";
                    embedded = true;
                }
                int layoutLength = embedded ? 1 : text.length();
                segments.add(new Segment(layout.length(), layoutLength, content.length(), text, embedded));
                content.append(text);
                layout.append(embedded ? "￼" : text);
            }
            this.content = content.toString();
            this.layoutText = layout.toString();
        }

        int layoutLength() {
            return layoutText.length();
        }

        /** The content offset at layout offset {@code at}. */
        int contentAt(int at) {
            for (Segment s : segments) {
                if (at < s.layoutStart() + s.layoutLength()) {
                    return s.embedded() ? s.contentStart() : s.contentStart() + Math.max(0, at - s.layoutStart());
                }
            }
            return content.length();
        }

        /** The layout offset a match starting at content offset {@code at} starts at. */
        int layoutStartOf(int at) {
            for (Segment s : segments) {
                if (at < s.contentEnd()) {
                    return s.embedded() ? s.layoutStart() : s.layoutStart() + Math.max(0, at - s.contentStart());
                }
            }
            return layoutLength();
        }

        /** The layout offset a match ending at content offset {@code at} ends at. */
        int layoutEndOf(int at) {
            if (at <= 0) return 0;
            for (Segment s : segments) {
                if (at > s.contentStart() && at <= s.contentEnd()) {
                    return s.embedded() ? s.layoutStart() + s.layoutLength() : s.layoutStart() + (at - s.contentStart());
                }
            }
            return layoutLength();
        }

        Path selectionPath() {
            if (selection == null) {
                selection = shape("md-selection");
                flow.getChildren().add(marks == null ? 0 : 1, selection);
            }
            return selection;
        }

        Path marksPath() {
            if (marks == null) {
                marks = shape("find-match");
                flow.getChildren().add(0, marks);
            }
            return marks;
        }

        /**
         * A shape painted behind the text. Unmanaged, so the flow leaves it out of the
         * text it lays out: a managed one would be a character of the paragraph.
         */
        private static Path shape(String styleClass) {
            Path path = new Path();
            path.setManaged(false);
            path.setMouseTransparent(true);
            path.getStyleClass().add(styleClass);
            return path;
        }
    }

    /** A place in the text: a block, and an offset in its layout. */
    record Position(int block, int offset) implements Comparable<Position> {
        @Override
        public int compareTo(Position other) {
            return block != other.block ? Integer.compare(block, other.block) : Integer.compare(offset, other.offset);
        }
    }

    private void register(TextFlow flow) {
        String separator = blocks.isEmpty() ? "" : nextSeparator != null ? nextSeparator : PARAGRAPH_BREAK;
        nextSeparator = null;
        document.append(separator);
        Block block = new Block(flow, separator, document.length());
        document.append(block.content);
        blocks.add(block);
    }

    /** The place under a point of the scene, or the nearest one to it; null with no text. */
    private Position positionAt(double sceneX, double sceneY) {
        if (blocks.isEmpty()) return null;
        Position beside = null;
        for (int i = 0; i < blocks.size(); i++) {
            TextFlow flow = blocks.get(i).flow;
            Bounds bounds = flow.localToScene(flow.getLayoutBounds());
            if (sceneY < bounds.getMinY()) {
                // Above this block, so in the gap before it: either the line beside the
                // point - a cell of the same table row, met earlier - or this block's start.
                return beside != null ? beside : new Position(i, 0);
            }
            if (sceneY <= bounds.getMaxY()) {
                Point2D local = flow.sceneToLocal(sceneX, sceneY);
                Position here = new Position(i, flow.getHitInfo(local).getInsertionIndex());
                if (sceneX >= bounds.getMinX() && sceneX <= bounds.getMaxX()) {
                    return here;
                }
                // The same height as the point but to one side: a table's other cells, or
                // the margin beside a paragraph. Kept in case nothing nearer turns up.
                if (beside == null || sceneX > bounds.getMaxX()) beside = here;
            }
        }
        if (beside != null) return beside;
        int last = blocks.size() - 1;
        return new Position(last, blocks.get(last).layoutLength());
    }

    private void onPress(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY || insideLink(e.getTarget())) {
            return;
        }
        requestFocus();
        Position at = positionAt(e.getSceneX(), e.getSceneY());
        if (at == null) return;
        if (e.getClickCount() == 2) {
            selectWordAt(at);
        } else if (e.getClickCount() >= 3) {
            Block block = blocks.get(at.block());
            select(new Position(at.block(), 0), new Position(at.block(), block.layoutLength()));
        } else if (e.isShiftDown() && anchor != null) {
            select(anchor, at);
        } else {
            select(at, at);
        }
    }

    private void onDrag(MouseEvent e) {
        if (!e.isPrimaryButtonDown() || anchor == null) return;
        // Dragged past the top or bottom of the view: the page comes along.
        Point2D inView = sceneToLocal(e.getSceneX(), e.getSceneY());
        double contentHeight = body.getHeight() - getViewportBounds().getHeight();
        if (contentHeight > 0 && inView != null) {
            if (inView.getY() < 0) {
                setVvalue(Math.max(0, getVvalue() - 20 / contentHeight));
            } else if (inView.getY() > getHeight()) {
                setVvalue(Math.min(1, getVvalue() + 20 / contentHeight));
            }
        }
        Position at = positionAt(e.getSceneX(), e.getSceneY());
        if (at != null) select(anchor, at);
    }

    private static boolean insideLink(Object target) {
        for (Node n = target instanceof Node node ? node : null; n != null; n = n.getParent()) {
            if (n instanceof Hyperlink) return true;
        }
        return false;
    }

    private void selectWordAt(Position at) {
        String text = blocks.get(at.block()).layoutText;
        if (text.isEmpty()) {
            select(at, at);
            return;
        }
        BreakIterator words = BreakIterator.getWordInstance();
        words.setText(text);
        int offset = Math.min(at.offset(), text.length() - 1);
        int end = words.following(offset);
        int start = words.previous();
        select(new Position(at.block(), start),
                new Position(at.block(), end == BreakIterator.DONE ? text.length() : end));
    }

    private void onKey(KeyEvent e) {
        if (!e.isShortcutDown() || e.isAltDown()) return;
        if (e.getCode() == KeyCode.C) {
            copy();
            e.consume();
        } else if (e.getCode() == KeyCode.A) {
            selectAll();
            e.consume();
        }
    }

    private ContextMenu buildContextMenu() {
        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(e -> copy());
        MenuItem all = new MenuItem("Select all");
        all.setOnAction(e -> selectAll());
        ContextMenu menu = new ContextMenu(copy, all);
        menu.setOnShowing(e -> copy.setDisable(selectedText().isEmpty()));
        return menu;
    }

    /** Selects from {@code from} to {@code to}, in either order, and paints it. */
    void select(Position from, Position to) {
        anchor = from;
        caret = to;
        paintSelection();
    }

    /** Selects every word on the page. */
    public void selectAll() {
        if (blocks.isEmpty()) return;
        int last = blocks.size() - 1;
        select(new Position(0, 0), new Position(last, blocks.get(last).layoutLength()));
    }

    /** What is selected, as plain text: paragraphs apart, list items and rows on lines, cells tab-separated. */
    public String selectedText() {
        if (anchor == null || caret == null || anchor.equals(caret)) return "";
        Position from = anchor.compareTo(caret) <= 0 ? anchor : caret;
        Position to = from == anchor ? caret : anchor;
        StringBuilder out = new StringBuilder();
        for (int i = from.block(); i <= to.block() && i < blocks.size(); i++) {
            Block block = blocks.get(i);
            int start = i == from.block() ? block.contentAt(from.offset()) : 0;
            int end = i == to.block() ? block.contentAt(to.offset()) : block.content.length();
            if (i > from.block()) out.append(block.separator);
            if (start < end) out.append(block.content, start, end);
        }
        return out.toString();
    }

    /** Puts the selection on the clipboard, if there is one. */
    public void copy() {
        String text = selectedText();
        if (text.isEmpty()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void paintSelection() {
        for (Block block : blocks) {
            if (block.selection != null) block.selection.getElements().clear();
        }
        if (anchor == null || caret == null || anchor.equals(caret)) return;
        Position from = anchor.compareTo(caret) <= 0 ? anchor : caret;
        Position to = from == anchor ? caret : anchor;
        for (int i = from.block(); i <= to.block() && i < blocks.size(); i++) {
            Block block = blocks.get(i);
            int start = i == from.block() ? from.offset() : 0;
            int end = i == to.block() ? to.offset() : block.layoutLength();
            if (start < end) block.selectionPath().getElements().setAll(block.flow.getRangeShape(start, end, false));
        }
    }

    private void paintMarks() {
        for (Block block : blocks) {
            if (block.marks != null) block.marks.getElements().clear();
        }
        for (Match match : marks) {
            forEachSpan(match.start(), match.end(), (block, start, end) ->
                    block.marksPath().getElements().addAll(block.flow.getRangeShape(start, end, false)));
        }
    }

    private void repaint() {
        paintSelection();
        paintMarks();
    }

    private interface Span {
        void accept(Block block, int layoutStart, int layoutEnd);
    }

    /** The pieces of the page's text from {@code start} to {@code end}, block by block, in layout offsets. */
    private void forEachSpan(int start, int end, Span span) {
        for (Block block : blocks) {
            int blockEnd = block.docStart + block.content.length();
            if (blockEnd <= start || block.docStart >= end) continue;
            int from = block.layoutStartOf(Math.max(0, start - block.docStart));
            int to = block.layoutEndOf(Math.min(block.content.length(), end - block.docStart));
            if (from < to) span.accept(block, from, to);
        }
    }

    /** Scrolls so that {@code at} is on screen, a third of the way down when it was not. */
    private void scrollTo(Position at) {
        Block block = blocks.get(at.block());
        double y = 0;
        if (block.layoutLength() > 0) {
            int offset = Math.min(at.offset(), block.layoutLength() - 1);
            // The top of the line the character is on.
            List<Rectangle2D> line = block.flow.getLayoutInfo().getSelectionGeometry(offset, offset + 1, false);
            if (!line.isEmpty()) y = line.get(0).getMinY();
        }
        Point2D inBody = body.sceneToLocal(block.flow.localToScene(0, y));
        if (inBody == null) return;
        double viewport = getViewportBounds().getHeight();
        double scrollable = body.getHeight() - viewport;
        if (scrollable <= 0) return;
        double top = getVvalue() * scrollable;
        if (inBody.getY() < top || inBody.getY() > top + viewport - UiScale.SECTION * 2) {
            setVvalue(Math.max(0, Math.min(1, (inBody.getY() - viewport / 3) / scrollable)));
        }
    }

    /** The page as the find bar searches it: read-only, its matches marked behind the text. */
    private final class PreviewSurface implements FindBar.Surface {
        @Override
        public String text() {
            return document.toString();
        }

        @Override
        public int anchor() {
            if (anchor == null || caret == null) return 0;
            Position from = anchor.compareTo(caret) <= 0 ? anchor : caret;
            Block block = blocks.get(from.block());
            return block.docStart + block.contentAt(from.offset());
        }

        @Override
        public void reveal(int start, int end) {
            List<Position> ends = new ArrayList<>();
            forEachSpan(start, end, (block, from, to) -> {
                int index = blocks.indexOf(block);
                if (ends.isEmpty()) ends.add(new Position(index, from));
                if (ends.size() > 1) ends.remove(1);
                ends.add(new Position(index, to));
            });
            if (ends.size() < 2) return;
            select(ends.get(0), ends.get(1));
            // Laid out first: a match found on a page drawn this very pulse has no
            // position to scroll to yet.
            layout();
            scrollTo(ends.get(0));
        }

        @Override
        public void mark(List<Match> matches) {
            marks = List.copyOf(matches);
            paintMarks();
        }

        @Override
        public void clearMarks() {
            marks = List.of();
            paintMarks();
        }

        @Override
        public String selectedText() {
            return MarkdownView.this.selectedText();
        }

        @Override
        public boolean isFocused() {
            return MarkdownView.this.isFocused();
        }

        @Override
        public void focus() {
            requestFocus();
        }

        @Override
        public Runnable watch(Runnable onChange) {
            renderListeners.add(onChange);
            return () -> renderListeners.remove(onChange);
        }
    }
}
