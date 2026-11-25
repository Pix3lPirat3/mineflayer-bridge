package io.github.pix3lpirat3.mfbridge.common.version;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.jspecify.annotations.Nullable;

/**
 * Finds a game member by any of the names it has had across the supported versions. Held in static
 * finals, the handles are constants to the JIT, so calls through them cost what a direct call does.
 */
final class Members {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.publicLookup();

    private Members() {
    }

    /** An instance method, or null when no name fits. */
    static @Nullable MethodHandle virtual(final Class<?> owner, final MethodType type, final String... names) {
        for (final String name : names) {
            try {
                return LOOKUP.findVirtual(owner, name, type);
            } catch (final NoSuchMethodException | IllegalAccessException e) {
                // the next name
            }
        }
        return null;
    }

    /** A static method, or null when no name fits. */
    static @Nullable MethodHandle statics(final Class<?> owner, final MethodType type, final String... names) {
        for (final String name : names) {
            try {
                return LOOKUP.findStatic(owner, name, type);
            } catch (final NoSuchMethodException | IllegalAccessException e) {
                // the next name
            }
        }
        return null;
    }

    /** An instance field's getter, or null when no name fits. */
    static @Nullable MethodHandle getter(final Class<?> owner, final Class<?> type, final String... names) {
        for (final String name : names) {
            try {
                return LOOKUP.findGetter(owner, name, type);
            } catch (final NoSuchFieldException | IllegalAccessException e) {
                // the next name
            }
        }
        return null;
    }

    /** A static field's value, or null when no name fits. */
    static @Nullable Object staticValue(final Class<?> owner, final Class<?> type, final String... names) {
        for (final String name : names) {
            try {
                return LOOKUP.findStaticGetter(owner, name, type).invoke();
            } catch (final NoSuchFieldException | IllegalAccessException e) {
                // the next name
            } catch (final Throwable e) {
                throw new IllegalStateException("reading " + owner.getName() + "." + name, e);
            }
        }
        return null;
    }

    /** The first handle that is there; fails startup with the names when none is. */
    static MethodHandle first(final String what, final @Nullable MethodHandle... handles) {
        for (final MethodHandle h : handles) {
            if (h != null) {
                return h;
            }
        }
        throw new IllegalStateException("Mineflayer Bridge: this game version has no " + what);
    }
}
