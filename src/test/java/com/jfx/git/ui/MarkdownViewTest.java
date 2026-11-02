package com.jfx.git.ui;

import com.jfx.git.core.GitResult;
import com.jfx.git.core.TextSearch;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Hyperlink;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Markdown file drawn as it reads, and the toggle that shows it. */
class MarkdownViewTest {

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @AfterEach
    void closeHarnessStages() {
        FxHarness.closeShownStages();
    }

    private static MarkdownView rendered(String markdown) {
        return FxHarness.call(() -> {
            MarkdownView view = new MarkdownView();
            view.render(markdown, "README.md");
            return view;
        });
    }

    private static <T extends Node> List<T> all(Node root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        return found;
    }

    private static <T extends Node> void collect(Node node, Class<T> type, List<T> into) {
        if (type.isInstance(node)) into.add(type.cast(node));
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collect(child, type, into);
        }
    }

    /** All the text under {@code node}, as a reader would read it. */
    private static String textOf(Node node) {
        StringBuilder out = new StringBuilder();
        for (Node n : all(node, Node.class)) {
            if (n instanceof Text text && !(n.getParent() instanceof javafx.scene.control.Labeled)
                    && !(n.getParent() != null && n.getParent().getParent() instanceof javafx.scene.control.Labeled)) {
                out.append(text.getText());
            } else if (n instanceof Hyperlink link) {
                out.append(link.getText());
            }
        }
        return out.toString();
    }

    private static boolean hasClass(Node node, String styleClass) {
        return node.getStyleClass().contains(styleClass);
    }

    private static List<Node> withClass(Node root, String styleClass) {
        return all(root, Node.class).stream().filter(n -> hasClass(n, styleClass)).toList();
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Test
    void headingsTakeTheirLevel() {
        MarkdownView view = rendered("# Title\n\n## Section\n\n### Detail\n");
        FxHarness.run(() -> {
            assertEquals(1, withClass(view.body(), "md-h1").size());
            assertEquals(1, withClass(view.body(), "md-h2").size());
            assertEquals(1, withClass(view.body(), "md-h3").size());
            assertEquals("Title", textOf(withClass(view.body(), "md-h1").get(0)));
            assertEquals(2, withClass(view.body(), "md-rule").size(),
                    "a rule under each of the top two levels, none under the third");
        });
    }

    @Test
    void inlineMarkupIsDrawnNotShown() {
        MarkdownView view = rendered("Some **bold**, *slanted*, ~~gone~~ and `code`.");
        FxHarness.run(() -> {
            String text = textOf(view.body());
            assertEquals("Some bold, slanted, gone and code.", text,
                    "no asterisks, tildes or backticks left in what is read");
            List<Text> runs = all(view.body(), Text.class);
            assertTrue(runs.stream().anyMatch(t -> t.getText().equals("bold") && hasClass(t, "md-strong")));
            assertTrue(runs.stream().anyMatch(t -> t.getText().equals("slanted") && hasClass(t, "md-em")));
            assertTrue(runs.stream().anyMatch(t -> t.getText().equals("gone") && t.isStrikethrough()));
            assertTrue(runs.stream().anyMatch(t -> t.getText().equals("code") && hasClass(t, "md-inline-code")));
        });
    }

    @Test
    void listsAreNumberedOrBulletedAndNest() {
        MarkdownView view = rendered("""
                3. three
                4. four
                   - inner
                   - also

                - one
                """);
        FxHarness.run(() -> {
            List<String> markers = withClass(view.body(), "md-list-marker").stream()
                    .map(n -> ((javafx.scene.control.Label) n).getText()).toList();
            assertEquals(List.of("3.", "4.", "◦", "◦", "•"), markers,
                    "numbering starts where the file says; a nested list has its own bullet");
        });
    }

    @Test
    void codeBlocksKeepTheirLinesAndQuotesTheirBar() {
        MarkdownView view = rendered("```java\nint a = 1;\n  int b;\n```\n\n> quoted\n");
        FxHarness.run(() -> {
            List<Node> code = withClass(view.body(), "md-code-text");
            assertEquals(1, code.size());
            assertEquals("int a = 1;\n  int b;", ((Text) code.get(0)).getText(),
                    "indentation and line breaks as written, no trailing blank line");
            assertEquals(1, withClass(view.body(), "md-quote").size());
            assertEquals("quoted", textOf(withClass(view.body(), "md-quote").get(0)));
        });
    }

    @Test
    void tablesAreGrids() {
        MarkdownView view = rendered("""
                | Name | Size |
                |------|-----:|
                | a    | 1    |
                | b    | 22   |
                """);
        FxHarness.run(() -> {
            List<GridPane> grids = all(view.body(), GridPane.class);
            assertEquals(1, grids.size());
            GridPane grid = grids.get(0);
            assertEquals(6, grid.getChildren().size(), "three rows of two cells");
            assertEquals(2, withClass(grid, "md-table-head").size());
            TextFlow size = all(grid.getChildren().get(5), TextFlow.class).get(0);
            assertEquals(javafx.scene.text.TextAlignment.RIGHT, size.getTextAlignment(),
                    "a column aligned right in the source is aligned right here");
        });
    }

    @Test
    void linksAreLinksAndBareAddressesToo() {
        MarkdownView view = rendered("See [the docs](https://example.com/docs) or https://example.org.");
        FxHarness.run(() -> {
            List<Hyperlink> links = all(view.body(), Hyperlink.class);
            assertEquals(List.of("the docs", "https://example.org"),
                    links.stream().map(Hyperlink::getText).toList());
            assertEquals("https://example.com/docs", links.get(0).getTooltip().getText());
        });
    }

    @Test
    void rawHtmlIsShownNotRun() {
        MarkdownView view = rendered("<div onclick=\"x()\">hi</div>\n");
        FxHarness.run(() -> assertTrue(textOf(view.body()).contains("<div onclick=\"x()\">hi</div>")));
    }

    @Test
    void anImageOfTheRepositoryIsShownAndARemoteOneIsItsAltText() throws Exception {
        byte[] png = png();
        MarkdownView view = FxHarness.call(() -> {
            MarkdownView v = new MarkdownView();
            Map<String, byte[]> files = Map.of("docs/img/logo.png", png);
            v.setImageSource(files::get);
            v.render("![Logo](img/logo.png)\n\n![Badge](https://example.com/b.svg)\n\n![Gone](missing.png)",
                    "docs/README.md");
            return v;
        });
        RobotWait.untilOnFx("the images were never read", () -> !all(view.body(), ImageView.class).isEmpty()
                && withClass(view.body(), "md-image-alt").size() == 2);
        FxHarness.run(() -> {
            List<ImageView> images = all(view.body(), ImageView.class);
            assertEquals(1, images.size(), "the repository's own image, resolved against the file's folder");
            assertEquals(2, images.get(0).getImage().getWidth());
            assertEquals(List.of("[Badge]", "[Gone]"), altTexts(view),
                    "nothing is fetched from the web, and a missing file is not an error");
        });
    }

    /** The alt texts standing in for images, in reading order: a Text, or a Label in a slot. */
    private static List<String> altTexts(MarkdownView view) {
        return withClass(view.body(), "md-image-alt").stream()
                .map(n -> n instanceof Text t ? t.getText() : ((javafx.scene.control.Label) n).getText())
                .toList();
    }

    private static MarkdownView withImages(String markdown, Map<String, byte[]> files) {
        return FxHarness.call(() -> {
            MarkdownView v = new MarkdownView();
            v.setImageSource(files::get);
            v.render(markdown, "README.md");
            return v;
        });
    }

    @Test
    void anHtmlImageTakesTheSizeItAsksFor() throws Exception {
        MarkdownView view = withImages("Logo: <img src=\"a.png\" width=\"10\"> here.", Map.of("a.png", png()));
        RobotWait.untilOnFx("the image never loaded", () -> !all(view.body(), ImageView.class).isEmpty());
        FxHarness.run(() -> {
            assertEquals(10, all(view.body(), ImageView.class).get(0).getFitWidth(), 0.01);
            assertFalse(textOf(view.body()).contains("<img"), "the tag is drawn, not shown");
        });
    }

    @Test
    void aCentredImageBlockIsAnImageNotCode() throws Exception {
        MarkdownView view = withImages("""
                <p align="center">
                  <img src="logo.png" alt="Logo" width="20">
                </p>

                # Project
                """, Map.of("logo.png", png()));
        RobotWait.untilOnFx("the image never loaded", () -> !all(view.body(), ImageView.class).isEmpty());
        FxHarness.run(() -> {
            assertTrue(withClass(view.body(), "md-code-block").isEmpty(), "no HTML shown as source");
            TextFlow row = (TextFlow) withClass(view.body(), "md-image-row").get(0);
            assertEquals(javafx.scene.text.TextAlignment.CENTER, row.getTextAlignment());
            assertEquals(1, withClass(view.body(), "md-h1").size(), "and the Markdown after it still renders");
        });
    }

    @Test
    void htmlWithTextInItStaysSource() {
        MarkdownView view = rendered("<div align=\"center\">Hello <img src=\"a.png\"></div>\n");
        FxHarness.run(() -> assertEquals(1, withClass(view.body(), "md-code-block").size(),
                "text in HTML is more than this view draws, so it is shown as written"));
    }

    @Test
    void aLinkedBadgeShowsNoTags() {
        MarkdownView view = rendered("Build: <a href=\"https://ci\"><img src=\"https://ci/badge.svg\" alt=\"CI\"></a> ok");
        FxHarness.run(() -> {
            String text = textOf(view.body());
            assertEquals("Build: [CI] ok", text, "the link's tags go, a remote badge is its alt text");
        });
    }

    @Test
    void boldAndItalicTagsStyleTheirTextAndBreaksBreak() {
        MarkdownView view = rendered("a <b>bold</b> <em>slanted</em><br>next");
        FxHarness.run(() -> {
            assertEquals("a bold slanted\nnext", textOf(view.body()));
            List<Text> runs = all(view.body(), Text.class);
            assertTrue(runs.stream().anyMatch(t -> "bold".equals(t.getText()) && hasClass(t, "md-strong")));
            assertTrue(runs.stream().anyMatch(t -> "slanted".equals(t.getText()) && hasClass(t, "md-em")));
            assertTrue(runs.stream().noneMatch(t -> "next".equals(t.getText()) && hasClass(t, "md-strong")),
                    "a closed tag stops styling");
        });
    }

    @Test
    void imagesAreReadOnceWhileTheSourceStaysTheSame() throws Exception {
        byte[] png = png();
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Function<String, byte[]> counting = path -> {
            reads.incrementAndGet();
            return png;
        };
        MarkdownView view = FxHarness.call(() -> {
            MarkdownView v = new MarkdownView();
            v.setImageSource(counting);
            v.render("![a](a.png)", "README.md");
            return v;
        });
        RobotWait.untilOnFx("the image never loaded", () -> !all(view.body(), ImageView.class).isEmpty());
        FxHarness.run(() -> {
            view.setImageSource(counting);
            view.render("# Typed\n\n![a](a.png)", "README.md");
        });
        RobotWait.untilOnFx("the image never came back", () -> !all(view.body(), ImageView.class).isEmpty());
        assertEquals(1, reads.get(), "typing re-renders the page; it does not read every image again");
    }

    @Test
    void attributesAndSizesAreReadTheWayHtmlWritesThem() {
        assertEquals(Map.of("src", "a b.png", "alt", "x", "width", "20"),
                MarkdownView.attributes(" SRC=\"a b.png\" alt='x' width=20 /"));
        assertEquals(20, MarkdownView.pixels("20"), 0.01);
        assertEquals(20, MarkdownView.pixels("20px"), 0.01);
        assertEquals(0, MarkdownView.pixels("50%"), 0.01, "a percentage is not something this view can honour");
        assertEquals(0, MarkdownView.pixels(null), 0.01);
    }

    @Test
    void thePreviewReadsImagesFromTheRevisionOnScreen() throws Exception {
        byte[] small = png(2);
        byte[] large = png(6);
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            javafx.stage.Stage stage = FxHarness.show(v, 900, 600);
            v.setOnSaveFile((p, t, onResult) -> onResult.accept(com.jfx.git.core.GitResult.ok("saved")));
            v.setFileContentSupplier(path -> path.equals("README.md") ? "![a](a.png)\n".getBytes() : small);
            v.setRevisionReader(path -> large);
            v.setFileDiffBytes("README.md", "@@ -1 +1 @@\n-x\n+![a](a.png)\n".getBytes(),
                    "![a](a.png)\n".getBytes());
            stage.setUserData("keep-alive");
            v.previewToggle().fire();
            return v;
        });
        RobotWait.untilOnFx("the commit's image never showed", () -> viewer.markdownView() != null
                && all(viewer.markdownView().body(), ImageView.class).stream()
                        .anyMatch(i -> i.getImage().getWidth() == 6));
        FxHarness.run(viewer::beginEditing);
        RobotWait.untilOnFx("editing should draw the working copy's image", () ->
                all(viewer.markdownView().body(), ImageView.class).stream()
                        .anyMatch(i -> i.getImage().getWidth() == 2));
    }

    private static byte[] png() throws Exception {
        return png(2);
    }

    private static byte[] png(int size) throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(size, size,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void relativePathsResolveInsideTheRepository() {
        assertEquals("docs/img/a.png", MarkdownView.resolve("docs/README.md", "img/a.png"));
        assertEquals("docs/img/a.png", MarkdownView.resolve("docs/README.md", "./img/a.png"));
        assertEquals("img/a.png", MarkdownView.resolve("docs/README.md", "../img/a.png"));
        assertEquals("img/a.png", MarkdownView.resolve("docs/README.md", "/img/a.png"),
                "a leading slash is the repository root");
        assertEquals("a b.png", MarkdownView.resolve("README.md", "a%20b.png"));
        assertEquals("a.png", MarkdownView.resolve("README.md", "a.png?raw=true#x"));
        assertNull(MarkdownView.resolve("README.md", "../outside.png"),
                "nothing above the repository is read");
    }

    @Test
    void whatCountsAsMarkdownAndAsExternal() {
        assertTrue(MarkdownView.isMarkdown("README.md"));
        assertTrue(MarkdownView.isMarkdown("docs/Guide.MARKDOWN"));
        assertFalse(MarkdownView.isMarkdown("notes.txt"));
        assertFalse(MarkdownView.isMarkdown(null));
        assertTrue(MarkdownView.isExternal("HTTPS://x"));
        assertTrue(MarkdownView.isExternal("mailto:a@b"));
        assertFalse(MarkdownView.isExternal("docs/other.md"));
        assertFalse(MarkdownView.isExternal("javascript:alert(1)"));
    }

    // ------------------------------------------------------------------
    // The toggle in the viewer
    // ------------------------------------------------------------------

    private static final String README = "# Project\n\nSome *text*.\n";
    private static final String README_DIFF = "@@ -1,3 +1,3 @@\n # Project\n \n-Old.\n+Some *text*.\n";

    private static DiffViewer viewerOn(String path, boolean editable) {
        return FxHarness.call(() -> {
            DiffViewer viewer = new DiffViewer();
            Stage stage = FxHarness.show(viewer, 900, 600);
            if (editable) {
                viewer.setOnSaveFile((p, t, onResult) -> onResult.accept(GitResult.ok("saved")));
            }
            viewer.setFileDiffBytes(path, README_DIFF.getBytes(), README.getBytes());
            stage.setUserData("keep-alive");
            FxHarness.pulse(viewer);
            return viewer;
        });
    }

    private static javafx.scene.control.ToggleButton segment(DiffViewer viewer, String label) {
        for (Node node : FxHarness.lookupAll(viewer, "diff-segment")) {
            if (node instanceof javafx.scene.control.ToggleButton b && label.equals(b.getText())) {
                return b;
            }
        }
        throw new AssertionError("no segment labelled " + label);
    }

    private static void fileView(DiffViewer viewer) {
        segment(viewer, "File view").fire();
        FxHarness.pulse(viewer);
    }

    @Test
    void previewSitsBesideFileViewForMarkdownOnly() {
        DiffViewer markdown = viewerOn("docs/README.md", false);
        FxHarness.run(() -> {
            assertTrue(markdown.previewToggle().isVisible(), "offered from the diff as well");
            javafx.scene.Parent pair = segment(markdown, "File view").getParent();
            assertEquals(List.of("File view", "Preview", "Diff view"), pair.getChildrenUnmodifiable().stream()
                    .map(n -> ((javafx.scene.control.ToggleButton) n).getText()).toList(),
                    "one switch of three ways to look at the file");
            fileView(markdown);
            assertTrue(markdown.previewToggle().isVisible());
        });
        DiffViewer java = viewerOn("src/App.java", false);
        FxHarness.run(() -> {
            assertFalse(java.previewToggle().isVisible(), "and only for a Markdown file");
            fileView(java);
            assertFalse(java.previewToggle().isVisible());
        });
    }

    @Test
    void previewFromTheDiffGoesStraightToThePage() {
        DiffViewer viewer = viewerOn("README.md", false);
        FxHarness.run(() -> {
            assertFalse(viewer.isFileView());
            viewer.previewToggle().fire();
            FxHarness.pulse(viewer);
            assertTrue(viewer.isFileView(), "the whole file, not the diff");
            assertTrue(viewer.isPreviewing(), "rendered, in one click");
            assertTrue(viewer.previewToggle().isSelected());
            assertFalse(segment(viewer, "File view").isSelected(), "the switch says Preview, nothing else");
            assertEquals("Project", textOf(withClass(viewer.markdownView().body(), "md-h1").get(0)));
        });
    }

    @Test
    void fileViewIsTheSourceAndLeavesThePreview() {
        DiffViewer viewer = viewerOn("README.md", false);
        FxHarness.run(() -> {
            viewer.previewToggle().fire();
            FxHarness.pulse(viewer);
            assertTrue(viewer.isPreviewing());
            fileView(viewer);
            assertFalse(viewer.isPreviewing(), "File view is the source, as it always was");
            assertTrue(viewer.isFileView());
            assertTrue(segment(viewer, "File view").isSelected());
        });
    }

    @Test
    void diffViewLeavesThePreview() {
        DiffViewer viewer = viewerOn("README.md", false);
        FxHarness.run(() -> {
            viewer.previewToggle().fire();
            FxHarness.pulse(viewer);
            segment(viewer, "Diff view").fire();
            FxHarness.pulse(viewer);
            assertFalse(viewer.isPreviewing(), "the diff is shown as a diff");
            assertFalse(viewer.isFileView());
            assertTrue(viewer.previewToggle().isVisible(), "and Preview is still one click away");
            assertFalse(viewer.previewToggle().isSelected());
        });
    }

    @Test
    void findSearchesThePageAsItReads() {
        DiffViewer viewer = viewerOn("README.md", false);
        FxHarness.run(() -> {
            fileView(viewer);
            viewer.previewToggle().fire();
            viewer.showFind(false);
            assertTrue(viewer.isPreviewing(), "Ctrl+F stays on the page");
            FindBar bar = viewer.findBar();
            bar.queryField().setText("*text*");
            assertTrue(bar.hits().isEmpty(), "the markup is not on the page, so it is not found");
            bar.queryField().setText("text");
            assertEquals(1, bar.hits().size());
            assertEquals(1, bar.highlightCount(), "marked on the page");
            assertEquals("text", viewer.markdownView().selectedText(), "and selected, as in the source");

            fileView(viewer);
            assertFalse(viewer.isPreviewing());
            assertEquals(1, bar.hits().size(), "the open search follows back to the source");
            assertTrue(withClass(viewer.markdownView().body(), "find-match").stream()
                            .allMatch(n -> ((javafx.scene.shape.Path) n).getElements().isEmpty()),
                    "the page keeps no marks it is not showing");
        });
    }

    @Test
    void replacingWhileEditingGoesBackToTheSource() {
        DiffViewer viewer = viewerOn("README.md", true);
        FxHarness.run(() -> {
            viewer.beginEditing();
            viewer.previewToggle().fire();
            assertTrue(viewer.isPreviewing());
            viewer.showFind(true);
            assertFalse(viewer.isPreviewing(), "replacing is typing, and typing is in the source");
            assertTrue(viewer.findBar().replaceShown());
        });
    }

    // ------------------------------------------------------------------
    // Selecting and copying
    // ------------------------------------------------------------------

    private static final String PAGE = """
            # Title

            Some **bold** text with [a link](https://example.com).

            - one
            - two

            | A | B |
            |---|---|
            | 1 | 2 |
            """;

    private static MarkdownView shown(String markdown) {
        return FxHarness.call(() -> {
            MarkdownView view = new MarkdownView();
            FxHarness.show(view, 700, 500);
            view.render(markdown, "README.md");
            FxHarness.pulse(view);
            return view;
        });
    }

    @Test
    void selectAllCopiesThePageAsPlainText() {
        MarkdownView view = rendered(PAGE);
        FxHarness.run(() -> {
            view.selectAll();
            assertEquals("Title\n\nSome bold text with a link.\n\none\ntwo\n\nA\tB\n1\t2", view.selectedText(),
                    "paragraphs apart, list items on lines, table cells tab-separated, no markup");
        });
    }

    @Test
    void ctrlASelectsAndNothingSelectedCopiesNothing() {
        MarkdownView view = shown(PAGE);
        FxHarness.run(() -> {
            assertEquals("", view.selectedText());
            view.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                    javafx.scene.input.KeyCode.A, false, true, false, false));
            assertTrue(view.selectedText().startsWith("Title"), view.selectedText());
            assertFalse(withClass(view.body(), "md-selection").isEmpty(), "and the selection is painted");
        });
    }

    private static javafx.scene.input.MouseEvent mouse(javafx.event.EventType<javafx.scene.input.MouseEvent> type,
                                                       javafx.geometry.Point2D scene, int clicks) {
        return new javafx.scene.input.MouseEvent(type, scene.getX(), scene.getY(), scene.getX(), scene.getY(),
                javafx.scene.input.MouseButton.PRIMARY, clicks, false, false, false, false,
                true, false, false, false, false, false, null);
    }

    private static javafx.geometry.Point2D sceneCentre(Node node) {
        javafx.geometry.Bounds b = node.localToScene(node.getBoundsInLocal());
        return new javafx.geometry.Point2D(b.getCenterX(), b.getCenterY());
    }

    @Test
    void draggingSelectsFromWhereThePressWasToWhereThePointerIs() {
        MarkdownView view = shown(PAGE);
        FxHarness.run(() -> {
            Node title = withClass(view.body(), "md-h1").get(0);
            javafx.geometry.Bounds t = title.localToScene(title.getLayoutBounds());
            javafx.geometry.Point2D start = new javafx.geometry.Point2D(t.getMinX() + 1, t.getCenterY());
            Text bold = all(view.body(), Text.class).stream()
                    .filter(x -> "bold".equals(x.getText())).findFirst().orElseThrow();
            javafx.geometry.Bounds b = bold.localToScene(bold.getBoundsInLocal());
            javafx.geometry.Point2D end = new javafx.geometry.Point2D(b.getMaxX() - 1, b.getCenterY());

            javafx.event.Event.fireEvent(view.body(), mouse(javafx.scene.input.MouseEvent.MOUSE_PRESSED, start, 1));
            javafx.event.Event.fireEvent(view.body(), mouse(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, end, 1));
            String selected = view.selectedText();
            assertTrue(selected.startsWith("Title\n\nSome bo"), selected);
            assertFalse(selected.contains("text"), "and no further than the pointer: " + selected);
        });
    }

    @Test
    void doubleClickSelectsAWord() {
        MarkdownView view = shown(PAGE);
        FxHarness.run(() -> {
            Text bold = all(view.body(), Text.class).stream()
                    .filter(x -> "bold".equals(x.getText())).findFirst().orElseThrow();
            javafx.event.Event.fireEvent(view.body(),
                    mouse(javafx.scene.input.MouseEvent.MOUSE_PRESSED, sceneCentre(bold), 2));
            assertEquals("bold", view.selectedText());
        });
    }

    @Test
    void aPressOnALinkIsLeftToTheLink() {
        MarkdownView view = shown(PAGE);
        FxHarness.run(() -> {
            view.selectAll();
            Hyperlink link = all(view.body(), Hyperlink.class).get(0);
            javafx.event.Event.fireEvent(link, mouse(javafx.scene.input.MouseEvent.MOUSE_PRESSED, sceneCentre(link), 1));
            assertFalse(view.selectedText().isEmpty(), "clicking a link follows it; it does not start a selection");
        });
    }

    // A text flow with padding of its own. The page never gives one any, but a flow's hit
    // testing and its range shapes used to leave the padding out, so a click or a selection
    // in a padded one landed a padding's width away from the glyphs it was meant for.

    /** Different on every side, so a shift along either axis shows. */
    private static final javafx.geometry.Insets FLOW_PADDING = new javafx.geometry.Insets(11, 23, 7, 29);

    private static Text boldText(MarkdownView view) {
        return all(view.body(), Text.class).stream()
                .filter(x -> "bold".equals(x.getText())).findFirst().orElseThrow();
    }

    /** {@link #PAGE}, shown, with the flow of its paragraph padded and laid out again. */
    private static MarkdownView shownWithPaddedParagraph() {
        MarkdownView view = shown(PAGE);
        FxHarness.run(() -> {
            TextFlow paragraph = (TextFlow) boldText(view).getParent();
            paragraph.setPadding(FLOW_PADDING);
            FxHarness.pulse(view);
            assertEquals(FLOW_PADDING.getLeft(), paragraph.getInsets().getLeft(), "the padding is the flow's own");
        });
        return view;
    }

    @Test
    void aPaddedFlowMapsAClickToTheCharacterUnderIt() {
        MarkdownView view = shownWithPaddedParagraph();
        FxHarness.run(() -> {
            javafx.event.Event.fireEvent(view.body(),
                    mouse(javafx.scene.input.MouseEvent.MOUSE_PRESSED, sceneCentre(boldText(view)), 2));
            assertEquals("bold", view.selectedText(),
                    "the word under the pointer, not the one the padding's width further along");
        });
    }

    @Test
    void draggingAcrossAPaddedFlowSelectsFromGlyphToGlyph() {
        MarkdownView view = shownWithPaddedParagraph();
        FxHarness.run(() -> {
            Text bold = boldText(view);
            Text first = (Text) ((TextFlow) bold.getParent()).getChildren().get(0);
            javafx.geometry.Bounds f = first.localToScene(first.getBoundsInLocal());
            javafx.geometry.Bounds b = bold.localToScene(bold.getBoundsInLocal());
            javafx.event.Event.fireEvent(view.body(), mouse(javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                    new javafx.geometry.Point2D(f.getMinX() + 1, f.getCenterY()), 1));
            javafx.event.Event.fireEvent(view.body(), mouse(javafx.scene.input.MouseEvent.MOUSE_DRAGGED,
                    new javafx.geometry.Point2D(b.getMaxX() - 1, b.getCenterY()), 1));
            assertEquals("Some bold", view.selectedText(),
                    "from the first glyph to the last one under the pointer");
        });
    }

    /** The box on screen that the {@code styleClass} shape of {@code flow} paints, edge to edge. */
    private static javafx.geometry.Bounds paintedBy(TextFlow flow, String styleClass) {
        Path path = (Path) withClass(flow, styleClass).get(0);
        assertFalse(path.getElements().isEmpty(), styleClass + " is painted");
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (PathElement element : path.getElements()) {
            double x;
            double y;
            if (element instanceof MoveTo move) {
                x = move.getX();
                y = move.getY();
            } else if (element instanceof LineTo line) {
                x = line.getX();
                y = line.getY();
            } else {
                continue;
            }
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        return path.localToScene(new javafx.geometry.BoundingBox(minX, minY, maxX - minX, maxY - minY));
    }

    /** {@code painted} is the box of {@code glyphs} on screen, to the pixel. */
    private static void assertOver(Text glyphs, javafx.geometry.Bounds painted, String what) {
        javafx.geometry.Bounds text = glyphs.localToScene(glyphs.getLayoutBounds());
        assertEquals(text.getMinX(), painted.getMinX(), 1.0, what + ", left edge");
        assertEquals(text.getMaxX(), painted.getMaxX(), 1.0, what + ", right edge");
        assertEquals(text.getMinY(), painted.getMinY(), 1.0, what + ", top");
        assertEquals(text.getMaxY(), painted.getMaxY(), 1.0, what + ", bottom");
    }

    @Test
    void aPaddedFlowPaintsItsSelectionAndMarksOverTheGlyphs() {
        MarkdownView view = shownWithPaddedParagraph();
        FxHarness.run(() -> {
            Text bold = boldText(view);
            TextFlow paragraph = (TextFlow) bold.getParent();

            // "Some " is five characters of the paragraph, the second block of the page.
            view.select(new MarkdownView.Position(1, 5), new MarkdownView.Position(1, 9));
            assertEquals("bold", view.selectedText());
            assertOver(bold, paintedBy(paragraph, "md-selection"), "the selection");

            int at = view.findSurface().text().indexOf("bold");
            view.findSurface().mark(List.of(new TextSearch.Match(at, at + "bold".length())));
            assertOver(bold, paintedBy(paragraph, "find-match"), "the find mark");
        });
    }

    @Test
    void revealingAMatchScrollsToItsLineNotJustItsParagraph() {
        // One paragraph many lines tall, the match on the last of them.
        MarkdownView view = shown("Intro.\n\n" + "word ".repeat(1200) + "needle\n");
        FxHarness.run(() -> {
            assertEquals(0.0, view.getVvalue(), "the page opens at the top");
            int at = view.findSurface().text().indexOf("needle");
            view.findSurface().reveal(at, at + "needle".length());
            FxHarness.pulse(view);
            assertTrue(view.getVvalue() > 0.0, "the page scrolled");
            Path selection = (Path) withClass(view.body(), "md-selection").stream()
                    .filter(n -> !((Path) n).getElements().isEmpty()).findFirst().orElseThrow();
            javafx.geometry.Bounds line = paintedBy((TextFlow) selection.getParent(), "md-selection");
            javafx.geometry.Bounds page = view.localToScene(view.getLayoutBounds());
            assertTrue(line.getMinY() >= page.getMinY() && line.getMaxY() <= page.getMaxY(),
                    "the matched line is on screen: " + line + " in " + page);
        });
    }

    @Test
    void whileEditingThePreviewFollowsTheTyping() {
        DiffViewer viewer = viewerOn("README.md", true);
        FxHarness.run(() -> {
            viewer.beginEditing();
            assertTrue(viewer.isEditing());
            viewer.previewToggle().fire();
            assertTrue(viewer.isPreviewing());
            viewer.replaceBuffer("# Renamed\n");
        });
        RobotWait.untilOnFx("the preview never caught up with the typing",
                () -> withClass(viewer.markdownView().body(), "md-h1").stream()
                        .anyMatch(h -> textOf(h).equals("Renamed")));
    }
}
