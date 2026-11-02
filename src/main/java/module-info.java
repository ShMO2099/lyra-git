// reactfx ships no Automatic-Module-Name, so its module name is derived from the jar's
// file name and every compiler warns that it is unstable. Nothing here can give it a
// better one; the warning is suppressed rather than left as permanent noise.
@SuppressWarnings("module")
module com.jfx.git.lyra {
    requires transitive javafx.controls;
    requires transitive javafx.graphics;
    // Not transitive any more: no JGit type appears in an exported signature. The engine
    // boundary is plain Java by design, and the last thing breaking that invariant -
    // GitService.Submodule carrying ObjectId hashes - now carries Strings and lives in
    // com.jfx.git.model. What is left is internal use (InlineDiff's histogram differ,
    // ProfilesPage's StoredConfig, the pack-mmap setting in LyraApp).
    requires org.eclipse.jgit;
    // SSH transport for git@host: remotes. The agent module is pulled in only by
    // ServiceLoader, so without an explicit requires it never joins the module graph
    // and ssh-agent support silently disappears.
    requires org.eclipse.jgit.ssh.apache;
    requires org.eclipse.jgit.ssh.apache.agent;
    requires java.logging;
    // java.awt.Desktop, for opening a working-tree file in its associated editor
    // without the console window that cmd.exe would flash.
    requires java.desktop;
    // GitHub REST calls (com.jfx.git.core.github), a plain JDK module.
    requires java.net.http;
    // OAuth sign-in's loopback redirect lands on a one-shot com.sun.net.httpserver
    // server on 127.0.0.1 (com.jfx.git.core.hosting.OAuthLoopbackFlow). The tests use it
    // too, for fake provider servers - and since no separate test module-info exists,
    // they compile as part of this module and need the same edge. The packaging scripts'
    // JLINK_MODULES list it as well; the runtime image has nothing this list does not.
    requires jdk.httpserver;
    // Transitive: com.jfx.git.ui exports DiffTextArea, which extends RichTextFX's
    // StyleClassedTextArea, and CodeStyler takes one - RichTextFX is part of this
    // module's UI API, unlike JGit, which stays behind the engine boundary.
    requires transitive org.fxmisc.richtext;
    requires org.fxmisc.flowless;
    // RichTextFX exposes reactfx types (Val, EventStream) on the members the diff
    // viewer uses to size an area to its own content.
    requires reactfx;
    // RichTextFX's own undo manager type, for clearing the history a display surface
    // gathered before it became an editor.
    requires org.fxmisc.undo;
    requires java.prefs;
    requires atlantafx.base;
    // TextMate grammar engine for com.jfx.git.ui.syntax.TextMateTokenizer. An automatic
    // module (Automatic-Module-Name in its manifest, no real module-info.class) - the
    // same category of dependency as org.eclipse.jgit.ssh.apache above, and the same
    // jlink limitation applies: jlink already can't link this project because of that
    // one. Its own runtime dependencies, joni and jcodings, declare no module name at
    // all (not even Automatic-Module-Name), so Maven leaves them on the classpath as
    // part of the unnamed module rather than the module path; no `requires` is needed
    // for them here because this module never references their types directly, and an
    // automatic module such as org.eclipse.tm4e.core automatically reads every other
    // module in the graph, including the unnamed module, so its internal use of joni
    // still resolves at runtime.
    requires org.eclipse.tm4e.core;
    requires com.google.gson;
    // The Markdown preview's parser and its GitHub extensions.
    requires org.commonmark;
    requires org.commonmark.ext.gfm.tables;
    requires org.commonmark.ext.gfm.strikethrough;
    requires org.commonmark.ext.autolink;
    requires transitive org.jruby.joni;
    requires transitive org.jruby.jcodings;
    // TM4E parses grammars with Gson but is an automatic module, so it declares no
    // dependency on it — and a real named module (Gson ships module-info) is only
    // resolved when something requires it. Without this edge, grammar parsing dies on
    // the module path with NoClassDefFoundError: com/google/gson/Gson, while the
    // classpath-launched test suite works fine. The joni/jcodings edges are the same
    // story: current jars ship their own descriptors (org.jruby.joni), so the pom's
    // old "they stay on the classpath" assumption no longer holds.

    exports com.jfx.git;
    exports com.jfx.git.core;
    exports com.jfx.git.core.github;
    exports com.jfx.git.core.gitlab;
    exports com.jfx.git.core.bitbucket;
    exports com.jfx.git.core.gitea;
    exports com.jfx.git.core.hosting;
    exports com.jfx.git.core.libgit2;
    exports com.jfx.git.graph;
    exports com.jfx.git.model;
    exports com.jfx.git.ui;
    exports com.jfx.git.ui.syntax;
    exports com.jfx.git.ui.dialogs;
    exports com.jfx.git.ui.views;
    exports com.jfx.git.ui.help;
    exports com.jfx.git.ui.settings;

    opens com.jfx.git to javafx.graphics;

    // Gson reads JSON into private records in these packages, and on the module path it
    // can only reach a non-public type in a package opened to it. Without these the
    // reads failed only in the running app - the tests run on the classpath, where
    // everything is open - which hid it: the grammar catalogue, the hosting providers'
    // replies and the update check all depend on it. GsonModuleOpensTest keeps the list
    // in step with the code.
    opens com.jfx.git.core to com.google.gson;
    opens com.jfx.git.core.github to com.google.gson;
    opens com.jfx.git.core.gitlab to com.google.gson;
    opens com.jfx.git.core.bitbucket to com.google.gson;
    opens com.jfx.git.core.gitea to com.google.gson;
    opens com.jfx.git.core.hosting to com.google.gson;
    opens com.jfx.git.ui.syntax to com.google.gson;
}
