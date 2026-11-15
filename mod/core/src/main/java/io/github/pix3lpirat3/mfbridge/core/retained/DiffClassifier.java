package io.github.pix3lpirat3.mfbridge.core.retained;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Ghost-block volume {@code diffAgainstWorld}: classifies every set position of a volume
 * section against the world. The volume names a state for a position (or air, meaning the position
 * should be empty); the world holds what is there now.
 *
 * <ul>
 *   <li>{@link #MISSING}: the volume wants a block and the world is air there.
 *   <li>{@link #WRONG}: the world holds a different block, or the same block with different values
 *       for the properties the volume names.
 *   <li>{@link #EXTRA}: the volume wants air and the world holds a block.
 *   <li>{@link #MATCH}: the world already matches; hidden when {@code hideMatching}.
 *   <li>{@link #PLAIN}: no comparison (diff off, or the world section is not loaded).
 * </ul>
 *
 * <p>Stateless; safe on any thread.
 */
public final class DiffClassifier {
    /** Unset in the volume: never drawn. */
    public static final byte HIDDEN = 0;
    /** Drawn as a plain ghost: no diff, or the world there is unknown. */
    public static final byte PLAIN = 1;
    /** The world already matches. */
    public static final byte MATCH = 2;
    /** The volume wants a block where the world is air. */
    public static final byte MISSING = 3;
    /** The world holds a different block or state. */
    public static final byte WRONG = 4;
    /** The volume wants air where the world holds a block. */
    public static final byte EXTRA = 5;

    /** Number of classes, for per-class tables. */
    public static final int CLASSES = 6;

    /** Compares one volume palette entry with one world state. Implementations should be cheap and pure. */
    @FunctionalInterface
    public interface StateMatcher {
        /**
         * Classifies a pair.
         *
         * @param paletteId volume palette id, never {@link GhostVolume#UNSET}
         * @param worldStateId the game's numeric id of the world state at the position
         * @return {@link #MATCH}, {@link #MISSING}, {@link #WRONG}, {@link #EXTRA}, or {@link #PLAIN} when
         *     the volume state cannot be compared (unknown block)
         */
        byte classify(int paletteId, int worldStateId);
    }

    private DiffClassifier() {}

    /**
     * The classification rule for one position.
     *
     * @param volumeAir the volume names air
     * @param worldAir the world holds air
     * @param sameBlock the world holds the block the volume names
     * @param propertiesMatch every property the volume names has the same value in the world
     */
    public static byte rule(boolean volumeAir, boolean worldAir, boolean sameBlock, boolean propertiesMatch) {
        if (volumeAir) {
            return worldAir ? MATCH : EXTRA;
        }
        if (worldAir) {
            return MISSING;
        }
        return sameBlock && propertiesMatch ? MATCH : WRONG;
    }

    /**
     * Classifies one section.
     *
     * @param volume palette ids in {@code (y * 16 + z) * 16 + x} order, 4096 entries
     * @param world the world's state ids in the same order, or null when no comparison is wanted or the
     *     world section is not loaded
     * @param matcher compares a palette id with a world state id; results are cached per call
     * @param out receives one class per position, 4096 entries
     * @return the number of positions that are not {@link #HIDDEN}
     */
    public static int classify(short[] volume, int @Nullable [] world, StateMatcher matcher, byte[] out) {
        Long2ByteOpenHashMap cache = world == null ? null : new Long2ByteOpenHashMap();
        int set = 0;
        for (int i = 0; i < 4096; i++) {
            int p = Short.toUnsignedInt(volume[i]);
            if (p == GhostVolume.UNSET) {
                out[i] = HIDDEN;
                continue;
            }
            set++;
            if (world == null || cache == null) {
                out[i] = PLAIN;
                continue;
            }
            long pair = (long) p << 32 | (world[i] & 0xFFFFFFFFL);
            byte c;
            if (cache.containsKey(pair)) {
                c = cache.get(pair);
            } else {
                c = matcher.classify(p, world[i]);
                cache.put(pair, c);
            }
            out[i] = c;
        }
        return set;
    }

    /** Returns whether a class is drawn with block faces near the camera. */
    public static boolean facesDrawn(byte cls, boolean hideMatching) {
        return switch (cls) {
            case PLAIN, MISSING, WRONG -> true;
            case MATCH -> !hideMatching;
            default -> false;
        };
    }

    /** Returns whether a class is drawn as an outline: always for {@link #EXTRA}, and far from the camera. */
    public static boolean outlined(byte cls, boolean hideMatching, boolean far) {
        return cls == EXTRA || far && facesDrawn(cls, hideMatching);
    }

    /** Returns the face tint of a class, ARGB; multiplied with the block model's own colours. */
    public static int tint(byte cls) {
        return switch (cls) {
            case MATCH -> 0xFFB8FFB8;
            case MISSING -> 0xFF9CCBFF;
            case WRONG -> 0xFFFFB45A;
            case EXTRA -> 0xFFFF4040;
            default -> 0xFFFFFFFF;
        };
    }

    /** Returns the outline colour of a class, ARGB. */
    public static int outline(byte cls) {
        return switch (cls) {
            case MATCH -> 0xFF50E050;
            case MISSING -> 0xFF4AA0FF;
            case WRONG -> 0xFFFF9020;
            case EXTRA -> 0xFFFF2020;
            default -> 0xFFE0E0E0;
        };
    }
}
