package com.lincoln.maceguard.warzone.command;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.rotation.OverrideDurationMode;
import com.lincoln.maceguard.warzone.rotation.RotationManager;
import com.lincoln.maceguard.warzone.rotation.SelectionSourceType;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WarzoneCommandParsingTest {
    @Test void nonPlayerRandomRequiresExplicitDuration() {
        Fixture fixture = fixture();
        fixture.command.onCommand(fixture.sender, null, "warzone", new String[]{"RaNdOm"});
        verify(fixture.rotations, never()).applyPrepared(any(), any(), anyList(), any(), anyBoolean());
        verify(fixture.module).send(eq(fixture.sender), contains("duration is required"));
    }

    @Test void explicitConsoleDurationAppliesPreparedRandomOverride() {
        Fixture fixture = fixture();
        fixture.command.onCommand(fixture.sender, null, "warzone", new String[]{"random", "manual"});
        verify(fixture.rotations).applyPrepared(SelectionSourceType.RANDOM, null,
                List.of("cobwebs"), OverrideDurationMode.UNTIL_CLEARED, true);
    }

    @Test void rootTabCompletionFiltersAdministrativeOperationsByPermission() {
        Fixture fixture = fixture();
        when(fixture.sender.hasPermission(anyString())).thenAnswer(invocation ->
                invocation.getArgument(0, String.class).equals("warzonerotator.command.info"));
        List<String> values = fixture.command.onTabComplete(fixture.sender, null,
                "warzone", new String[]{""});
        assertTrue(values.contains("info"));
        assertTrue(values.contains("help"));
        assertFalse(values.contains("random"));
        assertFalse(values.contains("reload"));
    }

    private Fixture fixture() {
        WarzoneModule module = mock(WarzoneModule.class);
        WarzoneRuntime runtime = mock(WarzoneRuntime.class);
        RotationManager rotations = mock(RotationManager.class);
        CommandSender sender = mock(CommandSender.class);
        WarzoneConfig.ActiveSet set = new WarzoneConfig.ActiveSet(List.of("cobwebs"),
                "test", "test", Set.of(), Map.of());
        when(module.runtime()).thenReturn(runtime);
        when(runtime.rotations()).thenReturn(rotations);
        when(rotations.previewRandom()).thenReturn(set);
        when(sender.hasPermission("warzonerotator.manage.random")).thenReturn(true);
        return new Fixture(new WarzoneCommand(module), module, rotations, sender);
    }

    private record Fixture(WarzoneCommand command, WarzoneModule module,
                           RotationManager rotations, CommandSender sender) { }
}
