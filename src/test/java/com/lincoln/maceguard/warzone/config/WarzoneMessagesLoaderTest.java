package com.lincoln.maceguard.warzone.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WarzoneMessagesLoaderTest {
    @TempDir Path temp;

    @Test void legacyCustomizedFileWithoutNewKeysLoadsSensibleDefaults() throws IOException {
        Path file = temp.resolve("warzone-messages.yml");
        Files.writeString(file, "item-disabled: '<red>Custom <item>'\n");
        ValidationResult<WarzoneMessages> result = new WarzoneMessagesLoader().load(file);
        assertTrue(result.valid(), () -> result.errors().toString());
        assertEquals("<red>Custom <item>", result.value().itemDisabled());
        assertTrue(result.value().itemCooldownStarted().contains("<ready_action>"));
        assertTrue(result.value().abilityCooldownStarted().contains("<cooldown>"));
        assertTrue(result.value().blockPlaceDenied().contains("<item>"));
    }

    @Test void allNewTemplatesAndPlaceholdersLoadWithoutSchemaChange() throws IOException {
        Path file = temp.resolve("warzone-messages.yml");
        Files.writeString(file, """
                item-cooldown: '<cooldown_remaining> <action>'
                item-cooldown-started: '<ready_action> <cooldown>'
                ability-cooldown: '<cooldown_remaining> <action>'
                ability-cooldown-started: '<ready_action> <cooldown>'
                block-place-denied: '<item>'
                block-break-denied: '<item>'
                bucket-use-denied: '<item>'
                """);
        assertTrue(new WarzoneMessagesLoader().load(file).valid());
    }

    @Test void unknownMessageKeyStillFailsStrictValidation() throws IOException {
        Path file = temp.resolve("warzone-messages.yml");
        Files.writeString(file, "invented-message: no\n");
        ValidationResult<WarzoneMessages> result = new WarzoneMessagesLoader().load(file);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("invented-message")));
    }
}
