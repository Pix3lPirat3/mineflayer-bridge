package io.github.pix3lpirat3.mfbridge.common.render.command;

/** How a shape interacts with world depth. */
public enum DepthMode {
    /** Hidden behind blocks. */
    TEST,
    /** Drawn through walls. */
    XRAY,
    /** Drawn dimmed through walls, then normally where visible. */
    BOTH
}
