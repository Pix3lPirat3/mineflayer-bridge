package io.github.pix3lpirat3.mfbridge.common.sound;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.LoopingAudioStream;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Custom Ogg audio from bot blobs, served to the game's sound engine. A sound emitter registers its
 * file under {@link #id}; the {@code SoundBufferLibrary} mixin asks {@link #open} before the game looks
 * in its resource packs, so no resource pack is involved. Static because the mixin has no other way in.
 */
@AnyThread
public final class BridgeAudio {

    private static final String PREFIX = "blob/";
    /** {@code Sound.getPath()} of a blob sound: {@code mineflayer_bridge:sounds/blob/<hash>.ogg}. */
    private static final String PATH_PREFIX = "sounds/" + PREFIX;
    private static final String PATH_SUFFIX = ".ogg";

    private static final Map<String, byte[]> FILES = new ConcurrentHashMap<>();

    private BridgeAudio() {
    }

    /**
     * Returns the sound id of a blob file.
     *
     * @param hex the blob hash, lower-case hex
     * @return {@code mineflayer_bridge:blob/<hex>}
     */
    public static Identifier id(final String hex) {
        return Constants.id(PREFIX + hex);
    }

    static void put(final String hex, final byte[] ogg) {
        FILES.putIfAbsent(hex, ogg);
    }

    static boolean has(final String hex) {
        return FILES.containsKey(hex);
    }

    /** Forgets every file not in {@code keep}; playing sounds keep the stream they opened. */
    static void retain(final Set<String> keep) {
        FILES.keySet().retainAll(keep);
    }

    /**
     * Opens a stream for a blob sound path.
     *
     * @param path a streamed sound's path, as the sound engine asks for it
     * @param looping whether the stream restarts at its end
     * @return the stream, failed when the file is gone or unreadable, or null when the path is not a blob sound
     */
    public static @Nullable CompletableFuture<AudioStream> open(final Identifier path, final boolean looping) {
        final String p = path.getPath();
        if (!Constants.MOD_ID.equals(path.getNamespace()) || !p.startsWith(PATH_PREFIX) || !p.endsWith(PATH_SUFFIX)) {
            return null;
        }
        final byte[] ogg = FILES.get(p.substring(PATH_PREFIX.length(), p.length() - PATH_SUFFIX.length()));
        if (ogg == null) {
            return CompletableFuture.failedFuture(new IOException("bot audio " + path + " is not loaded"));
        }
        try {
            final ByteArrayInputStream in = new ByteArrayInputStream(ogg);
            return CompletableFuture.completedFuture(looping ? new LoopingAudioStream(JOrbisAudioStream::new, in) : new JOrbisAudioStream(in));
        } catch (final IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
