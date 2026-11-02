package com.jfx.git.model;

/**
 * A parsed Git LFS pointer file - what a working-tree file actually holds once
 * {@code git lfs track} takes it over: not the real content, a small text stand-in
 * naming where the real content lives.
 *
 * @param oid  {@code <algorithm>:<hex digest>}, e.g. {@code sha256:4d7a2146...}
 * @param size the real object's size in bytes, as the pointer itself declares it
 */
public record LfsPointer(String oid, long size) {
}
