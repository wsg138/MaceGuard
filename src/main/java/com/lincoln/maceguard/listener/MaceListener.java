package com.lincoln.maceguard.listener;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.util.Compat;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MaceListener implements Listener {

    private final MaceGuardPlugin plugin;

    // Track “first hit at floor did 0” state
    private final Map<UUID, Boolean> floorZeroed = new ConcurrentHashMap<>();
    // Track victims of a mace hit this tick for durability limiting
    private final Map<UUID, Long> recentMaceVictim = new ConcurrentHashMap<>();

    public MaceListener(MaceGuardPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isMaceMainhand(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        return hand != null && hand.getType() != Material.AIR && Compat.isMace(hand.getType());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMaceHit(EntityDamageByEntityEvent event) {
        if (!plugin.isPluginEnabled() || !Compat.isMaceSupported()) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!isMaceMainhand(attacker)) return;

        Entity ent = event.getEntity();
        if (!(ent instanceof LivingEntity victim)) return;
        if (plugin.isPlayersOnly() && !(victim instanceof Player)) return;

        // Only apply inside our original mace zones
        if (!plugin.regions().isInAnyZone(victim.getLocation())) return;

        final double floor = Math.max(0.0D, plugin.getHealthFloor());
        final double healthBefore = victim.getHealth();
        final double finalNow = event.getFinalDamage();
        final double base = event.getDamage();

        // If > floor, we either allow or cap to land exactly at floor
        if (healthBefore > floor) {
            // Reset “floor zeroed” because they are above floor now
            floorZeroed.remove(victim.getUniqueId());

            double projected = healthBefore - finalNow;
            if (projected < floor) {
                double desiredFinal = Math.max(0.0D, healthBefore - floor);
                double scale = (finalNow <= 0.0D) ? 0.0D : (desiredFinal / finalNow);
                double newBase = Math.max(0.0D, base * scale);
                if (plugin.isDebug()) {
                    plugin.getLogger().info(String.format("[Mace] Cap-to-floor: H=%.2f final=%.4f -> newBase=%.4f (floor=%.2f)",
                            healthBefore, finalNow, newBase, floor));
                }
                event.setDamage(newBase);
            }
        } else {
            // At or below floor: first mace hit does 0, second is normal, then reset.
            boolean hasZeroed = floorZeroed.getOrDefault(victim.getUniqueId(), false);
            if (!hasZeroed) {
                // First hit at/below floor: zero out damage, flag them
                event.setDamage(0.0D);
                floorZeroed.put(victim.getUniqueId(), true);
                if (plugin.isDebug()) {
                    plugin.getLogger().info("[Mace] First hit at/below floor -> setDamage(0) and flag victim.");
                }
            } else {
                // Second (and subsequent while they remain <= floor): allow normal damage
                if (plugin.isDebug()) {
                    plugin.getLogger().info("[Mace] Subsequent hit at/below floor -> normal damage.");
                }
            }
        }

        // Mark victim for durability limiting
        recentMaceVictim.put(victim.getUniqueId(), System.currentTimeMillis());
    }

    // Limit armor durability on mace hits
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorDamage(PlayerItemDamageEvent event) {
        if (!plugin.isLimitArmorDurability()) return;

        Player p = event.getPlayer();
        Long ts = recentMaceVictim.get(p.getUniqueId());
        if (ts == null) return;

        // small window (100 ms) after a mace hit to catch all armor item damages
        if (System.currentTimeMillis() - ts <= 100L) {
            int desired = Math.max(1, plugin.getDurabilityPerHit());
            if (event.getDamage() != desired) {
                if (plugin.isDebug()) {
                    plugin.getLogger().info("[Mace] Limiting armor durability from " + event.getDamage() + " to " + desired);
                }
                event.setDamage(desired);
            }
        }
    }
}
