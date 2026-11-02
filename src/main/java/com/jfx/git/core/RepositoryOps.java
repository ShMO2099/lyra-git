package com.jfx.git.core;

import java.io.File;

/**
 * Opening a repository and answering where it is.
 *
 * <p>One of the capability interfaces {@link GitBackend} composes; see its javadoc for
 * why the contract is split up rather than kept as one list.
 */
public interface RepositoryOps extends AutoCloseable {

    // ------------------------------------------------------------------
    // Repository lifecycle
    // ------------------------------------------------------------------

    /**
     * Opens the repository that contains {@code dir}.
     *
     * @return {@code true} on success
     * @throws GitOperationException if no repository can be found
     */
    boolean openRepository(File dir) throws GitOperationException;

    boolean isOpen();

    /** The {@code .git} directory, or {@code null} if no repository is open. */
    File getDirectory();

    /** The working tree root, or {@code null} if no repository is open. */
    File getWorkTree();

    /** Narrows {@link AutoCloseable#close()}: closing a repository throws nothing. */
    @Override
    void close();
}
