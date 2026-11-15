package io.github.pix3lpirat3.mfbridge.api.client;

/**
 * Where the bridge's own stages sit in the in-game input chain, for {@link
 * ClientRegistrar#inputStage}. Lower numbers see a press first. An extension picks a number between
 * two of these, or after {@link #TOOLS} to come last.
 */
public final class InputOrder {
    /** The input a bot is waiting for: a pick, a prompt or a world menu. */
    public static final int REQUEST = 100;
    /** The selected object. */
    public static final int SELECTION = 200;
    /** The handles of an object being edited. */
    public static final int GIZMO = 300;
    /** Interactive elements under the crosshair. */
    public static final int ELEMENTS = 400;
    /** The first-party zone, point and route tools. */
    public static final int SPATIAL = 500;
    /** The active bot tool. */
    public static final int TOOLS = 600;

    private InputOrder() {
    }
}
