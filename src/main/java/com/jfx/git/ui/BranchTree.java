package com.jfx.git.ui;

import javafx.scene.control.TreeItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns flat branch names into the nested folder structure commercial clients show, so
 * {@code agent/rust-chat-template} and {@code agent/symm-mem-nvshmem} collapse under
 * a single {@code agent} folder instead of listing as two long flat rows.
 */
public final class BranchTree {

    /** A node in the branch tree: either a folder or an actual branch. */
    public static final class Node {
        private final String label;
        private final String fullName;
        private final boolean folder;

        Node(String label, String fullName, boolean folder) {
            this.label = label;
            this.fullName = fullName;
            this.folder = folder;
        }

        /** The path segment shown in the row. */
        public String label() {
            return label;
        }

        /** Full ref name, e.g. {@code origin/agent/rust-chat-template}; null for folders. */
        public String fullName() {
            return fullName;
        }

        public boolean isFolder() {
            return folder;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private BranchTree() {
    }

    /**
     * Builds a tree from full branch names.
     *
     * @param names    full branch names, e.g. {@code origin/bugfix/37931}
     * @param expanded whether folders start expanded
     */
    public static TreeItem<Node> build(List<String> names, boolean expanded) {
        return build(names, expanded, List.of());
    }

    /**
     * {@link #build(List, boolean)}, with folders that are shown whether or not anything
     * is in them.
     *
     * <p>For remotes. A remote that has just been added has no branches until it is
     * fetched, and one whose URL is wrong never gets any - which are the two remotes a
     * user most needs to find in the sidebar, to fetch the first and correct the second.
     *
     * @param folders folder paths to show even when empty, e.g. {@code upstream}
     */
    public static TreeItem<Node> build(List<String> names, boolean expanded, List<String> folders) {
        return build(names, folders, path -> expanded);
    }

    /**
     * {@link #build(List, boolean, List)}, with each folder open or closed by its own path
     * ({@code origin}, {@code origin/agent}) - so a rebuild can put back the folders the
     * user had open instead of resetting every one of them. Each of {@code folders} is one
     * folder, slashes and all, as {@link #build(List, List, List, java.util.function.Predicate)}
     * explains.
     */
    public static TreeItem<Node> build(List<String> names, List<String> folders,
                                       java.util.function.Predicate<String> expandFolder) {
        return build(names, folders, folders, expandFolder);
    }

    /**
     * The tree, with {@code roots} kept whole: a remote whose own name has a slash in it,
     * {@code team/core}, is one folder with its branches beneath - {@code team/core/topic}
     * is {@code topic} in it - not a folder {@code team} holding a folder {@code core}. A
     * name belongs to the longest root it starts with, the rule every view reads a remote
     * branch by ({@link com.jfx.git.model.RefNames#remoteOf}).
     *
     * @param roots   names to keep as one folder, e.g. the configured remotes
     * @param folders folder paths to show even when empty
     */
    public static TreeItem<Node> build(List<String> names, List<String> roots, List<String> folders,
                                       java.util.function.Predicate<String> expandFolder) {
        TreeItem<Node> root = new TreeItem<>(new Node("", null, true));

        // Intermediate mutable tree keyed by path segment, preserving insertion order.
        Builder builder = new Builder();
        for (String name : names) {
            builder.addSegments(name, segments(name, roots), 0);
        }
        for (String folder : folders) {
            builder.addFolder(segments(folder, roots), 0);
        }
        builder.attach(root, "", expandFolder);
        return root;
    }

    /** {@code name}'s path segments, the root it falls under - if any - as the first. */
    private static String[] segments(String name, List<String> roots) {
        if (roots == null || roots.isEmpty()) return name.split("/");
        if (roots.contains(name)) return new String[]{name};
        String first = com.jfx.git.model.RefNames.remoteOf(name, roots);
        if (!roots.contains(first)) return name.split("/");
        String[] rest = name.substring(first.length() + 1).split("/");
        String[] all = new String[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    /** The path of {@code item} from the root: its own label and its ancestors', joined by slashes. */
    public static String pathOf(TreeItem<Node> item) {
        java.util.ArrayDeque<String> segments = new java.util.ArrayDeque<>();
        for (TreeItem<Node> at = item; at != null && at.getParent() != null; at = at.getParent()) {
            segments.addFirst(at.getValue().label());
        }
        return String.join("/", segments);
    }

    private static final class Builder {
        private final Map<String, Builder> children = new LinkedHashMap<>();
        private String leafFullName;
        /** A folder even with nothing in it. */
        private boolean alwaysFolder;

        void addFolder(String[] segments, int index) {
            if (index == segments.length) {
                alwaysFolder = true;
                return;
            }
            children.computeIfAbsent(segments[index], k -> new Builder()).addFolder(segments, index + 1);
        }

        private boolean isFolder() {
            return alwaysFolder || !children.isEmpty();
        }

        void addSegments(String fullName, String[] segments, int index) {
            if (index == segments.length) {
                leafFullName = fullName;
                return;
            }
            children.computeIfAbsent(segments[index], k -> new Builder())
                    .addSegments(fullName, segments, index + 1);
        }

        void attach(TreeItem<Node> parent, String parentPath, java.util.function.Predicate<String> expand) {
            // Folders first, then branches, each alphabetically - matching commercial clients.
            List<Map.Entry<String, Builder>> entries = new ArrayList<>(children.entrySet());
            entries.sort(Comparator
                    .comparing((Map.Entry<String, Builder> e) -> !e.getValue().isFolder())
                    .thenComparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER));

            for (Map.Entry<String, Builder> entry : entries) {
                Builder child = entry.getValue();
                boolean isFolder = child.isFolder();
                Node node = new Node(entry.getKey(), child.leafFullName, isFolder);

                String path = parentPath.isEmpty() ? entry.getKey() : parentPath + "/" + entry.getKey();
                TreeItem<Node> item = new TreeItem<>(node);
                item.setExpanded(expand.test(path));
                parent.getChildren().add(item);
                child.attach(item, path, expand);
            }
        }
    }
}
