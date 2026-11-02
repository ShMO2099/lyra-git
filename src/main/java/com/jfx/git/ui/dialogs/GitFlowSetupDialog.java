package com.jfx.git.ui.dialogs;

import com.jfx.git.core.GitFlow;
import com.jfx.git.ui.Icons;
import com.jfx.git.ui.UiScale;

import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextField;

import java.util.List;

/**
 * Sets a repository up for Git Flow, or changes how it is set up.
 *
 * <p>Opens on git-flow's own defaults, or on what the repository already has. The
 * production branch is chosen from the branches that exist, because it has to be one of
 * them - it is where releases have been going. The develop branch is typed: it usually
 * does not exist yet, and setting up creates it.
 *
 * <p>The prefixes sit two to a row: they are short, and four full-width fields made the
 * form twice the height for nothing. What {@link GitFlow#init} would refuse - the two
 * branches the same, a prefix left empty, a name git does not accept - shows under the
 * field as it is typed, with the button off until it is right.
 */
public class GitFlowSetupDialog extends Dialog<GitFlow.Config> {

    public static final String PRODUCTION_ID = "gitflow-production";
    public static final String DEVELOP_ID = "gitflow-develop";
    public static final String FEATURE_ID = "gitflow-feature";
    public static final String RELEASE_ID = "gitflow-release";
    public static final String HOTFIX_ID = "gitflow-hotfix";
    public static final String TAG_ID = "gitflow-versiontag";

    /**
     * @param localBranches the repository's local branches; never empty
     * @param existing      the repository's current settings, or {@code null} to start
     *                      from the defaults
     */
    public GitFlowSetupDialog(List<String> localBranches, GitFlow.Config existing) {
        JfxDialog.shell(this, JfxDialog.Size.MD, "Git Flow");
        JfxDialog.header(this, Icons.branch(), JfxDialog.Tone.NEUTRAL,
                existing == null ? "Set up Git Flow" : "Git Flow settings",
                "Branches and prefixes for feature, release and hotfix work").ruled();
        ButtonType save = new ButtonType(existing == null ? "Set up" : "Save", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        GitFlow.Config start = existing != null ? existing : GitFlow.Config.defaults(
                localBranches.contains("main") ? "main"
                        : localBranches.contains("master") ? "master" : localBranches.get(0));

        ChoiceBox<String> production = new ChoiceBox<>(FXCollections.observableArrayList(localBranches));
        production.setId(PRODUCTION_ID);
        production.setValue(localBranches.contains(start.production()) ? start.production() : localBranches.get(0));
        TextField develop = field(DEVELOP_ID, start.develop(), "develop");
        TextField feature = field(FEATURE_ID, start.featurePrefix(), "feature/");
        TextField release = field(RELEASE_ID, start.releasePrefix(), "release/");
        TextField hotfix = field(HOTFIX_ID, start.hotfixPrefix(), "hotfix/");
        TextField versionTag = field(TAG_ID, start.versionTagPrefix(), "v");

        DialogParts.Field productionField = DialogParts.field("Production", production)
                .hint("Where releases are made from.");
        DialogParts.Field developField = DialogParts.field("Develop", develop)
                .hint("Where finished features gather. Created from the production branch if it "
                        + "does not exist yet.");
        DialogParts.Field featureField = DialogParts.field("Feature", feature);
        DialogParts.Field releaseField = DialogParts.field("Release", release);
        DialogParts.Field hotfixField = DialogParts.field("Hotfix", hotfix);
        DialogParts.Field tagField = DialogParts.field("Version tag", versionTag).optional()
                .hint("v tags release/1.2 as v1.2.");

        getDialogPane().setContent(DialogParts.stack(
                DialogParts.section("Branches"), productionField, developField,
                DialogParts.section("Prefixes"),
                DialogParts.pair(featureField, releaseField), DialogParts.pair(hotfixField, tagField)));

        Button confirm = (Button) getDialogPane().lookupButton(save);
        Runnable validate = () -> {
            String developName = text(develop);
            String developProblem = developName.isEmpty() ? null
                    : developName.equals(production.getValue()) ? "Has to be a different branch from production."
                    : com.jfx.git.model.RefNames.branchNameProblem(developName);
            developField.error(developProblem);
            // & rather than &&: every prefix says what is wrong with it, not only the first.
            boolean prefixesOk = checkPrefix(featureField, "feature")
                    & checkPrefix(releaseField, "release")
                    & checkPrefix(hotfixField, "hotfix");
            String tag = text(versionTag);
            String tagProblem = tag.isEmpty() || org.eclipse.jgit.lib.Repository.isValidRefName("refs/tags/" + tag + "1.0")
                    ? null : "Not something a tag name can start with.";
            tagField.error(tagProblem);
            confirm.setDisable(developName.isEmpty() || developProblem != null || !prefixesOk || tagProblem != null);
        };
        for (TextField typed : List.of(develop, feature, release, hotfix, versionTag)) {
            typed.textProperty().addListener((obs, old, now) -> validate.run());
        }
        production.valueProperty().addListener((obs, old, now) -> validate.run());
        validate.run();

        setResultConverter(button -> button == save
                ? new GitFlow.Config(production.getValue(), text(develop), text(feature), text(release),
                        text(hotfix), text(versionTag))
                : null);
    }

    /** Shows what is wrong with a prefix field, if anything; whether it is right. */
    private static boolean checkPrefix(DialogParts.Field field, String word) {
        String problem = prefixProblem(text(field.control()), word);
        field.error(problem);
        return problem == null;
    }

    /** What {@code GitFlow.init} would refuse about a branch prefix, or null. */
    private static String prefixProblem(String prefix, String word) {
        if (prefix.isEmpty()) {
            return "Needed: it is how a " + word + " branch is told from the rest.";
        }
        return com.jfx.git.model.RefNames.branchNameProblem(prefix + "name") == null ? null
                : "Not something a branch name can start with.";
    }

    private static TextField field(String id, String value, String example) {
        TextField field = new TextField(value == null ? "" : value);
        field.setId(id);
        field.setPromptText(example);
        return field;
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().trim();
    }
}
