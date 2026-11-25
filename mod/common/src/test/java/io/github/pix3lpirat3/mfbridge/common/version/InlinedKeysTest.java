package io.github.pix3lpirat3.mfbridge.common.version;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Key and button numbers changed in 26.3, and javac copies a constant into the class that names it,
 * where no link check can see it. So only {@link Keys} may name them.
 */
class InlinedKeysTest {

    private static final Pattern NAMED = Pattern.compile("InputConstants\\.(KEY|MOUSE_BUTTON)_");

    @Test
    void onlyKeysNamesTheGameKeyNumbers() throws IOException {
        final List<String> found;
        try (Stream<Path> files = Stream.of("src/main/java", "../fabric/src/main/java", "../neoforge/src/main/java")
                .map(Path::of).filter(Files::isDirectory).flatMap(InlinedKeysTest::walk)) {
            found = files.filter(p -> p.toString().endsWith(".java") && !p.getFileName().toString().equals("Keys.java"))
                    .filter(p -> NAMED.matcher(read(p)).find())
                    .map(Path::toString).toList();
        }
        assertEquals(List.of(), found, "use Keys instead of InputConstants key and button constants");
    }

    private static Stream<Path> walk(final Path dir) {
        try {
            return Files.walk(dir);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(final Path p) {
        try {
            return Files.readString(p);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
