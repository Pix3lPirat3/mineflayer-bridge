package io.github.pix3lpirat3.mfbridge.core.command;

/**
 * One token of a command line.
 *
 * @param text the token without its quotes, with backslash escapes inside quotes applied
 * @param start offset of the token's first character in the line, in chars
 * @param end offset just past the token's last character (the closing quote, if any), in chars
 */
public record Token(String text, int start, int end) {}
