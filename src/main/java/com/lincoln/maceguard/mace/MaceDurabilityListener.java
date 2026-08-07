package com.lincoln.maceguard.mace;

import com.lincoln.maceguard.adapter.bukkit.listener.MaceAttackClassifier;
import com.lincoln.maceguard.config.MaceGuardConfig;
import com.lincoln.maceguard.core.service.MaceDurabilityTracker;
import com.lincoln.maceguard.util.Compat;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.Optional;

/** Changes armor item durability only; health, knockback, cooldowns, and vanilla mace mechanics are untouched. */
public final class MaceDurabilityListener implements Listener {
    private final MaceGuardConfig config;
    private final WorldGuardQueryService worldGuard;
    private final MaceDurabilityTracker tracker = new MaceDurabilityTracker();
    private final MaceAttackClassifier classifier = new MaceAttackClassifier();
    private final BukkitTask ticker;

    public MaceDurabilityListener(JavaPlugin plugin, MaceGuardConfig config, WorldGuardQueryService worldGuard) {
        this.config = config; this.worldGuard = worldGuard;
        ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, tracker::advanceTick, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        if (event.willAttack() && event.getAttacked() instanceof Player && isMace(event.getPlayer().getInventory().getItemInMainHand()))
            tracker.recordMaceAttackSnapshot(event.getPlayer().getUniqueId(), event.getAttacked().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMaceHit(EntityDamageByEntityEvent event) {
        if (!config.enabled() || !Compat.isMaceSupported() || !(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof Player victim)) return;
        boolean smash = classifier.isMaceSmash(event.getDamageSource().getDamageType());
        boolean snapshot = !smash && tracker.consumeMaceAttackSnapshot(attacker.getUniqueId(), victim.getUniqueId());
        var source = classifier.classify(smash, snapshot, !smash && isMace(attacker.getInventory().getItemInMainHand()));
        if (source == MaceAttackClassifier.Source.NONE || !worldGuard.durabilityAllowed(victim.getLocation(), victim)) return;
        EnumSet<MaceDurabilityTracker.ArmorSlot> armor = equippedArmor(victim);
        if (!armor.isEmpty()) tracker.createContext(attacker.getUniqueId(), victim.getUniqueId(), "worldguard", config.durabilityCap(), armor);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorDamage(PlayerItemDamageEvent event) {
        if (!config.enabled()) return;
        equippedArmorSlot(event.getPlayer(), event.getItem()).flatMap(slot -> tracker.claim(event.getPlayer().getUniqueId(), slot))
                .ifPresent(context -> event.setDamage(Math.min(event.getDamage(), context.cap())));
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { tracker.clearPlayer(event.getPlayer().getUniqueId()); }
    @EventHandler public void onDeath(PlayerDeathEvent event) { tracker.clearPlayer(event.getEntity().getUniqueId()); }
    public void clear() {
        ticker.cancel();
        tracker.clear();
    }

    private EnumSet<MaceDurabilityTracker.ArmorSlot> equippedArmor(Player player) {
        EnumSet<MaceDurabilityTracker.ArmorSlot> result = EnumSet.noneOf(MaceDurabilityTracker.ArmorSlot.class);
        for (var slot : MaceDurabilityTracker.ArmorSlot.values()) if (isDamageableArmor(player.getInventory().getItem(toBukkitSlot(slot)))) result.add(slot);
        return result;
    }

    private Optional<MaceDurabilityTracker.ArmorSlot> equippedArmorSlot(Player player, ItemStack item) {
        for (var slot : MaceDurabilityTracker.ArmorSlot.values()) {
            ItemStack equipped = player.getInventory().getItem(toBukkitSlot(slot));
            if (isDamageableArmor(equipped) && equipped.getType() == item.getType() && equipped.equals(item)) return Optional.of(slot);
        }
        return Optional.empty();
    }

    private boolean isDamageableArmor(ItemStack item) { return item != null && item.getType() != Material.AIR && item.getType().getEquipmentSlot().isArmor(); }
    private boolean isMace(ItemStack item) { return item != null && Compat.isMace(item.getType()); }
    private EquipmentSlot toBukkitSlot(MaceDurabilityTracker.ArmorSlot slot) { return switch (slot) { case HEAD -> EquipmentSlot.HEAD; case CHEST -> EquipmentSlot.CHEST; case LEGS -> EquipmentSlot.LEGS; case FEET -> EquipmentSlot.FEET; }; }
}
