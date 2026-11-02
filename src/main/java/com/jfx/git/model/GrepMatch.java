package com.jfx.git.model;

/**
 * One line a {@link GrepQuery} found.
 *
 * @param path repository-relative, with forward slashes
 * @param line the line's number in the file, from 1
 * @param text the line as it reads, without its line ending
 */
public record GrepMatch(String path, int line, String text) {
}
