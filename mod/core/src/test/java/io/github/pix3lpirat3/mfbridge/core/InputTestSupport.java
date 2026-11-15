package io.github.pix3lpirat3.mfbridge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectFull;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldReport;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.jspecify.annotations.Nullable;

/** Shared fixtures for the input, declaration and settings tests. */
final class InputTestSupport {
    static final String IDENTITY = "miner@example.com";
    static final String OWNER = "bot:" + IDENTITY;
    static final String P = OWNER + "/";
    static final WorldKey BOT_WORLD = new WorldKey("example.com:25565", null, "minecraft:overworld");
    static final WorldKey PLAYER_WORLD = new WorldKey("Example.COM", null, "minecraft:overworld");
    static final WorldKey NETHER = new WorldKey("example.com", null, "minecraft:the_nether");

    private InputTestSupport() {}

    static <T> ObjectFull obj(String key, KindType<T> kind, T data) {
        return FakePlugin.object(key, kind, 1, kind.encode(data));
    }

    static <T> ObjectFull worldObj(String key, KindType<T> kind, WorldKey world, T data) {
        return FakePlugin.worldObject(key, kind, 1, world, kind.encode(data));
    }

    /** Opens a bot session that reports the overworld, with the player in the same world. */
    static int matchedBot(Harness h, FakePlugin p, String username) {
        int s = p.openBot(username, "example.com").sessionId();
        p.control(s, CoreMessages.WORLD_REPORT, new WorldReport(BOT_WORLD, null));
        h.core.setPlayerContext(PLAYER_WORLD);
        p.sync();
        h.tick();
        return s;
    }

    /** Awaits the success response to {@code req} and checks its envelope. */
    static <T> T response(FakePlugin p, MessageType<T> type, long req) {
        FakePlugin.Frame f = p.await(type.name() + " for req " + req, x -> x.is(type) && x.env().req() == req);
        assertTrue(f.env().has(Envelope.RESPONSE), "response flag");
        assertTrue(f.env().seq() != Envelope.NONE, "reliable response carries seq");
        return f.decode(type);
    }

    /** Awaits the error response to {@code req} and checks code, flags and relatesTo. */
    static void errorResponse(FakePlugin p, long req, String code, MessageType<?> request) {
        FakePlugin.Frame f = p.await("error for req " + req, x -> x.is(CoreMessages.ERROR) && x.env().req() == req);
        assertTrue(f.env().has(Envelope.RESPONSE) && f.env().has(Envelope.ERROR), "error response flags");
        ErrorMessage m = f.decode(CoreMessages.ERROR);
        assertEquals(code, m.error().code(), m.error().message());
        assertEquals(request.id(), m.relatesTo());
    }

    /** Asserts that {@code f} failed with {@code code} and a message containing {@code text}. */
    static void assertError(String code, String text, CompletableFuture<?> f) {
        assertError(code, text, f, null);
    }

    static void assertError(String code, String text, CompletableFuture<?> f, @Nullable String why) {
        BridgeException e = failure(f);
        assertEquals(code, e.code(), why);
        assertTrue(String.valueOf(e.getMessage()).contains(text), e.getMessage());
    }

    /** Returns the BridgeException a completed future failed with. */
    static BridgeException failure(CompletableFuture<?> f) {
        assertTrue(f.isDone(), "future completed");
        try {
            f.get();
        } catch (ExecutionException e) {
            return assertInstanceOf(BridgeException.class, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return fail("future succeeded: " + f.join());
    }
}
