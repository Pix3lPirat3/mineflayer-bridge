package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.protocol.core.ArgType;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandArg;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class BridgeStatusTest {
    private static final Text EN = EnglishText.INSTANCE;

    static final CommandData MINE = new CommandData("mine", "Mine a block type", List.of(
            new CommandArg("block", ArgType.BLOCK_ID),
            new CommandArg("count", ArgType.INT, false, 64, null, null, null, null, null),
            new CommandArg("mode", ArgType.CHOICE, true, null, null, null, List.of("fast", "safe"), null, null)));
    static final CommandData STOP = new CommandData("stop", null, List.of());

    @Test
    void statusListsBotsOrSaysNoneAreConnected() {
        assertEquals(List.of("The bridge endpoint is not running"), BridgeStatus.status(EN, false, 0, List.of()));
        assertEquals(List.of("Bridge endpoint listening on port 25590", "No bots connected. Start a bot with the mineflayer-bridge plugin."), BridgeStatus.status(EN, true, 25590, List.of()));
        final List<String> lines = BridgeStatus.status(EN, true, 25590, List.of(new BridgeStatus.Bot("Miner", true, true, 2),
                new BridgeStatus.Bot("Farmer", false, false, 1)));
        assertEquals("- Miner: attached, same world, 2 commands", lines.get(1));
        assertEquals("- Farmer: not attached, other world, 1 command", lines.get(2));
        assertTrue(lines.getLast().contains("/bridge help"));
    }

    @Test
    void signaturesMarkOptionalArgumentsAndChoices() {
        assertEquals("mine <block:block_id> [count:int=64] [mode:fast|safe]", BridgeStatus.signature(MINE));
        assertEquals("stop", BridgeStatus.signature(STOP));
    }

    @Test
    void commandListsShowEachSectionAndExplainWhenEmpty() {
        final List<BridgeStatus.Section> sections = List.of(new BridgeStatus.Section("Miner:", List.of(MINE, STOP)),
                new BridgeStatus.Section("Orders from eco, for Miner:", List.of(STOP)), new BridgeStatus.Section("Empty:", List.of()));
        assertEquals(List.of("Miner:", "  mine <block:block_id> [count:int=64] [mode:fast|safe] - Mine a block type", "  stop",
                "Orders from eco, for Miner:", "  stop"), BridgeStatus.commandList(EN, "Miner", sections));
        assertEquals(List.of("Builder has no commands."), BridgeStatus.commandList(EN, "Builder", List.of()));
        assertTrue(BridgeStatus.usage(EN).getFirst().startsWith("/bridge"));
    }

    @Test
    void commandTreesListEveryRunnableNode() {
        final CommandData tree = new CommandData("team", "Teams", List.of(), List.of("t"), List.of(
                new CommandNode("join", null, "Join", List.of(new CommandArg("name", ArgType.STRING))),
                new CommandNode("leave", null, null, List.of())), false, false);
        assertEquals(List.of("Miner:", "  team <join|leave> (t) - Teams", "    team join <name:string> - Join", "    team leave"),
                BridgeStatus.commandList(EN, "Miner", List.of(new BridgeStatus.Section("Miner:", List.of(tree)))));
    }

    @Test
    void botsAreFoundIgnoringCase() {
        assertEquals("Miner", BridgeStatus.findBot("MINER", List.of("Farmer", "Miner")));
        assertNull(BridgeStatus.findBot("Builder", List.of("Miner")));
    }
}
