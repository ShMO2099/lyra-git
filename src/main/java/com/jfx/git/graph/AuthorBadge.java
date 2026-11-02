package com.jfx.git.graph;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * An author's initials on a coloured disc, drawn rather than rasterised.
 *
 * <h2>Why this replaced an image</h2>
 * The avatar used to be a bitmap: a small canvas was filled, lettered and snapshotted,
 * then shown in an {@code ImageView}. That is a fixed grid of pixels, and every way the
 * interface can grow stretches it — a high-density display, and now the zoom, which
 * multiplies whatever the bitmap was rendered at. The result was visibly blocky beside
 * text the toolkit had drawn at full resolution.
 *
 * <p>A rounded background and a label are scene-graph nodes. The toolkit renders them at
 * whatever resolution it is finally painting at, so they are sharp at 200% zoom on a 4K
 * display and equally sharp at 50%. There is nothing to rasterise, so there is also no
 * cache to size, no render scale to track, and nothing to invalidate when the theme
 * changes.
 *
 * <p>The initials and the hue still come from {@link Avatars}, so the colour a person is
 * given here is the same one the commit graph draws on their nodes.
 */
public final class AuthorBadge extends StackPane {

    private final Label initials = new Label();
    private final double size;

    public AuthorBadge(double size) {
        this.size = size;

        initials.setFont(Font.font("System", FontWeight.BOLD, size * 0.42));
        initials.setMouseTransparent(true);

        setAlignment(Pos.CENTER);
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        getChildren().add(initials);
        getStyleClass().add("author-badge");
    }

    /** Shows {@code name}'s initials on their own colour. */
    public void setAuthor(String name, String email) {
        Avatars.Author author = Avatars.author(name, email);
        Color hue = author.hue();

        // A corner radius of 50% is a circle while the box is square, and follows the
        // size without needing a Circle shape sized alongside it. One Background per hue,
        // shared: there are eight hues, and a new one per row per scroll tick was garbage
        // that also made every row's background compare unequal and repaint.
        setBackground(DISCS.computeIfAbsent(hue, c -> new Background(new BackgroundFill(
                c, new CornerRadii(50, true), Insets.EMPTY))));

        initials.setText(author.initials());
        initials.setTextFill(author.text());
    }

    /** The disc for each hue, built on first use. */
    private static final java.util.Map<Color, Background> DISCS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Leaves nothing drawn, for a row that has no author. */
    public void clearAuthor() {
        setBackground(null);
        initials.setText("");
    }

    /** The diameter this badge was built for. */
    public double diameter() {
        return size;
    }
}
