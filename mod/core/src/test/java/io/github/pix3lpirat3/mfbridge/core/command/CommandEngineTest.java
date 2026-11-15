package io.github.pix3lpirat3.mfbridge.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.decl.CommandDecl;
import io.github.pix3lpirat3.mfbridge.protocol.core.ArgType;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandArg;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandNode;
import io.github.pix3lpirat3.mfbridge.protocol.core.CompletionSpec;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.Suggestion;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class CommandEngineTest {
    static final ArgContext CTX = new ArgContext() {
        @Override
        public Vec3i originBlock() {
            return new Vec3i(10, 64, -5);
        }

        @Override
        public Vec3d originExact() {
            return new Vec3d(10.5, 64, -4.5);
        }

        @Override
        public Vec3i crosshairBlock() {
            return new Vec3i(1, 2, 3);
        }

        @Override
        public Vec3d crosshairPos() {
            return new Vec3d(1.5, 2.5, 3.5);
        }

        @Override
        public boolean blockExists(String id) {
            return id.equals("minecraft:stone") || id.equals("minecraft:iron_ore");
        }

        @Override
        public boolean itemExists(String id) {
            return true;
        }

        @Override
        public EntityQuery entities() {
            return ENTITIES;
        }
    };

    static final EntityQuery ENTITIES = new EntityQuery() {
        @Override
        public Integer player() {
            return 1;
        }

        @Override
        public @Nullable Integer bot(String username) {
            return username.equals("Miner") ? 2 : null;
        }

        @Override
        public Integer looked() {
            return 7;
        }

        @Override
        public @Nullable Integer named(String name) {
            return name.equalsIgnoreCase("steve") ? 3 : null;
        }

        @Override
        public List<Integer> find(Selector.Filter filter, Vec3d from, int limit) {
            List<Integer> all = filter.types().contains("minecraft:cow") ? List.of(20, 21, 22) : List.of(12, 13);
            if (filter.fromBot() && from.x() != 100) {
                throw new AssertionError("from=bot must measure from the bot");
            }
            return all.subList(0, Math.min(limit, all.size()));
        }

        @Override
        public @Nullable Vec3d position(int entityId) {
            return entityId == 2 ? new Vec3d(100, 70, 100) : new Vec3d(entityId, 0, 0);
        }
    };

    static CommandArg arg(String name, ArgType type) {
        return new CommandArg(name, type, false, null, null, null, null, null, null);
    }

    static CommandArg optional(String name, ArgType type, @Nullable Object def) {
        return new CommandArg(name, type, def == null, def, null, null, null, null, null);
    }

    /** {@code mine start <block> [count=64]}, {@code mine stop}, {@code mine status}, and {@code say <text...>}. */
    static BotCommands miner() {
        CommandNode start = new CommandNode("start", List.of("go"), "Start mining",
                List.of(arg("block", ArgType.BLOCK_ID), new CommandArg("count", ArgType.INT, false, 64, 1.0, 100.0, null, null, null)));
        CommandNode stop = new CommandNode("stop", null, "Stop", List.of());
        CommandNode status = new CommandNode("status", List.of("st"), null, List.of());
        CommandData mine = new CommandData("mine", "Mining", List.of(), List.of("m"), List.of(start, stop, status), false, false);
        CommandData say = new CommandData("say", null, List.of(arg("text", ArgType.GREEDY)));
        CommandData go = new CommandData("goto", null, List.of(arg("pos", ArgType.BLOCK_POS), optional("fast", ArgType.BOOL, null)));
        CommandData kill = new CommandData("kill", null, List.of(arg("target", ArgType.ENTITY)));
        CommandData herd = new CommandData("herd", null, List.of(arg("animals", ArgType.ENTITY_LIST)));
        CommandArg mode = new CommandArg("mode", ArgType.CHOICE, false, null, null, null, List.of("fast", "safe"), null, null);
        CommandArg tag = new CommandArg("tag", ArgType.STRING, true, null, null, null, null, null, new CompletionSpec("dynamic"));
        CommandData set = new CommandData("set", null, List.of(mode, tag));
        List<CommandDecl> decls = new ArrayList<>();
        for (CommandData d : List.of(go, herd, kill, mine, say, set)) {
            decls.add(new CommandDecl("bot:miner@x/m/~cmd/" + d.name(), "Miner", "miner@x", Scope.GLOBAL, d));
        }
        return new BotCommands("Miner", "bot:miner@x", new Vec3d(0, 0, 0), decls);
    }

    static ParseResult parse(String line) {
        return CommandEngine.parse(miner(), line, CTX);
    }

    static ParseResult.Invocation ok(String line) {
        return assertInstanceOf(ParseResult.Invocation.class, parse(line), line);
    }

    static ParseResult.Failure fail(String line) {
        return assertInstanceOf(ParseResult.Failure.class, parse(line), line);
    }

    @Test
    void treesResolveByNameAndAliasIntoCanonicalPaths() {
        ParseResult.Invocation inv = ok("mine start stone 5");
        assertEquals("mine", inv.command().data().name());
        assertEquals(List.of("start"), inv.path());
        assertEquals(Map.of("block", "minecraft:stone", "count", 5L), inv.args());

        ParseResult.Invocation alias = ok("M GO iron_ore");
        assertEquals(List.of("start"), alias.path(), "aliases resolve to canonical names");
        assertEquals(64, alias.args().get("count"), "defaults are filled in");
        assertEquals(List.of("status"), ok("mine st").path());
        assertEquals(List.of("stop"), ok("mine stop").path());
    }

    @Test
    void groupingNodesAndMissingRequiredArgsAskForHelp() {
        ParseResult.Help help = assertInstanceOf(ParseResult.Help.class, parse("mine"));
        assertEquals(List.of(), help.path());
        List<String> lines = CommandUsage.help(List.of("mine"), help.node());
        assertEquals("mine <start|stop|status> - Mining", lines.get(0));
        assertEquals("  mine start <block:block_id> [count:int=64] - Start mining", lines.get(1));
        assertEquals(4, lines.size());
        ParseResult.Failure unknown = fail("mine dig");
        assertEquals(5, unknown.start());
        assertEquals(8, unknown.end());
        assertTrue(unknown.message().contains("expected start, stop, status"), unknown.message());
    }

    @Test
    void failuresCarryTheOffendingRange() {
        ParseResult.Failure noCommand = fail("dig stone");
        assertNull(noCommand.command());
        assertEquals(0, noCommand.start());
        assertEquals(3, noCommand.end());

        ParseResult.Failure badBlock = fail("mine start dirt");
        assertEquals(11, badBlock.start());
        assertEquals(15, badBlock.end());

        ParseResult.Failure range = fail("mine start stone 500");
        assertEquals(17, range.start());
        assertEquals("'count' must be at most 100", range.message());

        ParseResult.Failure missing = fail("mine start");
        assertEquals(10, missing.start());
        assertEquals(10, missing.end());
        assertEquals("Missing argument 'block'", missing.message());

        ParseResult.Failure extra = fail("mine stop now please");
        assertEquals(10, extra.start());
        assertEquals(20, extra.end());

        ParseResult.Failure partial = fail("goto 1 2");
        assertEquals("'pos' needs x, y and z", partial.message());
    }

    @Test
    void greedyOptionalAndPositions() {
        assertEquals("hello   there \"you\"", ok("say hello   there \"you\"").args().get("text"));
        assertEquals("one word", ok("say \"one word\"").args().get("text"));
        assertEquals(Map.of("pos", Map.of("x", 11L, "y", 64L, "z", -8L)), ok("goto ~1 ~ -8").args());
        assertEquals(Map.of("x", 1L, "y", 2L, "z", 3L), ok("goto here true").args().get("pos"));
        assertEquals(true, ok("goto here true").args().get("fast"));
        assertEquals(Map.of("x", 100L, "y", 70L, "z", 100L), ok("goto @self").args().get("pos"));
        assertEquals("safe", ok("set SAFE").args().get("mode"));
    }

    @Test
    void selectorsResolveToEntityIds() {
        assertEquals(1L, ok("kill @me").args().get("target"));
        assertEquals(2L, ok("kill @self").args().get("target"));
        assertEquals(7L, ok("kill @looked").args().get("target"));
        assertEquals(12L, ok("kill @nearest").args().get("target"));
        assertEquals(3L, ok("kill Steve").args().get("target"));
        assertEquals(42L, ok("kill 42").args().get("target"));
        assertEquals(20L, ok("kill @nearest[type=cow,from=bot]").args().get("target"));
        assertTrue(fail("kill @all").message().contains("@nearest"));
        assertEquals(5, fail("kill @foo").start());

        assertEquals(List.of(20L, 21L), ok("herd @all[type=cow,limit=2]").args().get("animals"));
        assertEquals(List.of(123L, 456L), ok("herd 123,456").args().get("animals"));
        assertEquals(List.of(123L, 7L), ok("herd 123,@looked").args().get("animals"));
        assertEquals(List.of(5L, 20L, 21L, 22L), ok("herd 5,@all[type=minecraft:cow,distance=..10]").args().get("animals"));
        ParseResult.Failure bad = fail("herd 1,,2");
        assertEquals(7, bad.start());
    }

    static Completion complete(CommandEngine e, String line) {
        return e.complete(miner(), line, line.length(), CTX).join();
    }

    static List<String> texts(Completion c) {
        List<String> out = new ArrayList<>();
        for (Suggestion s : c.suggestions()) {
            out.add(s.text());
        }
        return out;
    }

    @Test
    void completionOffersNamesAliasesChoicesAndSources() {
        Map<String, CompletionSource> sources = new HashMap<>();
        sources.put("block_ids", q -> List.of(new Suggestion("minecraft:stone"), new Suggestion("minecraft:iron_ore")));
        CommandEngine e = new CommandEngine(sources, DynamicCompleter.NONE);

        Completion roots = complete(e, "mi");
        assertEquals(0, roots.start());
        assertEquals(2, roots.end());
        assertEquals(List.of("mine"), texts(roots));
        assertEquals(List.of("mine"), texts(complete(e, "m")), "aliases stay out of the list by default");
        e.setShowAliases(true);
        assertEquals(List.of("m"), texts(complete(e, "m")).subList(1, 2), "when asked, aliases follow names once typed");
        e.setShowAliases(false);

        Completion children = complete(e, "mine s");
        assertEquals(5, children.start());
        assertEquals(List.of("start", "stop", "status"), texts(children), "no aliases unless asked");

        Completion block = complete(e, "mine go ");
        assertEquals(8, block.start());
        assertEquals(List.of("minecraft:stone", "minecraft:iron_ore"), texts(block));

        assertEquals(List.of("fast"), texts(complete(e, "set f")));
        assertEquals(List.of("here", "~ ~ ~", "@me", "@self", "@looked", "@nearest"), texts(complete(e, "goto ")));
        assertEquals(List.of("~"), texts(complete(e, "goto 1 ")));
        Completion bool = complete(e, "goto 1 2 3 t");
        assertEquals(11, bool.start());
        assertEquals(List.of("true"), texts(bool));
        assertEquals(List.of("@nearest[type=", "@nearest[tag="), texts(complete(e, "kill @nearest[t")));
        assertEquals(List.of("5,@all", "5,@nearest"), texts(complete(e, "herd 5,@")).subList(0, 2));
        assertEquals(List.of(), texts(complete(e, "mine stop ")));
        assertEquals(List.of(), texts(complete(e, "dig ")));
    }

    @Test
    void addressedLinesCompleteBotsThenCommands() {
        CommandEngine e = new CommandEngine(Map.of(), DynamicCompleter.NONE);
        BotCommands miner = miner();
        List<Addressee> book = List.of(new Addressee("all", Addressee.Kind.ALL, List.of(miner), List.of("Miner"), List.of(miner.owner())),
                new Addressee("Miner", Addressee.Kind.BOT, List.of(miner), List.of("Miner"), List.of(miner.owner())));
        Completion names = e.completeAddressed(book, "mi", 2, CTX).join();
        assertEquals(List.of("Miner"), texts(names));
        Completion all = e.completeAddressed(book, "a", 1, CTX).join();
        assertEquals(List.of("all"), texts(all));
        Completion cmd = e.completeAddressed(book, "miner mine st", 13, CTX).join();
        assertEquals(11, cmd.start());
        assertEquals(List.of("start", "stop", "status"), texts(cmd));
        CommandEngine.Address a = CommandEngine.address(book, "MINER  mine stop");
        assertEquals(7, a.restStart());
        assertEquals(1, a.sets().size());
        assertNull(CommandEngine.address(book, "nobody stop").to());
    }

    @Test
    void ordersParseWithTheBotsTheyAreFor() {
        CommandData go = new CommandData("go", null, List.of(new CommandArg("where", ArgType.STRING)), null, null, true, true);
        CommandDecl order = new CommandDecl("ctl:dev/eco/user:default/~order/go", "eco", null, Scope.GLOBAL, go);
        BotCommands farmers = new BotCommands("farmers", "ctl:dev/eco", null, List.of(order), List.of("eco1", "eco2"));
        BotCommands miner = miner();
        CommandEngine.Parsed p = CommandEngine.parseAll(List.of(miner, farmers), "go home", CTX);
        assertEquals(1, p.runs().size());
        assertEquals(List.of("eco1", "eco2"), p.runs().getFirst().invocation().bots());
        assertEquals("home", p.runs().getFirst().invocation().args().get("where"));
        CommandEngine.Parsed none = CommandEngine.parseAll(List.of(miner, farmers), "go", CTX);
        assertTrue(none.runs().isEmpty());
        assertEquals("farmers", none.failedIn());
        assertEquals(List.of(), CommandEngine.parseAll(List.of(miner), "mine stop", CTX).runs().getFirst().invocation().bots());
    }

    @Test
    void dynamicSourcesGetTheEarlierArguments() {
        List<Object[]> calls = new ArrayList<>();
        CommandEngine e = new CommandEngine(Map.of(), (key, path, arg, input, args) -> {
            calls.add(new Object[] {key, path, arg, input, args});
            return CompletableFuture.completedFuture(List.of(new Suggestion("red", "Red team", "5 players")));
        });
        Completion c = complete(e, "set safe r");
        assertEquals(List.of("red"), texts(c));
        assertEquals("Red team", c.suggestions().getFirst().display());
        Object[] call = calls.getFirst();
        assertEquals("bot:miner@x/m/~cmd/set", call[0]);
        assertEquals("tag", call[2]);
        assertEquals("r", call[3]);
        assertEquals(Map.of("mode", "safe"), call[4]);
    }

    @Test
    void formFieldsUseTheSameConversions() {
        BotCommands bot = miner();
        CommandDecl mine = bot.command("mine");
        ParseResult.Invocation inv = assertInstanceOf(ParseResult.Invocation.class,
                CommandEngine.parseFields(bot, mine, List.of("start"), Map.of("block", "stone", "count", ""), CTX));
        assertEquals(Map.of("block", "minecraft:stone", "count", 64), inv.args());
        ParseResult.Failure f = assertInstanceOf(ParseResult.Failure.class,
                CommandEngine.parseFields(bot, mine, List.of("start"), Map.of("count", "3"), CTX));
        assertEquals("'block' is required", f.message());
        CommandDecl go = bot.command("goto");
        ParseResult.Invocation pos = assertInstanceOf(ParseResult.Invocation.class,
                CommandEngine.parseFields(bot, go, List.of(), Map.of("pos", List.of("1", "", "3")), CTX));
        assertEquals(Map.of("x", 1L, "y", 64L, "z", 3L), pos.args().get("pos"));
    }

    @Test
    void tokensKeepTheirRanges() {
        Tokenizer.Tokens t = Tokenizer.split("say \"hello \\\"you\\\"\" next ");
        assertEquals(List.of("say", "hello \"you\"", "next"), t.texts());
        assertEquals(4, t.tokens().get(1).start());
        assertEquals(19, t.tokens().get(1).end());
        assertTrue(t.trailingSpace());
        assertEquals("\"a b\"", Tokenizer.quote("a b"));
        assertEquals(List.of("a b"), Tokenizer.split(Tokenizer.quote("a b")).texts());
    }
}
