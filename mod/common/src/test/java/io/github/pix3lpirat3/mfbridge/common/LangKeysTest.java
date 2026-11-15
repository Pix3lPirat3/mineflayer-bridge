package io.github.pix3lpirat3.mfbridge.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Checks the lang files against the sources: core keys live in {@code assets/mineflayer_bridge}, each
 * first-party extension's in {@code assets/mineflayer_bridge_<ext>}, so it can move to its own jar.
 */
final class LangKeysTest {

    private static final Path JAVA = Path.of("src/main/java");
    private static final Path ASSETS = Path.of("src/main/resources/assets");
    private static final String CORE = "mineflayer_bridge";
    /** A string literal that names a key of ours. */
    private static final Pattern KEY_LITERAL = Pattern.compile("\"((?:key\\.|screen\\.)?mineflayer_bridge(?:_[a-z]+)?\\.[a-z0-9_.]+)\"");
    /** Keys built at runtime: settings rows and key mappings ({@code BridgeUi.keyMapping}). */
    private static final List<String> DYNAMIC = List.of("screen.mineflayer_bridge.settings", "key.mineflayer_bridge.",
            "key.category.mineflayer_bridge.");

    /** Key to the namespace of the file it is in. */
    private static Map<String, String> keys() throws IOException {
        final Map<String, String> keys = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(ASSETS)) {
            for (final Path file : files.filter(p -> p.endsWith(Path.of("lang", "en_us.json"))).toList()) {
                final String ns = ASSETS.relativize(file).getName(0).toString();
                try (JsonReader r = new JsonReader(new StringReader(Files.readString(file)))) {
                    r.beginObject();
                    while (r.hasNext()) {
                        final String key = r.nextName();
                        r.nextString();
                        final String before = keys.put(key, ns);
                        assertEquals(null, before, () -> "duplicate key " + key + " in " + ns + " and " + before);
                    }
                    r.endObject();
                }
            }
        }
        return keys;
    }

    /**
     * Namespace a key belongs to: its first segment, its second after {@code key.} and {@code screen.},
     * or its third after {@code key.category.}.
     */
    private static String namespace(final String key) {
        final String[] parts = key.split("\\.");
        if (parts[0].equals("key") && parts[1].equals("category")) {
            return parts[2];
        }
        return parts[0].equals("key") || parts[0].equals("screen") ? parts[1] : parts[0];
    }

    /** Key literals by the source file that uses them. */
    private static Map<String, List<String>> uses() throws IOException {
        final Map<String, List<String>> uses = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(JAVA)) {
            for (final Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                final Matcher m = KEY_LITERAL.matcher(Files.readString(file));
                while (m.find()) {
                    uses.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(JAVA.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        return uses;
    }

    @Test
    void everyUsedKeyExists() throws IOException {
        final Map<String, String> keys = keys();
        final TreeSet<String> missing = new TreeSet<>(uses().keySet());
        missing.removeAll(keys.keySet());
        assertTrue(missing.isEmpty(), () -> "keys used but not in any en_us.json: " + missing);
    }

    @Test
    void everyKeyIsUsed() throws IOException {
        final Map<String, List<String>> uses = uses();
        final TreeSet<String> unused = new TreeSet<>();
        for (final String key : keys().keySet()) {
            if (!uses.containsKey(key) && DYNAMIC.stream().noneMatch(key::startsWith)) {
                unused.add(key);
            }
        }
        assertTrue(unused.isEmpty(), () -> "keys in en_us.json that no source uses: " + unused);
    }

    @Test
    void keysLiveInTheirNamespace() throws IOException {
        final List<String> wrong = new ArrayList<>();
        for (final Map.Entry<String, String> e : keys().entrySet()) {
            // Key mappings keep their stable core names (options.txt stores them) in the extension's file.
            final boolean keybind = e.getKey().startsWith("key." + CORE + ".");
            if (!namespace(e.getKey()).equals(e.getValue()) && !keybind) {
                wrong.add(e.getKey() + " in " + e.getValue());
            }
        }
        assertTrue(wrong.isEmpty(), () -> "keys outside their namespace: " + wrong);
    }

    @Test
    void extensionKeysStayInTheirExtension() throws IOException {
        final List<String> wrong = new ArrayList<>();
        for (final Map.Entry<String, List<String>> e : uses().entrySet()) {
            final String ns = namespace(e.getKey());
            if (!ns.startsWith(CORE + "_")) {
                continue;
            }
            final String pkg = "/common/ext/" + ns.substring(CORE.length() + 1) + "/";
            for (final String file : e.getValue()) {
                if (!file.contains(pkg)) {
                    wrong.add(e.getKey() + " in " + file);
                }
            }
        }
        assertTrue(wrong.isEmpty(), () -> "extension keys used outside the extension: " + wrong);
    }
}
