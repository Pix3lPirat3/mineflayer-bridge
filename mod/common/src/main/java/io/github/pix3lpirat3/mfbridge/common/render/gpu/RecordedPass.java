package io.github.pix3lpirat3.mfbridge.common.render.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A pass for render APIs that cannot write uniforms while a render pass is open (26.1 and 26.2):
 * the draws are kept, and on close the version writes every transform, opens its pass and replays
 * them. One per frame.
 */
public final class RecordedPass implements Gpu.Pass {

    /** One kept call: a bind, a transform or a draw. */
    public sealed interface Step permits Bind, Draw, SetTransform {
    }

    /** Binds a pipeline and what it samples. */
    public record Bind(Gpu.Pipeline pipeline, Gpu.Texture texture) implements Step {
    }

    /** Sets the uniforms of the next draws; {@code index} counts the frame's transforms. */
    public record SetTransform(Gpu.Transform transform, int index) implements Step {
    }

    /** Draws quads from a buffer. */
    public record Draw(Object buffer, int firstVertex, int vertices) implements Step {
    }

    private final List<Step> steps = new ArrayList<>();
    private final List<Gpu.Transform> transforms = new ArrayList<>();
    private final Consumer<RecordedPass> replay;

    /**
     * Starts recording.
     *
     * @param replay writes {@link #transforms()}, opens the pass and runs {@link #steps()}
     */
    public RecordedPass(final Consumer<RecordedPass> replay) {
        this.replay = replay;
    }

    /** Returns the kept calls in order. */
    public List<Step> steps() {
        return steps;
    }

    /** Returns every transform of the frame, in the order of their {@link SetTransform#index}. */
    public List<Gpu.Transform> transforms() {
        return transforms;
    }

    @Override
    public void bind(final Gpu.Pipeline pipeline, final Gpu.Texture texture) {
        steps.add(new Bind(pipeline, texture));
    }

    @Override
    public void transform(final Gpu.Transform transform) {
        steps.add(new SetTransform(transform, transforms.size()));
        transforms.add(transform);
    }

    @Override
    public void draw(final Object buffer, final int firstVertex, final int vertices) {
        steps.add(new Draw(buffer, firstVertex, vertices));
    }

    @Override
    public void close() {
        if (!steps.isEmpty()) {
            replay.accept(this);
        }
    }
}
