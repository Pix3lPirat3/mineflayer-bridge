package io.github.pix3lpirat3.mfbridge.core.command;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits command lines into {@link Token}s: whitespace separates tokens, double quotes group words,
 * and a backslash escapes the next character inside quotes. An unterminated quote runs to the end of
 * the line. Pure and thread-safe.
 */
public final class Tokenizer {
    private Tokenizer() {}

    /**
     * The tokens of a line.
     *
     * @param tokens tokens in order. Immutable.
     * @param trailingSpace whether the line ends with whitespace outside quotes, so the player is
     *     starting a new token
     * @param openQuote whether the last token has an unterminated quote
     */
    public record Tokens(List<Token> tokens, boolean trailingSpace, boolean openQuote) {
        /** Copies the tokens. */
        public Tokens {
            tokens = List.copyOf(tokens);
        }

        /** Returns the token texts in order. */
        public List<String> texts() {
            List<String> out = new ArrayList<>(tokens.size());
            for (Token t : tokens) {
                out.add(t.text());
            }
            return out;
        }
    }

    /**
     * Tokenizes a line.
     *
     * @param line the typed text
     * @return the tokens with their ranges
     */
    public static Tokens split(String line) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inToken = false;
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '\\' && i + 1 < line.length()) {
                    current.append(line.charAt(++i));
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    current.append(ch);
                }
                continue;
            }
            if (Character.isWhitespace(ch)) {
                if (inToken) {
                    tokens.add(new Token(current.toString(), start, i));
                    current.setLength(0);
                    inToken = false;
                }
                continue;
            }
            if (!inToken) {
                inToken = true;
                start = i;
            }
            if (ch == '"') {
                quoted = true;
            } else {
                current.append(ch);
            }
        }
        if (inToken) {
            tokens.add(new Token(current.toString(), start, line.length()));
        }
        boolean trailing = !line.isEmpty() && !quoted && Character.isWhitespace(line.charAt(line.length() - 1));
        return new Tokens(tokens, trailing, quoted);
    }

    /**
     * Quotes a value so that {@link #split} reads it back as one token.
     *
     * @param value any text
     * @return the value itself when it needs no quoting, else a quoted and escaped form
     */
    public static String quote(String value) {
        boolean plain = !value.isEmpty();
        for (int i = 0; i < value.length() && plain; i++) {
            char ch = value.charAt(i);
            plain = !Character.isWhitespace(ch) && ch != '"' && ch != '\\';
        }
        if (plain) {
            return value;
        }
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
