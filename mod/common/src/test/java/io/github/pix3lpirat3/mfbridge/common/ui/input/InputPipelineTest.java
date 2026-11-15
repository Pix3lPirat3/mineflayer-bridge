package io.github.pix3lpirat3.mfbridge.common.ui.input;

import io.github.pix3lpirat3.mfbridge.api.client.InputStage;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.pix3lpirat3.mfbridge.api.client.InputStage.Outcome;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InputPipelineTest {

    static InputStage stage(String name, Outcome outcome, List<String> seen) {
        return new InputStage() {
            @Override
            public Outcome scroll(double amount) {
                seen.add(name);
                return outcome;
            }
        };
    }

    @Test
    void stagesRunByPriorityThenRegistrationAndTheFirstThatActsWins() {
        List<String> seen = new ArrayList<>();
        InputPipeline p = new InputPipeline();
        p.add(InputPipeline.TOOLS, stage("tools", Outcome.CONSUMED, seen));
        p.add(InputPipeline.REQUEST, stage("request", Outcome.PASS, seen));
        p.add(InputPipeline.ELEMENTS, stage("elements", Outcome.PASS, seen));
        p.add(InputPipeline.ELEMENTS, stage("extension", Outcome.PASS, seen));
        assertEquals(Outcome.CONSUMED, p.scroll(1));
        assertEquals(List.of("request", "elements", "extension", "tools"), seen);
    }

    @Test
    void anOwningStageStopsTheChainWithoutConsuming() {
        List<String> seen = new ArrayList<>();
        InputPipeline p = new InputPipeline();
        p.add(InputPipeline.REQUEST, stage("pick", Outcome.OWNED, seen));
        p.add(InputPipeline.TOOLS, stage("tools", Outcome.CONSUMED, seen));
        assertEquals(Outcome.OWNED, p.scroll(1), "keybinds still get the input");
        assertEquals(List.of("pick"), seen);
        assertEquals(Outcome.PASS, new InputPipeline().scroll(1));
    }
}
