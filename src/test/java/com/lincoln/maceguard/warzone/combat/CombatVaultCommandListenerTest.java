package com.lincoln.maceguard.warzone.combat;

import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

class CombatVaultCommandListenerTest {
    @Test void combatTagBlocksGuildVaultAliasesAndExtraArguments() {
        for (String command : List.of("/g vault", "/guild vault", "/lumaguilds:g vault",
                "/lumaguilds:guild vault", " /G\tVAULT ", "/g vault open")) {
            Fixture f = fixture(command, true);
            f.listener.onCommand(f.event);
            verify(f.event).setCancelled(true);
            verify(f.messages).guildVaultUnavailable(f.player);
            verify(f.combat, never()).bypass(f.player);
        }
    }
    @Test void ordinaryCombatAndWarzoneCarryoverNeedNoRegionOrLatch() {
        Fixture f = fixture("/g vault", true);
        f.listener.onCommand(f.event);
        verify(f.event).setCancelled(true);
        verify(f.combat).inCombat(f.player);
    }
    @Test void untaggedPlayerCanOpenVault() {
        Fixture f = fixture("/g vault", false);
        f.listener.onCommand(f.event);
        verify(f.event, never()).setCancelled(true);
        verifyNoInteractions(f.messages);
    }
    @Test void unrelatedCommandsAndVaultLookalikesRemainAvailable() {
        for (String command : List.of("/g info", "/g vaultrollback", "/g getvault",
                "/vault", "/other vault", "/g", "/guild bank", "/g chat vault")) {
            Fixture f = fixture(command, true);
            f.listener.onCommand(f.event);
            verify(f.event, never()).setCancelled(true);
            verify(f.combat, never()).inCombat(f.player);
            verifyNoInteractions(f.messages);
        }
    }
    @Test void registeredGuildAliasesCannotBypassGuard() {
        Fixture f = fixture("/clan vault", true);
        PluginCommand command = mock(PluginCommand.class);
        Plugin plugin = mock(Plugin.class);
        when(command.getName()).thenReturn("guild");
        when(command.getPlugin()).thenReturn(plugin);
        when(plugin.getName()).thenReturn("LumaGuilds");
        when(f.server.getPluginCommand("clan")).thenReturn(command);
        f.listener.onCommand(f.event);
        verify(f.event).setCancelled(true);
    }
    @Test void existingCancellationIsNeverChanged() {
        Fixture f = fixture("/g vault", true);
        when(f.event.isCancelled()).thenReturn(true);
        f.listener.onCommand(f.event);
        verify(f.event, never()).setCancelled(anyBoolean());
        verify(f.combat, never()).inCombat(f.player);
    }
    @Test void unavailableOptionalIntegrationDoesNotBlockUntaggedGameplay() {
        Fixture f = fixture("/g vault", false);
        when(f.combat.available()).thenReturn(false);
        f.listener.onCommand(f.event);
        verify(f.event, never()).setCancelled(true);
    }
    @Test void failedCombatQueryBlocksRatherThanAllowingVaultAccess() {
        Fixture f = fixture("/g vault", true);
        when(f.combat.inCombat(f.player)).thenThrow(new IllegalStateException("API unavailable"));
        f.listener.onCommand(f.event);
        verify(f.event).setCancelled(true);
    }
    private Fixture fixture(String message, boolean tagged) {
        CombatLogXGateway combat = mock(CombatLogXGateway.class);
        WarzoneMessageService messages = mock(WarzoneMessageService.class);
        Server server = mock(Server.class);
        Player player = mock(Player.class);
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getMessage()).thenReturn(message);
        when(event.getPlayer()).thenReturn(player);
        when(combat.available()).thenReturn(true);
        when(combat.inCombat(player)).thenReturn(tagged);
        return new Fixture(new CombatVaultCommandListener(combat, messages, server, ignored -> {}),
                combat, messages, server, player, event);
    }
    private record Fixture(CombatVaultCommandListener listener, CombatLogXGateway combat,
            WarzoneMessageService messages, Server server, Player player,
            PlayerCommandPreprocessEvent event) { }
}
