package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.pix3lpirat3.mfbridge.core.Presence;
import io.github.pix3lpirat3.mfbridge.core.decl.CrewDecl;
import io.github.pix3lpirat3.mfbridge.protocol.core.CrewData;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CrewRosterTest {
    @Test
    void listsCrewBotsWithoutARecordOnce() {
        final List<CrewDecl> crews = List.of(
                new CrewDecl("ctl:a/core:crew", "ctl:a", "eco", new CrewData(List.of("Miner", "Farmer", "farmer"))),
                new CrewDecl("ctl:b/core:crew", "ctl:b", "other", new CrewData(List.of("miner", "Scout"))));
        final List<Presence> presence = List.of(new Presence("uuid-1", "miner", false, null, 0, null));
        assertEquals(List.of(new CrewRoster.Unseen("Farmer", "eco"), new CrewRoster.Unseen("Scout", "other")),
                CrewRoster.unseen(crews, presence));
    }
}
