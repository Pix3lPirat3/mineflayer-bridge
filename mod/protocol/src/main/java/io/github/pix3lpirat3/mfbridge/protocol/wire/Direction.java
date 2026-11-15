package io.github.pix3lpirat3.mfbridge.protocol.wire;

/** Which side may send a message. */
public enum Direction {
    /** Only the Node plugin sends it. */
    NODE,
    /** Only the mod sends it. */
    MOD,
    /** Either side sends it. */
    BOTH;

    /** Returns whether the Node plugin may send it. */
    public boolean allowsFromNode() {
        return this != MOD;
    }

    /** Returns whether the mod may send it. */
    public boolean allowsFromMod() {
        return this != NODE;
    }
}
