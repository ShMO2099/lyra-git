package com.jfx.git.model;

/**
 * A file locked through Git LFS.
 *
 * @param id    the lock's id on the server
 * @param path  the locked file, relative to the repository root
 * @param owner the name of whoever holds the lock, or blank when the server did not say
 */
public record LfsLock(String id, String path, String owner) {
}
