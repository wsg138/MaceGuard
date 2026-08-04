package com.lincoln.maceguard.warzone.integration;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import com.lincoln.maceguard.warzone.rotation.RotationManager;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WarzoneModifierPlaceholderTest {
    private static final String MODIFIER_PREFIX = "modifier_";
    private static final String COBWEBS = "cobwebs";
    private static final String PEARL_FIVE = "ender-pearl-cooldown-5";
    private static final String NO_LUNGE = "no-lunge";
    private static final String COBWEBS_DESCRIPTION =
            "Temporary cobweb placement is available in the effective warzone.";
    private static final String PEARL_DESCRIPTION =
            "Successful Ender Pearls receive a five-second cooldown.";
    private static final String LUNGE_DESCRIPTION =
            "The Spear Lunge effect is disabled without blocking normal spear use.";

    @Test void threeActiveModifiersExposeAllNinePlainValuesInStoredOrder() {
        WarzonePlaceholderExpansion expansion = expansion(modifiers(),
                active(List.of(NO_LUNGE, COBWEBS, PEARL_FIVE)), true);

        assertSlot(expansion, 1, "No Lunge", NO_LUNGE, LUNGE_DESCRIPTION);
        assertSlot(expansion, 2, "Cobwebs", COBWEBS, COBWEBS_DESCRIPTION);
        assertSlot(expansion, 3, "5s Pearl Cooldown", PEARL_FIVE,
                PEARL_DESCRIPTION);
    }

    @Test void twoActiveModifiersLeaveThirdSlotEmpty() {
        WarzonePlaceholderExpansion expansion = expansion(modifiers(),
                active(List.of(PEARL_FIVE, COBWEBS)), true);

        assertSlot(expansion, 1, "5s Pearl Cooldown", PEARL_FIVE,
                PEARL_DESCRIPTION);
        assertSlot(expansion, 2, "Cobwebs", COBWEBS, COBWEBS_DESCRIPTION);
        assertEmptySlot(expansion, 3);
    }

    @Test void oneActiveModifierLeavesSecondAndThirdSlotsEmpty() {
        WarzonePlaceholderExpansion expansion = expansion(modifiers(),
                active(List.of(NO_LUNGE)), true);

        assertSlot(expansion, 1, "No Lunge", NO_LUNGE, LUNGE_DESCRIPTION);
        assertEmptySlot(expansion, 2);
        assertEmptySlot(expansion, 3);
    }

    @Test void zeroActiveModifiersLeaveAllNineValuesEmpty() {
        assertAllSlotsEmpty(expansion(modifiers(), active(List.of()), true));
    }

    @Test void inactiveGameplayScopeStillExposesTheSelectedWeek() {
        WarzonePlaceholderExpansion expansion = expansion(modifiers(),
                active(List.of(COBWEBS, NO_LUNGE)), false);

        assertSlot(expansion, 1, "Cobwebs", COBWEBS, COBWEBS_DESCRIPTION);
        assertSlot(expansion, 2, "No Lunge", NO_LUNGE, LUNGE_DESCRIPTION);
        assertEmptySlot(expansion, 3);
    }

    @Test void unavailableOrEmptyRotationStateReturnsEmptyStrings() {
        Plugin plugin = mock(Plugin.class);
        assertAllSlotsEmpty(new WarzonePlaceholderExpansion(plugin, () -> null));
        assertAllSlotsEmpty(expansion(Map.of(), active(List.of(COBWEBS)), true));
        assertAllSlotsEmpty(expansion(modifiers(), null, true));
    }

    @Test void existingGenericPlaceholdersRemainUnchanged() {
        WarzoneConfig.ActiveSet active = new WarzoneConfig.ActiveSet(
                List.of(COBWEBS, NO_LUNGE),
                "<yellow>Cobwebs <gray>+ </gray><red>No Lunge",
                "<gray>Combined description.", Set.of(), Map.of());
        WarzonePlaceholderExpansion expansion = expansion(modifiers(), active, true);

        assertEquals("Cobwebs + No Lunge",
                expansion.onRequest(null, "current_modifiers"));
        assertEquals(COBWEBS + "+" + NO_LUNGE,
                expansion.onRequest(null, "current_modifier_ids"));
        assertEquals("Combined description.",
                expansion.onRequest(null, "description"));
    }

    private WarzonePlaceholderExpansion expansion(
            Map<String, WarzoneConfig.Modifier> modifiers,
            WarzoneConfig.ActiveSet active, boolean scopeActive) {
        Plugin plugin = mock(Plugin.class);
        WarzoneRuntime live = mock(WarzoneRuntime.class);
        RotationManager rotations = mock(RotationManager.class);
        WarzoneMessageService messages = mock(WarzoneMessageService.class);
        WarzoneConfig config = mock(WarzoneConfig.class);
        when(live.rotations()).thenReturn(rotations);
        when(rotations.active()).thenReturn(active);
        when(rotations.remaining()).thenReturn(Duration.ZERO);
        when(live.gameplayScopeActive()).thenReturn(scopeActive);
        when(live.config()).thenReturn(config);
        when(config.modifiers()).thenReturn(modifiers);
        when(live.messages()).thenReturn(messages);
        when(messages.plain(anyString())).thenAnswer(invocation ->
                plain(invocation.getArgument(0)));
        return new WarzonePlaceholderExpansion(plugin, () -> live);
    }

    private void assertSlot(WarzonePlaceholderExpansion expansion, int index,
                            String name, String id, String description) {
        assertEquals(name, expansion.onRequest(null, MODIFIER_PREFIX + index));
        assertEquals(id, expansion.onRequest(null, MODIFIER_PREFIX + index + "_id"));
        assertEquals(description, expansion.onRequest(
                null, MODIFIER_PREFIX + index + "_description"));
    }

    private void assertEmptySlot(WarzonePlaceholderExpansion expansion, int index) {
        assertEquals("", expansion.onRequest(null, MODIFIER_PREFIX + index));
        assertEquals("", expansion.onRequest(null, MODIFIER_PREFIX + index + "_id"));
        assertEquals("", expansion.onRequest(
                null, MODIFIER_PREFIX + index + "_description"));
    }

    private void assertAllSlotsEmpty(WarzonePlaceholderExpansion expansion) {
        for (int index = 1; index <= 3; index++) assertEmptySlot(expansion, index);
    }

    private Map<String, WarzoneConfig.Modifier> modifiers() {
        return Map.of(
                COBWEBS, modifier(COBWEBS, "<yellow>Cobwebs",
                        "<gray>" + COBWEBS_DESCRIPTION),
                PEARL_FIVE, modifier(PEARL_FIVE,
                        "<light_purple>5s Pearl Cooldown",
                        "<gray>" + PEARL_DESCRIPTION),
                NO_LUNGE, modifier(NO_LUNGE, "<red>No Lunge",
                        "<gray>" + LUNGE_DESCRIPTION));
    }

    private WarzoneConfig.Modifier modifier(String id, String displayName,
                                             String description) {
        return new WarzoneConfig.Modifier(id, true, 10, displayName, description,
                Set.of(), Map.of(), "", "", "");
    }

    private WarzoneConfig.ActiveSet active(List<String> modifierIds) {
        return new WarzoneConfig.ActiveSet(modifierIds,
                "Modifier Test", "Modifier Test", Set.of(), Map.of());
    }

    private String plain(String value) {
        return value.replaceAll("<[^>]+>", "");
    }
}
