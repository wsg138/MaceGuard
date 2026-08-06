package com.lincoln.maceguard.policy;

import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PermissionIsolationTest {
    private static final String WARZONE_BYPASS = "warzonerotator.bypass";
    private static final String POLICY_BYPASS = "maceguard.block-policy.bypass";

    @Test void legacyWarzoneBypassDoesNotBypassIndependentBlockPolicy() {
        BlockPolicyResolver resolver = mock(BlockPolicyResolver.class);
        WarzoneModule warzone = mock(WarzoneModule.class);
        BlockPolicyListener listener = new BlockPolicyListener(resolver, warzone);
        Player player = mock(Player.class);
        when(player.hasPermission(WARZONE_BYPASS)).thenReturn(true);
        when(player.hasPermission(POLICY_BYPASS)).thenReturn(false);
        when(resolver.resolve(any(Location.class))).thenReturn(denied());
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.STONE);
        when(block.getLocation()).thenReturn(mock(Location.class));
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlockPlaced()).thenReturn(block);

        listener.onPlace(event);

        verify(event).setCancelled(true);
        verify(warzone).sendBlockPlaceDenied(player, Material.STONE);
    }

    @Test void dedicatedPolicyBypassSkipsPolicyResolutionAndFeedback() {
        BlockPolicyResolver resolver = mock(BlockPolicyResolver.class);
        WarzoneModule warzone = mock(WarzoneModule.class);
        BlockPolicyListener listener = new BlockPolicyListener(resolver, warzone);
        Player player = mock(Player.class);
        when(player.hasPermission(POLICY_BYPASS)).thenReturn(true);
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onPlace(event);

        verify(event, never()).setCancelled(true);
        verifyNoInteractions(resolver, warzone);
    }

    @Test void descriptorDoesNotGrantNewBypassesThroughWarzoneAdmin() {
        YamlConfiguration plugin = YamlConfiguration.loadConfiguration(
                new File("src/main/resources/plugin.yml"));
        String admin = "permissions.warzonerotator.admin.children";
        assertFalse(plugin.getBoolean(admin + ".maceguard.block-policy.bypass"));
        assertFalse(plugin.getBoolean(admin + ".maceguard.temporary-cobweb.bypass"));
        assertFalse(plugin.getBoolean("permissions.maceguard.block-policy.bypass.default"));
        assertFalse(plugin.getBoolean("permissions.maceguard.temporary-cobweb.bypass.default"));
    }

    private BlockPolicyResolver.Resolution denied() {
        return new BlockPolicyResolver.Resolution("scope", "missing", null, true,
                "region", false, BlockPolicyResolver.Status.REFERENCED_POLICY_MISSING);
    }
}
