package io.github.pix3lpirat3.mfbridge.core.ext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.api.BridgeExtension;
import io.github.pix3lpirat3.mfbridge.api.ExtensionId;
import io.github.pix3lpirat3.mfbridge.api.ExtensionRegistrar;
import io.github.pix3lpirat3.mfbridge.api.SemVer;
import io.github.pix3lpirat3.mfbridge.core.ext.ExtensionResolver.Resolution;
import io.github.pix3lpirat3.mfbridge.core.ext.ExtensionResolver.State;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ExtensionResolverTest {

    record Ext(String name, String ver, List<String> requires, List<String> optional) implements BridgeExtension {
        static Ext of(String name, String... requires) {
            return new Ext(name, "1.2.0", List.of(requires), List.of());
        }

        @Override
        public ExtensionId id() {
            return ExtensionId.parse(name);
        }

        @Override
        public SemVer version() {
            return SemVer.parse(ver);
        }

        @Override
        public void register(ExtensionRegistrar registrar) {}
    }

    static List<String> order(Resolution r) {
        return r.load().stream().map(e -> e.id().toString()).toList();
    }

    static Map<String, State> states(Resolution r) {
        return r.statuses().stream().collect(Collectors.toMap(ExtensionResolver.Status::id, ExtensionResolver.Status::state, (a, b) -> a));
    }

    @Test
    void dependenciesRegisterFirstAndSoftOnesOnlyOrder() {
        Ext tasks = Ext.of("bridge:tasks");
        Ext map = new Ext("bridge:map", "1.0.0", List.of(), List.of("bridge:tasks", "bridge:absent"));
        Ext schematic = Ext.of("acme:schematic", "bridge:map@^1");
        Resolution r = ExtensionResolver.resolve(List.of(schematic, map, tasks), Set.of());
        assertEquals(List.of("bridge:tasks", "bridge:map", "acme:schematic"), order(r));
        assertTrue(r.statuses().stream().allMatch(s -> s.state() == State.LOADED));
    }

    @Test
    void aMissingOffOrOutOfRangeDependencyStopsTheChainWithAReason() {
        Resolution r = ExtensionResolver.resolve(List.of(
                Ext.of("bridge:console"), Ext.of("acme:a", "bridge:console"), Ext.of("acme:b", "acme:a"),
                Ext.of("acme:c", "acme:gone"), Ext.of("bridge:tasks"), Ext.of("acme:d", "bridge:tasks@^2")), Set.of("bridge:console"));
        Map<String, State> s = states(r);
        assertEquals(State.DISABLED, s.get("bridge:console"));
        assertEquals(State.MISSING_DEPENDENCY, s.get("acme:a"));
        assertEquals(State.MISSING_DEPENDENCY, s.get("acme:b"), "chains resolve");
        assertEquals(List.of("bridge:tasks"), order(r));
        String reasons = r.statuses().stream().map(ExtensionResolver.Status::reason).collect(Collectors.joining("|"));
        assertTrue(reasons.contains("needs bridge:console, which is switched off"), reasons);
        assertTrue(reasons.contains("needs acme:gone, which is not installed"), reasons);
        assertTrue(reasons.contains("needs bridge:tasks ^2, but 1.2.0 is installed"), reasons);
    }

    @Test
    void cyclesAndDuplicatesDoNotLoad() {
        Resolution r = ExtensionResolver.resolve(List.of(
                Ext.of("acme:a", "acme:b"), Ext.of("acme:b", "acme:a"), Ext.of("acme:c", "acme:a"), Ext.of("acme:ok"),
                Ext.of("acme:ok")), Set.of());
        Map<String, State> s = states(r);
        assertEquals(State.CYCLE, s.get("acme:a"));
        assertEquals(State.CYCLE, s.get("acme:b"));
        assertEquals(State.MISSING_DEPENDENCY, s.get("acme:c"));
        assertEquals(List.of("acme:ok"), order(r));
        assertTrue(r.statuses().stream().anyMatch(x -> x.state() == State.DUPLICATE));
        assertFalse(r.statuses().stream().filter(x -> x.state() == State.CYCLE).findFirst().orElseThrow().reason().isEmpty());
    }

    @Test
    void rangesMatchThePluginSyntax() {
        SemVer v = SemVer.parse("1.4.2");
        assertTrue(v.satisfies("^1"));
        assertTrue(v.satisfies("^1.2"));
        assertTrue(v.satisfies("~1.4"));
        assertTrue(v.satisfies(">=1.0.0 <2.0.0"));
        assertTrue(v.satisfies("*"));
        assertTrue(v.satisfies("1.4"));
        assertFalse(v.satisfies("^2"));
        assertFalse(v.satisfies("~1.3"));
        assertFalse(v.satisfies("1.4.3"));
        assertFalse(SemVer.parse("0.3.1").satisfies("^0.2"));
    }
}
