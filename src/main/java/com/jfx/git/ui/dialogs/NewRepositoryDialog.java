package com.jfx.git.ui.dialogs;

import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitService;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.RepoInitTemplates;
import com.jfx.git.ui.Icons;

import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;

import java.io.File;
import java.util.Locale;
import java.util.function.Function;

/**
 * Creates a repository with {@code git init}, optionally seeded with a {@code .gitignore},
 * a LICENSE and a README, then opens it.
 *
 * <p>One form, two doors. "New repository" from the menu or the welcome page arrives
 * here having already chosen a folder. Opening a folder that turns out not to be a
 * repository arrives here too - the open is refused anyway, and the useful answer to
 * "this is not a repository" is the offer to make it one, with the same starter files
 * on the way in, rather than an error dialog that sends the user back to the menu.
 *
 * <p>Nothing is committed. The repository opens on "No commits yet" with the files the
 * user chose waiting unstaged in the commit panel, the same way
 * {@link GitService#initRepository} has always left them: a first commit is the user's
 * to review and make, not something that appears on their behalf.
 *
 * <p>Its own class rather than a method on the application so the flow can be driven in
 * a test, with the init itself replaceable - the package-private constructor takes the
 * function that creates the repository.
 */
public final class NewRepositoryDialog {

    /** Which door the user came through; decides the wording, not the form. */
    public enum Purpose {
        /** "New repository": the folder was chosen for this. */
        CREATE,
        /** The folder was opened as a repository and is not one. */
        NOT_A_REPOSITORY
    }

    /** The confirming button when the folder was opened by mistake. */
    public static final String INITIALISE = "Initialise and open";
    /** The confirming button when the folder was chosen for a new repository. */
    public static final String CREATE = "Create";

    /** The form's parts, for tests. */
    public static final String FOLDER_ID = "init-folder";
    public static final String GITIGNORE_ID = "init-gitignore";
    public static final String GITIGNORE_FILTER_ID = "init-gitignore-filter";
    public static final String LICENSE_ID = "init-license";
    public static final String HOLDER_ID = "init-copyright-holder";
    public static final String README_ID = "init-readme";

    private final File dir;
    private final Purpose purpose;
    private final Function<RepoInitTemplates, GitResult> init;
    private final Runnable open;

    /**
     * @param dir     the folder to turn into a repository
     * @param purpose how the user got here; see {@link Purpose}
     * @param open    opens {@code dir} once it is a repository; runs on the FX thread
     */
    public NewRepositoryDialog(File dir, Purpose purpose, Runnable open) {
        this(dir, purpose, templates -> com.jfx.git.core.GitBackendFactory.initRepository(dir, templates), open);
    }

    /**
     * @param init creates the repository with the chosen starter files; runs on a worker.
     *             Tests pass one that records what was asked for.
     */
    NewRepositoryDialog(File dir, Purpose purpose, Function<RepoInitTemplates, GitResult> init, Runnable open) {
        this.dir = dir;
        this.purpose = purpose;
        this.init = init;
        this.open = open;
    }

    /** The header the form opens with, for tests. */
    static String headerFor(File dir, Purpose purpose) {
        return purpose == Purpose.CREATE
                ? "Create a repository in " + dir.getName()
                : "Make '" + dir.getName() + "' a Git repository?";
    }

    /** The line under it: where, or why this is being asked. */
    private static String subtitleFor(File dir, Purpose purpose) {
        return purpose == Purpose.CREATE
                ? "Starter files are optional"
                : "It is not one yet, so it cannot be opened";
    }

    /**
     * Shows the form and waits for it. The init runs on a worker - it writes files - and
     * the folder is opened on success.
     */
    public void show() {
        ButtonType confirm = new ButtonType(
                purpose == Purpose.CREATE ? CREATE : INITIALISE, ButtonBar.ButtonData.OK_DONE);
        Dialog<RepoInitTemplates> dialog = new Dialog<>();
        dialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);
        JfxDialog.header(dialog, purpose == Purpose.CREATE ? Icons.create() : Icons.folder(),
                JfxDialog.Tone.NEUTRAL, headerFor(dir, purpose), subtitleFor(dir, purpose)).ruled();

        // The folder as a path, in monospace like any path: where the files will be
        // written, and the one thing on the form not chosen here.
        Label path = new Label(dir.getAbsolutePath());
        path.setId(FOLDER_ID);
        path.getStyleClass().add("t-mono");
        path.setWrapText(true);
        path.setMinHeight(Region.USE_PREF_SIZE);
        DialogParts.Field folderField = DialogParts.field("Folder", path)
                .hint("Nothing is committed: the files you choose below are written to the folder "
                        + "and left for you to review in the commit panel.");

