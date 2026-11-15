package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import io.github.pix3lpirat3.mfbridge.protocol.core.KeyActivation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Lookup from a key to the bot keybinds it can trigger. A chord triggers on its
 * last key while all earlier keys are held. Built once per declaration change; each key event checks
 * only the binds whose trigger is that key. Immutable.
 */
public final class KeybindIndex {

    /** An index with no binds. */
    public static final KeybindIndex EMPTY = build(List.of());

    /** Most specific first: priority, then chord length, then the bind declared last. */
    private static final Comparator<Bind> ORDER = Comparator.comparingInt(Bind::priority).reversed()
            .thenComparing(Comparator.comparingInt((Bind b) -> b.keys().size()).reversed())
            .thenComparing(Comparator.comparingInt(Bind::order).reversed());

    private final Map<String, List<Bind>> byTrigger;
    private final Set<String> keys;
    private final List<Conflict> conflicts;

    private KeybindIndex(final Map<String, List<Bind>> byTrigger, final Set<String> keys, final List<Conflict> conflicts) {
        this.byTrigger = byTrigger;
        this.keys = keys;
        this.conflicts = conflicts;
    }

    /**
     * One bot keybind.
     *
     * @param declKey full object key of the {@code core:keybind}
     * @param keys vanilla key names such as {@code key.keyboard.g}, chords in order; at least one. Immutable.
     * @param context {@code ingame}, {@code gui}, {@code any} or {@code tool:<id>}
     * @param activation when events fire
     * @param priority higher wins
     * @param consumes whether the triggering key is kept from vanilla
     * @param order position in declaration order; on ties the later bind wins
     */
    public record Bind(String declKey, List<String> keys, String context, KeyActivation activation, int priority, boolean consumes, int order) {

        /**
         * Copies the keys.
         *
         * @throws IllegalArgumentException if no key is given
         */
        public Bind {
            keys = List.copyOf(keys);
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("a keybind needs at least one key: " + declKey);
            }
        }

        /**
         * Returns the key that triggers the chord.
         *
         * @return the last key
         */
        public String trigger() {
            return keys.getLast();
        }
    }

    /**
     * Two binds with the same keys in overlapping contexts; {@code winner} takes the key.
     *
     * @param winner the bind that triggers
     * @param loser the bind that never triggers while the winner's context is active
     * @param tie whether priority did not decide, so declaration order did
     */
    public record Conflict(Bind winner, Bind loser, boolean tie) {
    }

    /**
     * Builds an index.
     *
     * @param binds the binds in declaration order
     * @return the index
     */
    public static KeybindIndex build(final List<Bind> binds) {
        final Map<String, List<Bind>> byTrigger = new HashMap<>();
        final Set<String> keys = new HashSet<>();
        for (final Bind bind : binds) {
            byTrigger.computeIfAbsent(bind.trigger(), k -> new ArrayList<>()).add(bind);
            keys.addAll(bind.keys());
        }
        final List<Conflict> conflicts = new ArrayList<>();
        for (final Map.Entry<String, List<Bind>> e : byTrigger.entrySet()) {
            final List<Bind> list = e.getValue();
            list.sort(ORDER);
            e.setValue(List.copyOf(list));
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    final Bind a = list.get(i);
                    final Bind b = list.get(j);
                    if (a.keys().equals(b.keys()) && overlap(a.context(), b.context())) {
                        conflicts.add(new Conflict(a, b, a.priority() == b.priority()));
                    }
                }
            }
        }
        return new KeybindIndex(Map.copyOf(byTrigger), Set.copyOf(keys), List.copyOf(conflicts));
    }

    /**
     * Returns the binds triggered by a key, most specific first.
     *
     * @param trigger a key name
     * @return the candidates, possibly empty. Immutable.
     */
    public List<Bind> candidates(final String trigger) {
        return byTrigger.getOrDefault(trigger, List.of());
    }

    /**
     * Returns the bind a key press triggers.
     *
     * @param trigger the pressed key's name
     * @param isDown whether another key is currently held
     * @param contextActive whether a bind's context applies right now
     * @return the most specific bind whose context is active and whose other chord keys are held, or null
     */
    public @Nullable Bind match(final String trigger, final Predicate<String> isDown, final Predicate<Bind> contextActive) {
        final List<Bind> list = byTrigger.get(trigger);
        if (list == null) {
            return null;
        }
        for (final Bind bind : list) {
            if (!contextActive.test(bind)) {
                continue;
            }
            boolean held = true;
            final List<String> chord = bind.keys();
            for (int i = 0; i < chord.size() - 1 && held; i++) {
                held = isDown.test(chord.get(i));
            }
            if (held) {
                return bind;
            }
        }
        return null;
    }

    /**
     * Returns whether any bind uses a key, as trigger or modifier.
     *
     * @param key a key name
     * @return whether the key is bound
     */
    public boolean uses(final String key) {
        return keys.contains(key);
    }

    /**
     * Returns the binds that shadow each other.
     *
     * @return the conflicts. Immutable.
     */
    public List<Conflict> conflicts() {
        return conflicts;
    }

    /**
     * Returns whether two bind contexts can be active at the same time.
     *
     * @param a one context
     * @param b another context
     * @return false only for contexts that exclude each other
     */
    public static boolean overlap(final String a, final String b) {
        if (a.equals(b) || a.equals("any") || b.equals("any")) {
            return true;
        }
        final boolean aGame = a.equals("ingame") || a.startsWith("tool:");
        final boolean bGame = b.equals("ingame") || b.startsWith("tool:");
        if (a.startsWith("tool:") && b.startsWith("tool:")) {
            return false;
        }
        return aGame && bGame;
    }
}
