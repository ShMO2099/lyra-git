package com.jfx.git.core;

/** Raised when a git operation cannot be carried out. */
public class GitOperationException extends RuntimeException {

    public GitOperationException(String message) {
        super(message);
    }

    public GitOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
