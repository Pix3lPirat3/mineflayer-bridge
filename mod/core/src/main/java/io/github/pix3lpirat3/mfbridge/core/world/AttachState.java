package io.github.pix3lpirat3.mfbridge.core.world;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.AttachControls;
import io.github.pix3lpirat3.mfbridge.core.AttachPolicy;
import io.github.pix3lpirat3.mfbridge.core.persist.PlayerPrefs;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/** Attach policy plus explicit choices per bot identity. */
@ClientThread
public final class AttachState implements AttachControls {
    private final PlayerPrefs prefs;
    private final Predicate<String> worldMatched;
    private final Consumer<@Nullable String> onChange;

    /**
     * Creates the state.
     *
     * @param worldMatched whether a bot identity is currently in the player's world
     * @param onChange called with the affected identity, or null when every identity may be affected
     */
    public AttachState(PlayerPrefs prefs, Predicate<String> worldMatched, Consumer<@Nullable String> onChange) {
        this.prefs = prefs;
        this.worldMatched = worldMatched;
        this.onChange = onChange;
    }

    @Override
    public AttachPolicy policy() {
        return prefs.policy();
    }

    @Override
    public void setPolicy(AttachPolicy policy) {
        if (policy != prefs.policy()) {
            prefs.policy(policy);
            onChange.accept(null);
        }
    }

    @Override
    public boolean isAttached(String identity) {
        return isAttached(identity, worldMatched.test(identity));
    }

    /** Same as {@link #isAttached(String)} with the world match already known. */
    public boolean isAttached(String identity, boolean matched) {
        Boolean choice = prefs.attach().get(identity);
        if (choice != null) {
            return choice;
        }
        return switch (prefs.policy()) {
            case AUTO -> matched;
            case ALWAYS -> true;
            case MANUAL -> false;
        };
    }

    @Override
    public boolean worldMatched(String identity) {
        return worldMatched.test(identity);
    }

    @Override
    public @Nullable Boolean choice(String identity) {
        return prefs.attach().get(identity);
    }

    @Override
    public void setChoice(String identity, @Nullable Boolean attached) {
        Boolean previous = attached == null ? prefs.attach().remove(identity) : prefs.attach().put(identity, attached);
        if (!Objects.equals(previous, attached)) {
            prefs.save();
            onChange.accept(identity);
        }
    }
}
