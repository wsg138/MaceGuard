package com.lincoln.maceguard.warzone.integration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholderDocumentationTest {
    @Test void readmeDocumentsExactlyTheFiftyNineRuntimePlaceholders() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        Matcher matcher = Pattern.compile("^\\| `%warzone_([a-z0-9_]+)%` ",
                Pattern.MULTILINE).matcher(readme);
        Set<String> documented = new LinkedHashSet<>();
        while (matcher.find()) documented.add(matcher.group(1));

        Set<String> runtimeSupported = WarzonePlaceholderExpansion.supportedParameters();
        assertEquals(59, runtimeSupported.size());
        assertEquals(runtimeSupported, documented);
        assertTrue(readme.contains("`Mace, Ender Pearl`"));
        assertFalse(readme.contains("`Mace, Spear Lunge`"));
        assertTrue(readme.contains("effect-only ability targets such as `SPEAR_DAMAGE` and `SPEAR_LUNGE` are excluded"));
    }
}
