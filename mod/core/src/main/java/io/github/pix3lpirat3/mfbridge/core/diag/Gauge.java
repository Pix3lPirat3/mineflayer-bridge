package io.github.pix3lpirat3.mfbridge.core.diag;

/** Point-in-time values the renderer reports for the inspector's Performance tab. */
public enum Gauge {
    /** GPU memory held by retained-tier meshes, in bytes. */
    RETAINED_BYTES("Retained mesh memory"),
    /** Section meshes waiting for upload or meshing jobs queued. */
    UPLOAD_QUEUE("Upload queue"),
    /** Immediate-tier draw commands in the last frame. */
    IMMEDIATE_COMMANDS("Immediate commands");

    private final String label;

    Gauge(String label) {
        this.label = label;
    }

    /** Returns a short label for the inspector. */
    public String label() {
        return label;
    }
}
