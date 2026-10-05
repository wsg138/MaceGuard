package com.lincoln.maceguard;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

class FullFeatureCoverageContractTest {
    private static final Map<String, List<String>> REQUIRED_TEST_FAMILIES = requiredTestFamilies();

    @Test
    void everyMajorFeatureFamilyKeepsConcreteRegressionTests() throws IOException {
        Path testRoot = Path.of("src", "test", "java");
        Set<String> testPaths;
        try (Stream<Path> paths = Files.walk(testRoot)) {
            testPaths = paths
                    .filter(Files::isRegularFile)
                    .map(testRoot::relativize)
                    .map(Path::toString)
                    .map(path -> path.replace('\\', '/').toLowerCase(Locale.ROOT))
                    .filter(path -> path.endsWith("test.java"))
                    .collect(Collectors.toSet());
        }

        Map<String, List<String>> missing = new LinkedHashMap<>();
        REQUIRED_TEST_FAMILIES.forEach((family, markers) -> {
            boolean covered = markers.stream()
                    .map(marker -> marker.toLowerCase(Locale.ROOT))
                    .anyMatch(marker -> testPaths.stream().anyMatch(path -> path.contains(marker)));
            if (!covered) {
                missing.put(family, markers);
            }
        });

        if (!missing.isEmpty()) {
            fail("MaceGuard feature families without a concrete regression-test source: " + missing);
        }
    }

    private static Map<String, List<String>> requiredTestFamilies() {
        Map<String, List<String>> families = new LinkedHashMap<>();
        families.put("configuration and bundled defaults", List.of("/config/"));
        families.put("block and bypass policy", List.of("/policy/"));
        families.put("snapshot/reset planning and validation", List.of("/reset/"));
        families.put("restart-safe persistence", List.of("/storage/", "persistencesafety"));
        families.put("temporary blocks and cleanup", List.of("/temporary/"));
        families.put("WorldGuard flag and priority integration", List.of("/worldguard/"));
        families.put("Warzone combat and stasis", List.of("/warzone/combat/"));
        families.put("Warzone rotation, schedules, kits, and modifiers", List.of("/warzone/rotation/"));
        families.put("Warzone runtime, reload, migration, and defaults", List.of("/warzone/runtime/"));
        families.put("Warzone GUI behavior", List.of("/warzone/gui/"));
        families.put("Warzone messages, denial feedback, and cooldown presentation", List.of("/warzone/message/"));
        families.put("Warzone external integrations and placeholders", List.of("/warzone/integration/"));
        return Map.copyOf(families);
    }
}
