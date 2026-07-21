package com.lincoln.maceguard.adapter.bukkit.listener;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.core.model.GameplayZone;
import com.lincoln.maceguard.core.model.MaceDurabilityRule;
import com.lincoln.maceguard.core.service.MaceDurabilityTracker;
import com.lincoln.maceguard.util.Compat;
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

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Caps only armor damage caused by a just-observed mace attack. MACE_SMASH is
 * taken from the server's DamageSource, not from a potentially stale hand query.
 */
public final class MaceDurabilityListener implements Listener {
    private final MaceGuardPlugin plugin;
    private final MaceDurabilityTracker tracker = new MaceDurabilityTracker();
    private final MaceAttackClassifier classifier = new MaceAttackClassifier();

    public MaceDurabilityListener(MaceGuardPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getScheduler().runTaskTimer(plugin, tracker::advanceTick, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        if (event.willAttack() && event.getAttacked() instanceof Player && isMace(event.getPlayer().getInventory().getItemInMainHand())) {
            tracker.recordMaceAttackSnapshot(event.getPlayer().getUniqueId(), event.getAttacked().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMaceHit(EntityDamageByEntityEvent event) {
        if (!plugin.isFeatureEnabled() || !Compat.isMaceSupported() || !(event.getDamager() instanceof Player attacker)
                || !(event.getEntity() instanceof Player victim)) {
            return;
        }

        boolean maceSmash = classifier.isMaceSmash(event.getDamageSource().getDamageType());
        boolean snapshotMace = !maceSmash && tracker.consumeMaceAttackSnapshot(attacker.getUniqueId(), victim.getUniqueId());
        MaceAttackClassifier.Source source = classifier.classify(maceSmash, snapshotMace, !maceSmash && isMace(attacker.getInventory().getItemInMainHand()));
        if (source == MaceAttackClassifier.Source.NONE) {
            if (maceSmash) {
                debug("rejected mace-smash damage source without a valid classification context");
            }
            return;
        }

        plugin.runtime().counters().maceAttackRecognized(maceSmash, source == MaceAttackClassifier.Source.HELD_ITEM_FALLBACK);

        MaceDurabilityRule rule = durabilityRuleAt(victim);
        if (!rule.enabled()) {
            debug("rejected mace hit source=" + source + " because no enabled durability rule applies");
            return;
        }
        EnumSet<MaceDurabilityTracker.ArmorSlot> equippedArmor = equippedArmor(victim);
        if (equippedArmor.isEmpty()) {
            debug("rejected mace hit source=" + source + " because victim has no equipped damageable armor");
            return;
        }
        tracker.createContext(attacker.getUniqueId(), victim.getUniqueId(), ruleZoneAt(victim), rule.damagePerArmorPiece(), equippedArmor);
        plugin.runtime().counters().maceDurabilityContext();
        debug("mace hit source=" + source + " damageType=" + event.getDamageSource().getDamageType().getKey()
                + " zone=" + ruleZoneAt(victim) + " cap=" + rule.damagePerArmorPiece() + " tick=" + tracker.currentTick());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorDamage(PlayerItemDamageEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        Optional<MaceDurabilityTracker.ArmorSlot> armorSlot = equippedArmorSlot(event.getPlayer(), event.getItem());
        if (armorSlot.isEmpty()) {
            return;
        }
        tracker.claim(event.getPlayer().getUniqueId(), armorSlot.get()).ifPresent(context -> {
            int incoming = event.getDamage();
            int capped = Math.min(incoming, context.cap());
            event.setDamage(capped);
            plugin.runtime().counters().maceArmorEventCapped();
            debug("mace armor slot=" + armorSlot.get() + " material=" + event.getItem().getType()
                    + " incoming=" + incoming + " final=" + capped + " zone=" + context.zoneName());
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { tracker.clearPlayer(event.getPlayer().getUniqueId()); }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) { tracker.clearPlayer(event.getEntity().getUniqueId()); }

    public void clear() { tracker.clear(); }

    private MaceDurabilityRule durabilityRuleAt(Player victim) {
        List<GameplayZone> zones = plugin.runtime().zoneRegistry().highestPriorityZonesAt(victim.getLocation());
        if (plugin.runtime().zoneRegistry().isExternallyManaged(victim.getLocation())) {
            return MaceDurabilityRule.DISABLED;
        }
        return zones.stream().map(GameplayZone::maceDurabilityRule).filter(MaceDurabilityRule::enabled)
                .findFirst().orElse(MaceDurabilityRule.DISABLED);
    }

    private String ruleZoneAt(Player victim) {
        return plugin.runtime().zoneRegistry().highestPriorityZonesAt(victim.getLocation()).stream()
                .filter(zone -> zone.maceDurabilityRule().enabled()).map(GameplayZone::name).findFirst().orElse("none");
    }

    private EnumSet<MaceDurabilityTracker.ArmorSlot> equippedArmor(Player player) {
        EnumSet<MaceDurabilityTracker.ArmorSlot> slots = EnumSet.noneOf(MaceDurabilityTracker.ArmorSlot.class);
        for (MaceDurabilityTracker.ArmorSlot slot : MaceDurabilityTracker.ArmorSlot.values()) {
            if (isDamageableArmor(player.getInventory().getItem(toBukkitSlot(slot)))) {
                slots.add(slot);
            }
        }
        return slots;
    }

    private Optional<MaceDurabilityTracker.ArmorSlot> equippedArmorSlot(Player player, ItemStack item) {
        for (MaceDurabilityTracker.ArmorSlot slot : MaceDurabilityTracker.ArmorSlot.values()) {
            ItemStack equipped = player.getInventory().getItem(toBukkitSlot(slot));
            // PlayerItemDamageEvent has no EquipmentSlot in Paper 1.21.11. Restrict
            // matching to the corresponding live armor slot; never scan inventory.
            if (isDamageableArmor(equipped) && equipped.getType() == item.getType() && equipped.equals(item)) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    private boolean isDamageableArmor(ItemStack item) {
        return item != null && item.getType() != Material.AIR && item.getType().getEquipmentSlot().isArmor() && item.getType().isItem();
    }

    private EquipmentSlot toBukkitSlot(MaceDurabilityTracker.ArmorSlot slot) {
        return switch (slot) {
            case HEAD -> EquipmentSlot.HEAD;
            case CHEST -> EquipmentSlot.CHEST;
            case LEGS -> EquipmentSlot.LEGS;
            case FEET -> EquipmentSlot.FEET;
        };
    }

    private boolean isMace(ItemStack item) { return item != null && Compat.isMace(item.getType()); }

    private void debug(String message) {
        if (plugin.runtime() != null && plugin.runtime().settings().debug()) {
            plugin.getLogger().info("Mace durability: " + message);
        }
    }
}