        // Thirty-odd templates is past what a drop-down is for; typing narrows it.
        FilteredList<String> offered = new FilteredList<>(
                FXCollections.observableArrayList(RepoInitTemplates.gitignoreTemplateNames()));
        ComboBox<String> gitignore = new ComboBox<>(offered);
        gitignore.setId(GITIGNORE_ID);
        gitignore.getSelectionModel().selectFirst();
        TextField gitignoreFilter = new TextField();
        gitignoreFilter.setId(GITIGNORE_FILTER_ID);
        gitignoreFilter.setPromptText("Filter");
        gitignoreFilter.setPrefColumnCount(9);
        gitignoreFilter.textProperty().addListener((obs, old, text) -> {
            String wanted = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
            // "None" stays whatever is typed: not wanting one is always an answer.
            offered.setPredicate(name -> wanted.isEmpty() || name.equals(RepoInitTemplates.NONE)
                    || name.toLowerCase(Locale.ROOT).contains(wanted));
            // Typing a language's name is choosing it: take the first real match.
            gitignore.getSelectionModel().select(offered.size() > 1 && !wanted.isEmpty() ? 1 : 0);
        });
        DialogParts.Field gitignoreField = DialogParts.field(".gitignore", gitignore).trailing(gitignoreFilter)
                .hint("What git leaves untracked for your language or tool. Type in the filter to find one.");

        ComboBox<String> license = new ComboBox<>();
        license.setId(LICENSE_ID);
        license.getItems().addAll(RepoInitTemplates.licenseTemplateNames());
        license.getSelectionModel().selectFirst();
        DialogParts.Field licenseField = DialogParts.field("License", license);

        TextField holder = new TextField(System.getProperty("user.name", ""));
        holder.setId(HOLDER_ID);
        holder.setPromptText("Ada Lovelace");
        DialogParts.Field holderField = DialogParts.field("Copyright holder", holder)
                .hint("Named in the license's copyright line.");
        // Only there while there is a license for it to go in: a disabled field under
        // "None" looked like something still to fill in.
        Node holderNode = holderField.node();
        holderNode.visibleProperty().bind(license.valueProperty().isNotEqualTo(RepoInitTemplates.NONE));
        holderNode.managedProperty().bind(holderNode.visibleProperty());
        holderNode.visibleProperty().addListener((obs, was, is) -> JfxDialog.refit(holderNode));

        CheckBox readme = new CheckBox("Add a README.md");
        readme.setId(README_ID);

        dialog.getDialogPane().setContent(DialogParts.stack(folderField,
                DialogParts.section("Starter files"),
                gitignoreField, licenseField, holderField,
                DialogParts.toggle(readme, "Starts a README.md with " + dir.getName() + " as its heading.")));

        dialog.setResultConverter(button -> button == confirm
                ? new RepoInitTemplates(gitignore.getValue(), license.getValue(),
                        holder.getText(), readme.isSelected())
                : null);
        JfxDialog.shell(dialog, JfxDialog.Size.MD,
                purpose == Purpose.CREATE ? "New repository" : "Not a Git repository");

        dialog.showAndWait().ifPresent(templates ->
                // The network lane: there is no open repository yet for the repository
                // lane to be about, and the welcome page may be the only tab.
                GitTaskRunner.runDetached(() -> init.apply(templates), result -> {
                    if (result.isOk()) {
                        open.run();
                    } else {
                        initFailed(result.getMessage()).show();
                    }
                }));
    }

    /**
     * The init's failure, under a title naming the folder, with the reason in a sentence.
     * The engines say "Init failed: " and then git's or JGit's reason, or "Repository
     * created, but ..." when only what came after {@code git init} went wrong - and then
     * the title must not say there is no repository. Of git's stderr, the line saying
     * what was fatal; all of it, when there is more, is behind Show Details.
     */
    private Dialogs.Message initFailed(String raw) {
        String text = raw == null ? "" : raw.strip();
        String createdBut = "Repository created, but ";
        boolean created = text.startsWith(createdBut);
        String reason = created ? text.substring(createdBut.length())
                : text.startsWith("Init failed:") ? text.substring("Init failed:".length()).strip() : text;
        String said = null;
        for (String line : reason.lines().toList()) {
            String l = line.strip();
            if (l.startsWith("fatal:") || l.startsWith("error:")) {
                said = l.substring(l.indexOf(':') + 1).strip();
            }
        }
        if (said == null || said.isEmpty()) {
            said = reason.lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("");
        }
        Dialogs.Message failed = Dialogs.error(purpose == Purpose.CREATE ? "New repository" : "Not a Git repository")
                .icon(Icons.folder())
                .title(created ? "Created a repository in " + dir.getName() + ", with a problem"
                        : "Could not create a repository in " + dir.getName())
                .subtitle("`" + dir.getAbsolutePath() + "`")
                .message(said.isEmpty() ? "Git gave no reason."
                        : Character.toUpperCase(said.charAt(0)) + said.substring(1))
                .size(JfxDialog.Size.MD);
        if (reason.lines().count() > 1) {
            failed.details(text);
        }
        return failed;
    }
}
