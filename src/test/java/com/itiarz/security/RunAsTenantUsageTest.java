package com.itiarz.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * {@link RequestContextHolder#runAsTenant} bypasses user/membership resolution, so it is reserved for
 * system code: production callers may only live in {@code com.itiarz.demo}.
 */
class RunAsTenantUsageTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path ALLOWED_PACKAGE = MAIN.resolve("com/itiarz/demo");
    private static final Path HOLDER = MAIN.resolve("com/itiarz/security/RequestContextHolder.java");

    @Test
    void runAsTenantIsOnlyCalledFromTheDemoPackage() throws IOException {
        assertThat(MAIN).isDirectory();
        try (Stream<Path> files = Files.walk(MAIN)) {
            List<Path> offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.startsWith(ALLOWED_PACKAGE) && !p.equals(HOLDER))
                    .filter(RunAsTenantUsageTest::mentionsRunAsTenant)
                    .toList();
            assertThat(offenders).as("runAsTenant is system-only (com.itiarz.demo)").isEmpty();
        }
    }

    private static boolean mentionsRunAsTenant(Path file) {
        try {
            return Files.readString(file).contains("runAsTenant");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
