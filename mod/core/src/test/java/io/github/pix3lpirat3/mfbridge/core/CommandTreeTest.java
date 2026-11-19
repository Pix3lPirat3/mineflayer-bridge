package io.github.pix3lpirat3.mfbridge.core;

import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.P;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.failure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.chat.ChatRoute;
import io.github.pix3lpirat3.mfbridge.core.command.BotCommands;
import io.github.pix3lpirat3.mfbridge.core.command.CommandControls;
import io.github.pix3lpirat3.mfbridge.core.command.Completion;
import io.github.pix3lpirat3.mfbridge.core.command.ParseResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.ArgType;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandArg;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandComplete;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandInvoked;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandNode;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandSuggestions;
import io.github.pix3lpirat3.mfbridge.protocol.core.CompletionSpec;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.Suggestion;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandTreeTest {
    static final String TEAM = P + "teams/~cmd/team";

    @TempDir
    Path dir;

    static CommandData team() {
        CommandArg name = new CommandArg("team", ArgType.STRING, false, null, null, null, null, null,
                new CompletionSpec("dynamic"));
        CommandNode join = new CommandNode("join", List.of("j"), "Join a team", List.of(name));
        CommandNode leave = new CommandNode("leave", null, null, List.of());
        return new CommandData("team", "Teams", List.of(), null, List.of(join, leave), false, false);
    }

    static int declare(Harness h, FakePlugin p, long rev) {
        int s = p.openBot("Miner", "example.com").sessionId();
        p.upsert(s, FakePlugin.object(TEAM, CoreKinds.COMMAND, rev, CoreKinds.COMMAND.encode(team())));
        p.sync();
        h.tick();
        return s;
    }

    static void answer(FakePlugin p, int s, long req, String... texts) {
        List<Suggestion> list = new java.util.ArrayList<>();
        for (String t : texts) {
            list.add(new Suggestion(t));
        }
        p.send(s, CoreMessages.COMMAND_SUGGESTIONS, new CommandSuggestions(list), Envelope.RESPONSE, p.nextSeq(s),
                Envelope.NONE, req);
    }

    @Test
    void chatAddressesControllersByNameButNotWithAll() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            long req = p.request(0, CoreMessages.SESSION_OPEN, new SessionOpen(SessionKind.CONTROLLER, "fleet", null, List.of()));
            int s = p.await("opened", f -> f.is(CoreMessages.SESSION_OPENED) && f.env().req() == req)
                    .decode(CoreMessages.SESSION_OPENED).sessionId();
            p.upsert(s, FakePlugin.object("ctl:dev/fleet/teams/~cmd/team", CoreKinds.COMMAND, 1, CoreKinds.COMMAND.encode(team())));
            p.sync();
            h.tick();

            assertInstanceOf(ChatRoute.CapturedCommand.class, h.core.chat().route("@fleet team j blue"));
            assertEquals(List.of("join"), p.await(CoreMessages.COMMAND_INVOKED, s).path());
            assertInstanceOf(ParseResult.Failure.class, h.core.chat().validate("@fleet team dance"));
            assertEquals("leave", h.core.chat().complete("@fleet team l", 13).join().suggestions().getFirst().text());
            assertEquals("fleet", h.core.chat().complete("@fl", 3).join().suggestions().getFirst().text());
            assertInstanceOf(ChatRoute.PassThrough.class, h.core.chat().route("@steve hi"));
            assertInstanceOf(ChatRoute.PassThrough.class, h.core.chat().route("@all team j blue"), "all means the bots");
        }
    }

    @Test
    void subcommandsRunWithTheirPathAndGroupsShowHelp() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = declare(h, p, 1);
            h.core.invokeCommand(TEAM, List.of("join"), Map.of("team", "red"));
            CommandInvoked ci = p.await(CoreMessages.COMMAND_INVOKED, s);
            assertEquals(List.of("join"), ci.path());
            assertEquals(Map.of("team", "red"), ci.args());
            assertEquals("invalid_message", failure(h.core.invokeCommand(TEAM, Map.of())).code(), "groups do not run");
            assertEquals("invalid_message", failure(h.core.invokeCommand(TEAM, List.of("dance"), Map.of())).code());

            ChatRoute.CapturedHelp help = assertInstanceOf(ChatRoute.CapturedHelp.class, h.core.chat().route("@miner team"));
            assertEquals("Miner", help.ownerName());
            assertTrue(help.lines().get(1).startsWith("  team join <team:string>"), help.lines().toString());
            assertInstanceOf(ChatRoute.CapturedCommand.class, h.core.chat().route("@miner team j blue"));
            assertEquals(List.of("join"), p.await(CoreMessages.COMMAND_INVOKED, s).path(), "aliases send canonical names");

            h.core.chat().runCommandLine(InputTestSupport.OWNER, "team leave");
            assertEquals(List.of("leave"), p.await(CoreMessages.COMMAND_INVOKED, s).path());

            ParseResult.Failure f = assertInstanceOf(ParseResult.Failure.class, h.core.chat().validate("@miner team dance"));
            assertEquals(12, f.start());
            assertEquals(17, f.end());
            Completion c = h.core.chat().complete("@miner team l", 13).join();
            assertEquals(12, c.start());
            assertEquals("leave", c.suggestions().getFirst().text());
            assertEquals(null, h.core.chat().complete("@steve hi", 9), "other names keep vanilla suggestions");

            BotCommands bot = h.core.commands().bot("MINER");
            assertEquals(1, bot.commands().size());
        }
    }

    @Test
    void dynamicCompletionIsDebouncedCachedAndGivesUp() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = declare(h, p, 1);
            CommandControls commands = h.core.commands();

            CompletableFuture<List<Suggestion>> first = commands.completeDynamic(TEAM, List.of("join"), "team", "r", Map.of());
            CompletableFuture<List<Suggestion>> second = commands.completeDynamic(TEAM, List.of("join"), "team", "re", Map.of());
            assertTrue(first.isDone(), "a newer call replaces one still in its debounce");
            assertEquals(List.of(), first.join());
            h.tick();
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.COMMAND_COMPLETE)), "nothing is sent before 100 ms");

            h.clock.advanceMs(CommandControls.DEBOUNCE_MS);
            FakePlugin.Frame req = p.await("command.complete", f -> f.is(CoreMessages.COMMAND_COMPLETE));
            CommandComplete m = req.decode(CoreMessages.COMMAND_COMPLETE);
            assertEquals(new CommandComplete(TEAM, List.of("join"), "team", "re", Map.of()), m);
            answer(p, s, req.env().req(), "red", "reef");
            h.tickUntil("answer", second::isDone);
            assertEquals(List.of(new Suggestion("red"), new Suggestion("reef")), second.join());

            CompletableFuture<List<Suggestion>> cached = commands.completeDynamic(TEAM, List.of("join"), "team", "re", Map.of());
            assertTrue(cached.isDone(), "the same prefix is answered from the cache");

            CompletableFuture<List<Suggestion>> slow = commands.completeDynamic(TEAM, List.of("join"), "team", "ree", Map.of());
            h.clock.advanceMs(CommandControls.DEBOUNCE_MS);
            FakePlugin.Frame slowReq = p.await("second command.complete", f -> f.is(CoreMessages.COMMAND_COMPLETE));
            h.clock.advanceMs(CoreMessages.COMMAND_COMPLETE.timeoutMs());
            h.tick();
            assertTrue(slow.isDone(), "gives up after 300 ms");
            assertEquals(List.of(new Suggestion("reef")), slow.join(), "falls back to the cached shorter prefix");
            answer(p, s, slowReq.env().req(), "reed");
            p.sync();
            h.tick();
            assertFalse(commands.completeDynamic(TEAM, List.of("join"), "team", "ree", Map.of()).isDone(),
                    "a late answer is dropped, not cached");

            p.upsert(s, FakePlugin.object(TEAM, CoreKinds.COMMAND, 2, CoreKinds.COMMAND.encode(team())));
            p.sync();
            h.tick();
            assertFalse(commands.completeDynamic(TEAM, List.of("join"), "team", "re", Map.of()).isDone(),
                    "a new declaration revision invalidates the cache");
            assertTrue(commands.completeDynamic(P + "x/~cmd/none", List.of(), "a", "", Map.of()).isDone());
        }
    }

    @Test
    void cachedAnswersExpireAndAreForgottenOnClose() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = declare(h, p, 1);
            CommandControls commands = h.core.commands();

            CompletableFuture<List<Suggestion>> first = commands.completeDynamic(TEAM, List.of("join"), "team", "", Map.of());
            h.clock.advanceMs(CommandControls.DEBOUNCE_MS);
            FakePlugin.Frame req = p.await("command.complete", f -> f.is(CoreMessages.COMMAND_COMPLETE));
            answer(p, s, req.env().req(), "red", "blue");
            h.tickUntil("answer", first::isDone);
            assertEquals(List.of(new Suggestion("red"), new Suggestion("blue")), first.join());

            h.clock.advanceMs(CommandControls.CACHE_FRESH_MS - 1);
            assertTrue(commands.completeDynamic(TEAM, List.of("join"), "team", "", Map.of()).isDone(), "a fresh answer is reused");

            h.clock.advanceMs(1);
            CompletableFuture<List<Suggestion>> again = commands.completeDynamic(TEAM, List.of("join"), "team", "", Map.of());
            assertFalse(again.isDone(), "an old answer is asked for again");
            h.clock.advanceMs(CommandControls.DEBOUNCE_MS);
            req = p.await("second command.complete", f -> f.is(CoreMessages.COMMAND_COMPLETE));
            answer(p, s, req.env().req(), "blue");
            h.tickUntil("second answer", again::isDone);
            assertEquals(List.of(new Suggestion("blue")), again.join(), "the new answer replaces the old one");

            h.clock.advanceMs(CommandControls.CACHE_FRESH_MS);
            CompletableFuture<List<Suggestion>> slow = commands.completeDynamic(TEAM, List.of("join"), "team", "b", Map.of());
            h.clock.advanceMs(CommandControls.DEBOUNCE_MS);
            p.await("third command.complete", f -> f.is(CoreMessages.COMMAND_COMPLETE));
            h.clock.advanceMs(CoreMessages.COMMAND_COMPLETE.timeoutMs());
            h.tick();
            assertEquals(List.of(new Suggestion("blue")), slow.join(), "a timeout still falls back to an old answer");

            commands.forgetCompletions();
            CompletableFuture<List<Suggestion>> closed = commands.completeDynamic(TEAM, List.of("join"), "team", "b", Map.of());
            h.clock.advanceMs(CommandControls.DEBOUNCE_MS);
            p.await("fourth command.complete", f -> f.is(CoreMessages.COMMAND_COMPLETE));
            h.clock.advanceMs(CoreMessages.COMMAND_COMPLETE.timeoutMs());
            h.tick();
            assertEquals(List.of(), closed.join(), "nothing is left after the chat closes");
        }
    }
}
