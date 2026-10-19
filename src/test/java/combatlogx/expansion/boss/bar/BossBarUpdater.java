package combatlogx.expansion.boss.bar;

import org.bukkit.entity.Player;

/** Test double with the public CombatLogX expansion updater name and method. */
public final class BossBarUpdater {
    private Player lastRemovedPlayer;

    public void remove(Player player) { lastRemovedPlayer = player; }
    public Player removedPlayer() { return lastRemovedPlayer; }
}
