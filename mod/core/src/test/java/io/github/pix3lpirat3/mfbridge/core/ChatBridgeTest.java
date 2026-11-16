package io.github.pix3lpirat3.mfbridge.core;

import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.IDENTITY;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.matchedBot;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.obj;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.chat.ChatControls;
import io.github.pix3lpirat3.mfbridge.core.chat.ChatPrint;
import io.github.pix3lpirat3.mfbridge.core.chat.ChatRoute;
import io.github.pix3lpirat3.mfbridge.core.input.FormInput;
import io.github.pix3lpirat3.mfbridge.core.input.InvocationResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.ArgType;
import io.github.pix3lpirat3.mfbridge.protocol.core.ChatClick;
import io.github.pix3lpirat3.mfbridge.protocol.core.ChatMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.ChatSpan;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandArg;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandInvoked;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.CrewData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CrewGroup;
import io.github.pix3lpirat3.mfbridge.protocol.core.FormField;
import io.github.pix3lpirat3.mfbridge.protocol.core.FormOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.FieldType;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionOpen;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatBridgeTest {
    static final String COMMAND = InvocationTest.COMMAND;

    @TempDir
    Path dir;

    static int declare(Harness h, FakePlugin p) {
        int s = p.openBot("Miner", "example.com").sessionId();
        p.upsert(s, obj(COMMAND, CoreKinds.COMMAND, InvocationTest.mineCommand()));
        p.sync();
        h.tick();
        return s;
    }

    static PromptRequest chatPrompt(PromptKind kind, Integer maxLength) {
        return new PromptRequest(kind, "What should I build?", null, null, 30_000, null, "chat", null, null, null,
                null, maxLength, null, null);
    }

    @Test
    void linesWithoutAKnownBotPassThroughAndDoubledPrefixEscapes() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            ChatControls chat = h.core.chat();
            assertEquals(new ChatRoute.PassThrough("@all hi"), chat.route("@all hi"), "all needs an attached bot");
            declare(h, p);
            assertEquals(new ChatRoute.PassThrough("hello"), chat.route("hello"));
            assertEquals(new ChatRoute.PassThrough("@steve hi"), chat.route("@steve hi"));
            assertEquals(new ChatRoute.PassThrough("@miner hi"), chat.route("@@miner hi"));
            assertEquals(new ChatRoute.PassThrough("@ miner"), chat.route("@ miner"));
            assertEquals(new ChatRoute.PassThrough("miner hi"), chat.route("miner hi"));
            assertFalse(chat.route("hello").captured());
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.CHAT_MESSAGE)), "nothing reaches the bot");

            assertInstanceOf(ChatRoute.Unknown.class, chat.route("@Miner"), "a bare name is captured but empty");
            assertEquals(new ChatRoute.Mistyped("minr", "Miner", "@Miner come here", "@@minr come here"), chat.route("@minr come here"),
                    "a near miss is kept from the server");
            assertInstanceOf(ChatRoute.Mistyped.class, chat.route("@minert@miner hi"), "a name run into the text");
            assertEquals(new ChatRoute.PassThrough("@mi"), chat.route("@@mi"), "the doubled prefix still sends it");
            assertEquals(new ChatRoute.PassThrough("@xy hi"), chat.route("@xy hi"), "far from every name");
            assertEquals(new ChatRoute.PassThrough("@mike hi"), chat.route("@mike hi"), "a player two edits from a short name");
            assertThrows(IllegalArgumentException.class, () -> chat.setPrefix(""));
            assertThrows(IllegalArgumentException.class, () -> chat.setPrefix("a b"));
            chat.setPrefix("!");
            assertEquals(new ChatRoute.PassThrough("@miner hi"), chat.route("@miner hi"));
            assertTrue(chat.route("!miner hi").captured());
        }
    }

    @Test
    void addressedTextRunsACommandOrIsDeliveredAsAMessage() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = declare(h, p);
            ChatControls chat = h.core.chat();

            ChatRoute.CapturedCommand cmd = assertInstanceOf(ChatRoute.CapturedCommand.class,
                    chat.route("@MINER mine minecraft:iron_ore 32 safe 1 2 3"));
            assertEquals("mine", cmd.command());
            assertEquals(1, cmd.runs().size());
            assertEquals("Miner", cmd.runs().get(0).ownerName());
            CommandInvoked ci = p.await(CoreMessages.COMMAND_INVOKED, s);
            assertEquals(COMMAND, ci.command());
            assertEquals(Map.of("block", "minecraft:iron_ore", "count", 32L, "mode", "safe",
                    "at", Map.of("x", 1L, "y", 2L, "z", 3L)), ci.args());
            InvocationTest.answer(p, s, ci.invocation(), true, "mining");
            h.tickUntil("result", () -> cmd.runs().get(0).result().isDone());
            assertEquals(new InvocationResult(true, "mining"), cmd.runs().get(0).result().join());

            ChatRoute.CapturedMessage free = assertInstanceOf(ChatRoute.CapturedMessage.class,
                    chat.route("@miner   what are you doing?"));
            assertEquals(List.of("Miner"), free.recipients());
            assertNull(free.commandError());
            assertEquals(new ChatMessage("what are you doing?", "@miner   what are you doing?", false),
                    p.await(CoreMessages.CHAT_MESSAGE, s));

            ChatRoute.CapturedMessage typo = assertInstanceOf(ChatRoute.CapturedMessage.class,
                    chat.route("@miner mine minecraft:stone lots"));
            assertTrue(String.valueOf(typo.commandError()).contains("'count'"), typo.commandError());
            assertEquals("mine minecraft:stone lots", p.await(CoreMessages.CHAT_MESSAGE, s).text());
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.COMMAND_INVOKED)), "a command that does not parse never runs");

            assertInstanceOf(ChatRoute.CapturedCommand.class, chat.route("@miner mine gold_ore"));
            CommandInvoked bare = p.await(CoreMessages.COMMAND_INVOKED, s);
            assertEquals("minecraft:gold_ore", bare.args().get("block"), "the engine adds the namespace");
            assertEquals(64L, bare.args().get("count"), "defaults are filled in");
            assertNull(bare.path(), "a root command has no path");
        }
    }

    @Test
    void allFansOutToEveryAttachedBot() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int miner = declare(h, p);
            int farmer = p.openBot("Farmer", "example.com").sessionId();
            p.sync();
            h.tick();
            ChatControls chat = h.core.chat();

            ChatRoute.CapturedMessage all = assertInstanceOf(ChatRoute.CapturedMessage.class, chat.route("@all come here"));
            assertEquals(List.of("Farmer", "Miner"), all.recipients());
            assertEquals(new ChatMessage("come here", "@all come here", true), p.await(CoreMessages.CHAT_MESSAGE, miner));
            assertEquals(new ChatMessage("come here", "@all come here", true), p.await(CoreMessages.CHAT_MESSAGE, farmer));

            ChatRoute.CapturedCommand cmd = assertInstanceOf(ChatRoute.CapturedCommand.class,
                    chat.route("@ALL mine minecraft:coal_ore"));
            assertEquals(List.of("Miner"), cmd.runs().stream().map(ChatRoute.Run::ownerName).toList(),
                    "only bots that have the command run it");
            p.await(CoreMessages.COMMAND_INVOKED, miner);
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.CHAT_MESSAGE)), "no message when the command ran");

            h.core.attach().setChoice(IDENTITY, false);
            h.tick();
            assertEquals(new ChatRoute.CapturedMessage(List.of("Miner"), null), chat.route("@miner hi"),
                    "a detached bot, one in another world, still gets the line");
            assertEquals(List.of("Farmer", "Miner"), assertInstanceOf(ChatRoute.CapturedMessage.class, chat.route("@all hi"))
                    .recipients());
        }
    }

    @Test
    void crewOrdersReachTheControllerOnceWithTheBotsAddressed() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int miner = declare(h, p);
            int farmer = p.openBot("Farmer", "example.com").sessionId();
            long req = p.request(0, CoreMessages.SESSION_OPEN, new SessionOpen(SessionKind.CONTROLLER, "eco", null, List.of()));
            int ctl = p.await("opened", f -> f.is(CoreMessages.SESSION_OPENED) && f.env().req() == req)
                    .decode(CoreMessages.SESSION_OPENED).sessionId();
            String order = "ctl:dev/eco/user:default/~order/go";
            String own = "ctl:dev/eco/user:default/~cmd/go";
            p.upsert(ctl, obj(order, CoreKinds.COMMAND, new CommandData("go", null,
                    List.of(new CommandArg("where", ArgType.STRING, false, null, null, null, null, null, null)), null, null, true, true)));
            p.upsert(ctl, obj(own, CoreKinds.COMMAND, new CommandData("go", "the suite's own go", List.of())));
            p.upsert(ctl, obj("ctl:dev/eco/user:default/~crew/crew", CoreKinds.CREW,
                    new CrewData(List.of("Miner", "farmer", "Absent"), List.of(new CrewGroup("diggers", List.of("miner", "Absent")),
                            new CrewGroup("Farmer", List.of("Miner"))))));
            p.sync();
            h.tick();
            ChatControls chat = h.core.chat();

            ChatRoute.CapturedCommand one = assertInstanceOf(ChatRoute.CapturedCommand.class, chat.route("@farmer go home"));
            assertEquals(List.of("Farmer"), p.await(CoreMessages.COMMAND_INVOKED, ctl).bots(), "an order to one bot names it");

            assertInstanceOf(ChatRoute.CapturedCommand.class, chat.route("@diggers go mine"));
            CommandInvoked group = p.await(CoreMessages.COMMAND_INVOKED, ctl);
            assertEquals(order, group.command());
            assertEquals(List.of("Miner"), group.bots(), "only attached members, in the bots' own names");

            ChatRoute.CapturedCommand all = assertInstanceOf(ChatRoute.CapturedCommand.class, chat.route("@all go home"));
            assertEquals(1, all.runs().size(), "one order for the whole crew, not one per bot");
            CommandInvoked everyone = p.await(CoreMessages.COMMAND_INVOKED, ctl);
            assertEquals(List.of("Miner", "Farmer"), everyone.bots());

            assertInstanceOf(ChatRoute.CapturedCommand.class, chat.route("@eco go"));
            CommandInvoked suite = p.await(CoreMessages.COMMAND_INVOKED, ctl);
            assertEquals(own, suite.command(), "the controller's own name reaches its own commands, not its orders");
            assertNull(suite.bots());

            ChatRoute.CapturedMessage hello = assertInstanceOf(ChatRoute.CapturedMessage.class, chat.route("@diggers hello"));
            assertEquals(List.of("Miner"), hello.recipients(), "free text to a group goes to its bots");
            assertEquals(new ChatMessage("hello", "@diggers hello", true), p.await(CoreMessages.CHAT_MESSAGE, miner));
            assertEquals(new ChatRoute.Mistyped("diggrs", "diggers", "@diggers go", "@@diggrs go"), chat.route("@diggrs go"));

            assertTrue(one.runs().size() == 1);
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.COMMAND_INVOKED) && f.env().session() == farmer), "bots never get the order");
            assertTrue(h.core.invokeCommand(order, List.of(), Map.of("where", "x"), "screen").isCompletedExceptionally(),
                    "an order needs its bots");
        }
    }

    @Test
    void chatSurfacePromptsTakeTheNextLine() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = matchedBot(h, p, "Miner");
            ChatControls chat = h.core.chat();
            long text = p.reliableRequest(s, CoreMessages.PROMPT_REQUEST, chatPrompt(PromptKind.TEXT, 10));
            long confirm = p.reliableRequest(s, CoreMessages.PROMPT_REQUEST, chatPrompt(PromptKind.CONFIRM, null));
            p.sync();
            long textId = h.core.input().active().id();

            ChatRoute.CapturedPrompt tooLong = assertInstanceOf(ChatRoute.CapturedPrompt.class,
                    chat.route("a castle with towers"));
            assertEquals(textId, tooLong.promptId());
            assertTrue(String.valueOf(tooLong.error()).contains("10"), tooLong.error());
            assertEquals(textId, h.core.input().active().id(), "a rejected answer keeps the prompt");
            assertEquals(new ChatRoute.CapturedPrompt(textId, null), chat.route("@miner hut"));
            assertEquals(new PromptResult("@miner hut", false, "chat"), response(p, CoreMessages.PROMPT_RESULT, text));

            long confirmId = h.core.input().active().id();
            assertEquals(new ChatRoute.CapturedPrompt(confirmId, "Answer yes or no"), chat.route("maybe"));
            assertEquals(new ChatRoute.CapturedPrompt(confirmId, null), chat.route(" Y "));
            assertEquals(new PromptResult(true, false, "chat"), response(p, CoreMessages.PROMPT_RESULT, confirm));
            assertEquals(new ChatRoute.PassThrough("hello"), chat.route("hello"), "no prompt, no capture");

            p.reliableRequest(s, CoreMessages.PROMPT_REQUEST, chatPrompt(PromptKind.NUMBER, null));
            p.sync();
            assertEquals(new ChatRoute.PassThrough("12"), chat.route("12"), "number prompts fall back to the HUD");
        }
    }

    @Test
    void printedLinesArePostedForAttachedBots() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = declare(h, p);
            List<ChatPrint> printed = new ArrayList<>();
            h.core.events().subscribe(ChatPrint.TYPE, printed::add);
            List<ChatSpan> spans = List.of(new ChatSpan("Done mining. ", null, false, false, null, null, false, false, false),
                    new ChatSpan("[Again]", 0xFF22C55E, true, false, new ChatClick("run_command", "mine"), "Mine again", false, false, false));
            p.reliable(s, CoreMessages.CHAT_PRINT, new io.github.pix3lpirat3.mfbridge.protocol.core.ChatPrint(spans));
            p.sync();
            assertEquals(List.of(new ChatPrint("Miner", IDENTITY, InputTestSupport.OWNER, spans)), printed);

            h.core.attach().setChoice(IDENTITY, false);
            h.tick();
            p.reliable(s, CoreMessages.CHAT_PRINT, new io.github.pix3lpirat3.mfbridge.protocol.core.ChatPrint(spans));
            p.sync();
            assertEquals(1, printed.size(), "detached bots do not print");
            // Its commands still run from Bridge home: the world decides what a bot does, not who may order it.
            h.core.chat().runCommandLine(InputTestSupport.OWNER, "mine minecraft:stone 2");
            assertEquals(2L, p.await(CoreMessages.COMMAND_INVOKED, s).args().get("count"));
            h.core.attach().setChoice(IDENTITY, true);
            h.tick();
            h.core.chat().runCommandLine(InputTestSupport.OWNER, "mine minecraft:stone 5");
            CommandInvoked ci = p.await(CoreMessages.COMMAND_INVOKED, s);
            assertEquals(5L, ci.args().get("count"));
            assertEquals("invalid_message", InputTestSupport.failure(
                    h.core.chat().runCommandLine(InputTestSupport.OWNER, "dig")).code());
            assertEquals("invalid_message", InputTestSupport.failure(
                    h.core.chat().runCommandLine(InputTestSupport.OWNER, "mine minecraft:stone x")).code());
        }
    }

    @Test
    void formsUseTheirOwnTimeout() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = matchedBot(h, p, "Miner");
            long req = p.reliableRequest(s, CoreMessages.FORM_OPEN, new FormOpen("Farm", List.of(
                    new FormField("crop", FieldType.TEXT, null, null, null, null, null, null, false)), null, "dialog", 5_000, null));
            p.sync();
            FormInput form = assertInstanceOf(FormInput.class, h.core.input().active());
            assertEquals("dialog", form.request().surface());
            assertEquals(form.createdAtMs() + 5_000, form.deadlineMs());
            h.clock.advanceMs(5_000);
            InputTestSupport.errorResponse(p, req, "timeout", CoreMessages.FORM_OPEN);
        }
    }
}
