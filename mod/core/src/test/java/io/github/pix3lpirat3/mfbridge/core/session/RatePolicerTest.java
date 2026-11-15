package io.github.pix3lpirat3.mfbridge.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.pix3lpirat3.mfbridge.core.session.RatePolicer.Verdict;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RatePolicerTest {

    static List<Verdict> run(RatePolicer p, long... messagesPerSecond) {
        List<Verdict> out = new ArrayList<>();
        for (long m : messagesPerSecond) {
            out.add(p.second(m, 0, 100, 1_000_000));
        }
        return out;
    }

    @Test
    void sustainedDoubleRateWarnsThenDisconnects() {
        RatePolicer p = new RatePolicer();
        List<Verdict> v = run(p, 201, 201, 201, 201, 201, 201, 201, 201, 201, 201);
        assertEquals(Verdict.WARN, v.get(4));
        assertEquals(Verdict.DISCONNECT, v.get(9));
        assertEquals(8, v.stream().filter(x -> x == Verdict.OK).count());
    }

    @Test
    void burstsAndCalmPeriodsForgive() {
        RatePolicer p = new RatePolicer();
        assertEquals(List.of(Verdict.OK, Verdict.OK, Verdict.OK, Verdict.OK, Verdict.OK, Verdict.OK),
                run(p, 201, 201, 201, 201, 200, 201), "a second at the limit breaks the run");
        run(p, 201, 201, 201);
        assertEquals(Verdict.WARN, run(p, 201).getFirst(), "the run continues from the last over-budget second");
        run(p, 0, 0, 0, 0, 0);
        assertEquals(Verdict.WARN, run(p, 201, 201, 201, 201, 201).get(4), "five calm seconds clear the warning");
        RatePolicer bytes = new RatePolicer();
        for (int i = 0; i < 4; i++) {
            assertEquals(Verdict.OK, bytes.second(1, 2_000_001, 100, 1_000_000));
        }
        assertEquals(Verdict.WARN, bytes.second(1, 2_000_001, 100, 1_000_000), "bytes count as well as messages");
    }
}
