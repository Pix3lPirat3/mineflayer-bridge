package io.github.pix3lpirat3.mfbridge.core;

import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.P;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.remote.Quota;
import io.github.pix3lpirat3.mfbridge.protocol.core.BlockHighlightData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorMessage;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetainedQuotaTest {

    @TempDir
    Path dir;

    static BlockHighlightData cells(int count) {
        return new BlockHighlightData().withPositions(new int[count * 3]);
    }

    @Test
    void retainedGeometryIsCountedPerOwnerAndReplacementsCountTheirGrowthOnly() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            h.core.devices().setQuota("dev", Quota.RETAINED_BYTES, 100);
            int s = p.openBot("Miner", "example.com").sessionId();
            p.upsert(s, obj(P + "fx/a", CoreKinds.BLOCK_HIGHLIGHT, cells(5)));
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.ERROR)), "60 of 100 bytes fit");

            p.upsert(s, FakePlugin.object(P + "fx/a", CoreKinds.BLOCK_HIGHLIGHT, 2, CoreKinds.BLOCK_HIGHLIGHT.encode(cells(8))));
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.ERROR)), "a replacement counts only what it adds: 96 bytes");

            p.upsert(s, obj(P + "fx/b", CoreKinds.BLOCK_HIGHLIGHT, cells(1)));
            ErrorMessage e = p.await(CoreMessages.ERROR, s);
            assertEquals("quota_exceeded", e.error().code());
            assertTrue(e.error().message().contains("retained geometry"), e.error().message());
        }
    }
}
