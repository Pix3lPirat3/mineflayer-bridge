package io.github.pix3lpirat3.mfbridge.core;

import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.IDENTITY;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.P;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.decl.InterceptDecl;
import io.github.pix3lpirat3.mfbridge.core.input.InterceptControls;
import io.github.pix3lpirat3.mfbridge.core.input.InterceptOutcome;
import io.github.pix3lpirat3.mfbridge.core.input.InvocationResult;
import io.github.pix3lpirat3.mfbridge.core.remote.DeviceControls;
import io.github.pix3lpirat3.mfbridge.core.remote.PairingSession;
import io.github.pix3lpirat3.mfbridge.core.remote.PermissionBatch;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.InterceptData;
import io.github.pix3lpirat3.mfbridge.protocol.core.InterceptEvent;
import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import io.github.pix3lpirat3.mfbridge.protocol.core.TargetSpec;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InterceptTest {
    static final String FARMER = "farmer@example.com";
    static final String F = "bot:" + FARMER + "/";
    static final TargetSpec CHESTS = new TargetSpec("block", List.of("minecraft:chest"), null);
    static final Target CHEST = new Target("block", new Vec3i(1, 64, 2), "up", null, null, null, null);

    @TempDir
    Path dir;

    static InterceptData rule(String input, String mode, @Nullable String modifier, String priority) {
        return new InterceptData(CHESTS, input, mode, modifier, priority);
    }

    /** Miner and Farmer, each with rules on chests at several priorities. */
    static int[] declare(Harness h, FakePlugin p) {
        int miner = p.openBot("Miner", "example.com").sessionId();
        int farmer = p.openBot("Farmer", "example.com").sessionId();
        p.upsert(miner,
                obj(P + "chests/~intercept/watch", CoreKinds.INTERCEPT, rule("secondary", "observe", null, "monitor")),
                obj(P + "chests/~intercept/deposit", CoreKinds.INTERCEPT, rule("secondary", "cancel", null, "normal")),
                obj(P + "chests/~intercept/sneak", CoreKinds.INTERCEPT, rule("secondary", "observe", "shift", "lowest")),
                obj(P + "chests/~intercept/punch", CoreKinds.INTERCEPT, rule("primary", "observe", null, "normal")));
        p.upsert(farmer,
                obj(F + "farm/~intercept/early", CoreKinds.INTERCEPT, rule("secondary", "observe", null, "low")),
                obj(F + "farm/~intercept/grab", CoreKinds.INTERCEPT, rule("secondary", "cancel", null, "high")),
                obj(F + "farm/~intercept/late", CoreKinds.INTERCEPT, rule("secondary", "observe", null, "highest")));
        p.sync();
        h.tick();
        return new int[] {miner, farmer};
    }

    static List<String> keys(List<InterceptDecl> rules) {
        return rules.stream().map(InterceptDecl::key).toList();
    }

    static InterceptEvent event(FakePlugin p, int session) {
        return p.await(CoreMessages.INTERCEPT_EVENT, session);
    }

    @Test
    void rulesMatchByInputModifierAndTargetInPriorityOrder() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            declare(h, p);
            InterceptControls c = h.core.intercepts();
            assertEquals(7, h.core.declarations().intercepts().size());

            assertEquals(List.of(F + "farm/~intercept/late", F + "farm/~intercept/grab", P + "chests/~intercept/deposit",
                    F + "farm/~intercept/early", P + "chests/~intercept/watch"),
                    keys(c.matching(InterceptControls.SECONDARY, 0, spec -> true)), "highest first, monitor last");
            assertEquals(P + "chests/~intercept/sneak",
                    c.matching(InterceptControls.SECONDARY, 1, spec -> true).get(4).key(), "shift rules need shift; lowest runs last");
            assertEquals(List.of(P + "chests/~intercept/punch"), keys(c.matching(InterceptControls.PRIMARY, 7, spec -> true)));
            assertTrue(c.matching(InterceptControls.SECONDARY, 0, spec -> false).isEmpty(), "the target spec must match");

            c.setPaused(true);
            assertTrue(c.matching(InterceptControls.SECONDARY, 0, spec -> true).isEmpty(), "paused rules never match");
            c.setPaused(false);

            h.core.attach().setChoice(FARMER, false);
            h.tick();
            assertEquals(List.of(P + "chests/~intercept/deposit", P + "chests/~intercept/watch"),
                    keys(c.matching(InterceptControls.SECONDARY, 0, spec -> true)), "detached bots declare nothing");
        }
    }

    @Test
    void remoteCancelNeedsAGrantAskedInOneBatchAndOtherwiseObserves() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {})) {
            PairingSession pairing = h.core.pairing().begin(null);
            try (FakePlugin p = RemoteTest.tls(h, "remote-1")) {
                RemoteTest.pair(h, p, pairing.code());
                int[] s = declare(h, p);
                InterceptControls c = h.core.intercepts();
                List<InterceptDecl> rules = c.matching(InterceptControls.SECONDARY, 0, spec -> true);

                assertFalse(c.mayCancel(IDENTITY), "cancel is off by default for remote bots");
                InterceptOutcome outcome = c.dispatch(rules, CHEST);
                assertFalse(outcome.cancelled());
                assertNull(outcome.result());
                assertEquals(5, outcome.delivered());
                p.sync();
                for (int i = 0; i < 2; i++) {
                    assertFalse(event(p, s[0]).cancelled());
                }
                for (int i = 0; i < 3; i++) {
                    assertFalse(event(p, s[1]).cancelled());
                }

                assertNull(h.core.devices().permissionBatch(), "requests are collected first");
                h.clock.advanceMs(DeviceControls.PROMPT_COLLECT_MS);
                PermissionBatch batch = h.core.devices().permissionBatch();
                assertNotNull(batch);
                assertEquals(List.of(new PermissionBatch.InterceptCancel(FARMER, "Farmer", "secondary on block"),
                        new PermissionBatch.InterceptCancel(IDENTITY, "Miner", "secondary on block")), batch.items(),
                        "one item per bot, in key order");
                h.core.devices().answerPermissions(batch, List.of(batch.items().get(1)));
                assertNull(h.core.devices().permissionBatch(), "answered bots are not asked again");
                assertTrue(c.mayCancel(IDENTITY));
                assertEquals(Boolean.TRUE, c.cancelPermission(IDENTITY), "allow is saved");
                assertFalse(c.mayCancel(FARMER), "an unchecked bot is denied");
                assertNull(c.cancelPermission(FARMER), "deny lasts for this run");
                assertFalse(c.mayCancel(null), "controllers never cancel");
            }
        }
    }

    @Test
    void localBotsMayCancelWithoutAsking() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            declare(h, p);
            InterceptControls c = h.core.intercepts();
            assertTrue(c.mayCancel(IDENTITY), "bots of local devices may cancel");
            assertNull(c.cancelPermission(IDENTITY), "nothing is saved");
            h.clock.advanceMs(DeviceControls.PROMPT_COLLECT_MS);
            assertNull(h.core.devices().permissionBatch(), "local devices are never prompted");

            c.setCancelPermission(FARMER, false);
            assertFalse(c.mayCancel(FARMER), "the player's choice wins");
            InterceptOutcome outcome = c.dispatch(c.matching(InterceptControls.SECONDARY, 0, spec -> true), CHEST);
            assertEquals(P + "chests/~intercept/deposit", outcome.cancelledBy().key());
            assertFalse(c.mayCancel(null), "controllers never cancel");
        }
    }

    @Test
    void firstGrantedCancelWinsAndLaterRulesHearItWasCancelled() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int[] s = declare(h, p);
            InterceptControls c = h.core.intercepts();
            c.setCancelPermission(IDENTITY, true);
            c.setCancelPermission(FARMER, true);
            assertEquals(Boolean.TRUE, c.cancelPermission(IDENTITY));

            InterceptOutcome outcome = c.dispatch(c.matching(InterceptControls.SECONDARY, 0, spec -> true), CHEST);
            assertTrue(outcome.cancelled());
            assertEquals(F + "farm/~intercept/grab", outcome.cancelledBy().key(), "high runs before normal");
            p.sync();
            InterceptEvent late = event(p, s[1]);
            assertEquals(F + "farm/~intercept/late", late.intercept());
            assertFalse(late.cancelled(), "rules before the cancel hear it uncancelled");
            InterceptEvent grab = event(p, s[1]);
            assertEquals(F + "farm/~intercept/grab", grab.intercept());
            assertFalse(grab.cancelled(), "the cancelling rule itself was not cancelled earlier");
            assertEquals(CHEST, grab.target());
            assertTrue(event(p, s[1]).cancelled(), "low hears the cancel");
            InterceptEvent deposit = event(p, s[0]);
            assertEquals(P + "chests/~intercept/deposit", deposit.intercept());
            assertTrue(deposit.cancelled(), "a later cancel rule only observes");
            InterceptEvent watch = event(p, s[0]);
            assertTrue(watch.cancelled(), "monitor hears the cancel");

            CompletableFuture<InvocationResult> result = outcome.result();
            assertNotNull(result);
            InvocationTest.answer(p, s[0], grab.invocation(), false, "from another bot is ignored");
            InvocationTest.answer(p, s[1], late.invocation(), true, "observers are not tracked");
            InvocationTest.answer(p, s[1], grab.invocation(), false, "Chest is full");
            h.tickUntil("result", result::isDone);
            assertEquals(new InvocationResult(false, "Chest is full"), result.join());

            c.setCancelPermission(IDENTITY, null);
            assertNull(c.cancelPermission(IDENTITY));
            assertSame(InterceptOutcome.NONE, c.dispatch(List.of(), CHEST));
        }
    }

    @Test
    void monitorRulesNeverCancel() {
        InterceptDecl monitor = new InterceptDecl(P + "x/~intercept/m", "Miner", IDENTITY, rule("secondary", "cancel", null, "monitor"));
        assertFalse(monitor.wantsCancel());
        assertEquals(InterceptDecl.MONITOR, monitor.rank());
        assertEquals(2, new InterceptDecl("k", "Miner", IDENTITY, rule("secondary", "cancel", null, "bogus")).rank());
        assertEquals(-1, new InterceptDecl("k", "Miner", IDENTITY, rule("secondary", "cancel", "meta", "low")).modifierBit());
    }

    @Test
    void eachRuleGetsTheTargetOfItsOwnTypeAndUnresolvedTypesAreSkipped() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int miner = p.openBot("Miner", "example.com").sessionId();
            p.upsert(miner,
                    obj(P + "bots/~intercept/menu", CoreKinds.INTERCEPT,
                            new InterceptData(new TargetSpec("bot", null, null), "secondary", "observe", "shift", "normal")),
                    obj(P + "bots/~intercept/any", CoreKinds.INTERCEPT,
                            new InterceptData(new TargetSpec("entity", null, null), "secondary", "observe", "shift", "low")),
                    obj(P + "bots/~intercept/chest", CoreKinds.INTERCEPT, rule("secondary", "observe", "shift", "lowest")));
            p.sync();
            h.tick();
            Target bot = new Target("bot", null, null, 7, null, null, "bot:farmer@example.com");
            Target entity = new Target("entity", null, null, 7, null, null, null);
            InterceptControls c = h.core.intercepts();

            InterceptOutcome outcome = c.dispatch(c.matching(InterceptControls.SECONDARY, 1, spec -> true),
                    type -> switch (type) {
                        case "bot" -> bot;
                        case "entity" -> entity;
                        default -> null;
                    });
            assertEquals(2, outcome.delivered(), "the block rule has no target on a player and hears nothing");
            p.sync();
            assertEquals(bot, event(p, miner).target(), "the bot rule sees the player as a bot");
            assertEquals(entity, event(p, miner).target(), "the entity rule sees the same player as an entity");
        }
    }
}
