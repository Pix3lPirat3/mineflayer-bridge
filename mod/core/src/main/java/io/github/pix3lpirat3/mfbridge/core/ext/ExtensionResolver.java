package io.github.pix3lpirat3.mfbridge.core.ext;

import io.github.pix3lpirat3.mfbridge.api.BridgeExtension;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Decides which mod-side extensions register and in what order. Hard
 * dependencies ({@link BridgeExtension#requires}) must be present, switched on and themselves
 * loadable; soft ones ({@link BridgeExtension#optional}) only order. Pure: no game or IO.
 */
public final class ExtensionResolver {

    /** Why an extension did or did not register. */
    public enum State {
        LOADED, DISABLED, MISSING_DEPENDENCY, CYCLE, DUPLICATE
    }

    /**
     * One extension's outcome.
     *
     * @param id the extension id
     * @param version its version
     * @param state what happened
     * @param reason a sentence for the player, or empty when loaded
     */
    public record Status(String id, String version, State state, String reason) {}

    /**
     * The outcome of a resolution.
     *
     * @param load extensions to register, dependencies first
     * @param statuses one per discovered extension, in discovery order
     */
    public record Resolution(List<BridgeExtension> load, List<Status> statuses) {
        public Resolution {
            load = List.copyOf(load);
            statuses = List.copyOf(statuses);
        }
    }

    private record Dep(String id, String range) {
        static Dep parse(String spec) {
            int at = spec.lastIndexOf('@');
            return at <= 0 ? new Dep(spec, "*") : new Dep(spec.substring(0, at), spec.substring(at + 1));
        }
    }

    private ExtensionResolver() {}

    /**
     * Resolves discovered extensions.
     *
     * @param found extensions in discovery order; first-party ones first
     * @param disabled ids the player switched off
     * @return what registers, in order, and every extension's state
     */
    public static Resolution resolve(List<BridgeExtension> found, Set<String> disabled) {
        Map<String, BridgeExtension> byId = new LinkedHashMap<>();
        Map<String, Status> statuses = new LinkedHashMap<>();
        List<Status> duplicates = new ArrayList<>();
        for (BridgeExtension e : found) {
            String id = e.id().toString();
            if (byId.putIfAbsent(id, e) != null) {
                duplicates.add(new Status(id, e.version().toString(), State.DUPLICATE, "another extension already uses this id"));
            } else if (disabled.contains(id)) {
                statuses.put(id, new Status(id, e.version().toString(), State.DISABLED, "switched off"));
            }
        }
        List<BridgeExtension> order = new ArrayList<>();
        Map<String, Integer> marks = new LinkedHashMap<>();
        for (BridgeExtension e : byId.values()) {
            visit(e, byId, statuses, marks, order, new ArrayList<>());
        }
        // A hard dependency that is missing, off, out of range or itself unloadable (a cycle included)
        // makes the dependent unloadable; repeat until nothing changes, so chains resolve.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (BridgeExtension e : byId.values()) {
                String id = e.id().toString();
                if (statuses.containsKey(id)) {
                    continue;
                }
                String problem = missing(e, byId, statuses);
                if (problem != null) {
                    statuses.put(id, new Status(id, e.version().toString(), State.MISSING_DEPENDENCY, problem));
                    changed = true;
                }
            }
        }
        List<Status> out = new ArrayList<>();
        for (BridgeExtension e : byId.values()) {
            String id = e.id().toString();
            Status s = statuses.get(id);
            out.add(s != null ? s : new Status(id, e.version().toString(), State.LOADED, ""));
        }
        out.addAll(duplicates);
        List<BridgeExtension> load = order.stream().filter(e -> !statuses.containsKey(e.id().toString())).toList();
        return new Resolution(load, out);
    }

    private static @Nullable String missing(BridgeExtension e, Map<String, BridgeExtension> byId, Map<String, Status> statuses) {
        for (String spec : e.requires()) {
            Dep d = Dep.parse(spec);
            BridgeExtension dep = byId.get(d.id());
            if (dep == null) {
                return "needs " + d.id() + ", which is not installed";
            }
            Status s = statuses.get(d.id());
            if (s != null) {
                return "needs " + d.id() + ", which is " + (s.state() == State.DISABLED ? "switched off" : "not loaded");
            }
            if (!dep.version().satisfies(d.range())) {
                return "needs " + d.id() + " " + d.range() + ", but " + dep.version() + " is installed";
            }
        }
        return null;
    }

    /** Depth-first, dependencies first; a cycle marks every extension on it. */
    private static void visit(BridgeExtension e, Map<String, BridgeExtension> byId, Map<String, Status> statuses,
            Map<String, Integer> marks, List<BridgeExtension> order, List<String> path) {
        String id = e.id().toString();
        Integer mark = marks.get(id);
        if (mark != null) {
            if (mark == 1) {
                List<String> cycle = new ArrayList<>(path.subList(path.indexOf(id), path.size()));
                cycle.add(id);
                String text = "dependency cycle " + String.join(" -> ", cycle);
                for (String member : cycle) {
                    BridgeExtension m = byId.get(member);
                    statuses.putIfAbsent(member, new Status(member, m.version().toString(), State.CYCLE, text));
                }
            }
            return;
        }
        marks.put(id, 1);
        path.add(id);
        List<String> deps = new ArrayList<>(e.requires());
        deps.addAll(e.optional());
        for (String spec : deps) {
            BridgeExtension dep = byId.get(Dep.parse(spec).id());
            if (dep != null) {
                visit(dep, byId, statuses, marks, order, path);
            }
        }
        path.removeLast();
        marks.put(id, 2);
        order.add(e);
    }
}
