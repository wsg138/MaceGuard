package com.lincoln.maceguard.adapter.bukkit.listener;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.core.model.GameplayZone;
import com.lincoln.maceguard.core.model.MaceDurabilityRule;
import com.lincoln.maceguard.util.Compat;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;

public final class MaceDurabilityListener implements Listener {
    private final MaceGuardPlugin plugin;
    private final Map<UUID, MaceDurabilityContext> pendingDurabilityCaps = new HashMap<>();

    public MaceDurabilityListener(MaceGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMaceHit(EntityDamageByEntityEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        if (!Compat.isMaceSupported()) {
            return;
        }
        if (!(event.getDamager() instanceof Player attacker) || !isMaceMainHand(attacker)) {
            return;
        }

        Entity victim = event.getEntity();
        if (!(victim instanceof Player playerVictim)) {
            return;
        }
        if (plugin.runtime().zoneRegistry().isExternallyManaged(playerVictim.getLocation())) {
            pendingDurabilityCaps.remove(playerVictim.getUniqueId());
            return;
        }

        List<GameplayZone> zones = plugin.runtime().zoneRegistry().highestPriorityZonesAt(playerVictim.getLocation());
        MaceDurabilityRule rule = zones.stream()
                .map(GameplayZone::maceDurabilityRule)
                .filter(MaceDurabilityRule::enabled)
                .findFirst()
                .orElse(MaceDurabilityRule.DISABLED);

        if (!rule.enabled()) {
            pendingDurabilityCaps.remove(playerVictim.getUniqueId());
            return;
        }

        pendingDurabilityCaps.put(playerVictim.getUniqueId(), new MaceDurabilityContext(rule.damagePerArmorPiece(), System.currentTimeMillis() + 250L));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorDamage(PlayerItemDamageEvent event) {
        if (!plugin.isFeatureEnabled() || !isArmorPiece(event.getPlayer(), event.getItem())) {
            return;
        }
        MaceDurabilityContext context = pendingDurabilityCaps.get(event.getPlayer().getUniqueId());
        if (context == null) {
            return;
        }
        if (System.currentTimeMillis() > context.expiresAtMillis()) {
            pendingDurabilityCaps.remove(event.getPlayer().getUniqueId());
            return;
        }
        event.setDamage(Math.max(0, Math.min(event.getDamage(), context.damagePerArmorPiece())));
    }

    private boolean isMaceMainHand(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        return item != null && item.getType() != Material.AIR && Compat.isMace(item.getType());
    }

    private boolean isArmorPiece(Player player, ItemStack item) {
        return Arrays.stream(player.getInventory().getArmorContents())
                .anyMatch(armor -> armor != null && armor.equals(item));
    }

    private record MaceDurabilityContext(int damagePerArmorPiece, long expiresAtMillis) {
    }
}
