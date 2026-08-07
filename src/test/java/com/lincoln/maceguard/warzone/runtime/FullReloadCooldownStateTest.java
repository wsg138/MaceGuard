package com.lincoln.maceguard.warzone.runtime;

import com.lincoln.maceguard.warzone.combat.CombatScopeService;
import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import com.lincoln.maceguard.warzone.region.WarzoneRegionService;
import com.lincoln.maceguard.warzone.restriction.CooldownService;
import com.lincoln.maceguard.warzone.restriction.ItemRestrictionListener;
import com.lincoln.maceguard.warzone.restriction.LungeVelocityGate;
import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionService;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;
import com.lincoln.maceguard.warzone.restriction.VisualCooldownService;
import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FullReloadCooldownStateTest {
    private static final RestrictionTarget PEARL =
            RestrictionTarget.parse("ENDER_PEARL").orElseThrow();

    @Test
    void shorterCooldownReconcilesActualBukkitStateAfterStagedScopeWasRecorded() {
        HandoffResult result = handoff(true, Duration.ofSeconds(5), 600);
        assertEquals(Duration.ofSeconds(5), result.authoritative());
        assertEquals(100, result.bukkitTicks());
    }

    @Test
    void removedCooldownTargetClearsActualBukkitState() {
        HandoffResult result = handoff(true, null, 600);
        assertTrue(result.authoritative().isZero());
        assertEquals(0, result.bukkitTicks());
    }

    @Test
    void disablingWarzoneClearsTransferredCooldownEvenInsideRegionGeometry() {
        HandoffResult result = handoff(false, Duration.ofSeconds(5), 600);
        assertTrue(result.authoritative().isZero());
        assertEquals(0, result.bukkitTicks());
    }

    @Test
    void finalReconciliationExtendsStaleVisualToAuthoritativeRemainingState() {
        HandoffResult result = handoff(true, Duration.ofSeconds(45), 100);
        assertEquals(Duration.ofSeconds(30), result.authoritative());
        assertEquals(600, result.bukkitTicks());
    }

    @Test
    void carriedCooldownUsesTheStricterDurationDuringReloadRestore() {
        AtomicLong clock = new AtomicLong(1_000L);
        CooldownService oldCooldowns = new CooldownService(clock::get);
        UUID playerId = UUID.randomUUID();
        oldCooldowns.start(playerId, PEARL, Duration.ofSeconds(30));
        WarzoneConfig.Restriction direct = new WarzoneConfig.Restriction(
                PEARL, RestrictionMode.COOLDOWN, Duration.ofSeconds(20));
        WarzoneConfig.Restriction carried = new WarzoneConfig.Restriction(
                PEARL, RestrictionMode.COOLDOWN, Duration.ofSeconds(5));
        WarzoneConfig.ActiveSet active = new WarzoneConfig.ActiveSet(
                List.of("reload-test"), "Reload test", "", Set.of(), Map.of(PEARL, direct),
                Set.of(), Map.of(PEARL, carried));
        CooldownService replacement = new CooldownService(clock::get);

        replacement.restore(oldCooldowns.snapshot(),
                WarzoneRuntime.reloadCooldownDurations(true, active));

        assertEquals(Duration.ofSeconds(5), replacement.remaining(playerId, PEARL));
    }

    private HandoffResult handoff(boolean enabled, Duration configuredDuration,
                                  int oldVisualTicks) {
        AtomicLong clock = new AtomicLong(1_000L);
        UUID playerId = UUID.randomUUID();
        AtomicInteger bukkitCooldown = new AtomicInteger(oldVisualTicks);
        Player player = mockPlayer(playerId, bukkitCooldown);
        Server server = mock(Server.class);
        when(server.getPlayer(playerId)).thenReturn(player);

        CooldownService oldCooldowns = new CooldownService(clock::get);
        oldCooldowns.start(playerId, PEARL, Duration.ofSeconds(30));
        VisualCooldownService.Snapshot visualSnapshot = new VisualCooldownService.Snapshot(List.of(
                new VisualCooldownService.SnapshotEntry(playerId, Material.ENDER_PEARL,
                        0, oldVisualTicks, 0L, 31_000L)));

        CooldownService replacementCooldowns = new CooldownService(clock::get);
        VisualCooldownService replacementVisuals =
                new VisualCooldownService(server, clock::get, () -> 0L);
        WarzoneConfig.ActiveSet active = activeSet(configuredDuration);
        ItemRestrictionListener listener = listener(replacementCooldowns, replacementVisuals, active);

        // startStaged() runs this before reload state is adopted and records visibleScope=true.
        listener.reconcileVisualCooldowns(List.of(player));

        replacementCooldowns.restore(oldCooldowns.snapshot(),
                WarzoneRuntime.reloadCooldownDurations(enabled, active));
        replacementVisuals.restore(visualSnapshot);

        // adoptReloadState() clears candidate transient bookkeeping before the final reconcile.
        listener.clearTransientState();
        listener.reconcileVisualCooldowns(List.of(player));
        return new HandoffResult(bukkitCooldown.get(), replacementCooldowns.remaining(playerId, PEARL));
    }

    private ItemRestrictionListener listener(CooldownService cooldowns,
                                             VisualCooldownService visuals,
                                             WarzoneConfig.ActiveSet active) {
        Plugin plugin = mock(Plugin.class);
        PluginMeta pluginMeta = mock(PluginMeta.class);
        when(pluginMeta.getName()).thenReturn("MaceGuard");
        when(pluginMeta.namespace()).thenReturn("maceguard");
        when(plugin.getName()).thenReturn("MaceGuard");
        when(plugin.namespace()).thenReturn("maceguard");
        when(plugin.getPluginMeta()).thenReturn(pluginMeta);
        WarzoneRegionService region = mock(WarzoneRegionService.class);
        when(region.contains(any(Location.class))).thenReturn(true);
        CombatScopeService combatScopes = mock(CombatScopeService.class);
        return new ItemRestrictionListener(plugin, mock(RestrictionService.class), combatScopes,
                cooldowns, visuals, region, mock(WarzoneMessageService.class), () -> active,
                new LungeVelocityGate(System::nanoTime, Duration.ofMillis(250)));
    }

    private WarzoneConfig.ActiveSet activeSet(Duration cooldown) {
        Map<RestrictionTarget, WarzoneConfig.Restriction> restrictions = cooldown == null
                ? Map.of()
                : Map.of(PEARL, new WarzoneConfig.Restriction(
                        PEARL, RestrictionMode.COOLDOWN, cooldown));
        return new WarzoneConfig.ActiveSet(List.of("reload-test"), "Reload test", "",
                Set.of(), restrictions);
    }

    private Player mockPlayer(UUID id, AtomicInteger cooldown) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getLocation()).thenReturn(mock(Location.class));
        when(player.getCooldown(any(Material.class))).thenAnswer(invocation -> cooldown.get());
        doAnswer(invocation -> {
            cooldown.set(invocation.getArgument(1));
            return null;
        }).when(player).setCooldown(any(Material.class), anyInt());
        return player;
    }

    private record HandoffResult(int bukkitTicks, Duration authoritative) { }
}
