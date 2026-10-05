package combatlogx.expansion.boss.bar;

import org.bukkit.entity.Player;

/** Test double with the public CombatLogX expansion updater name and method. */
public final class BossBarUpdater {
    private Player removedPlayer;

    public void remove(Player player) { removedPlayer = player; }
    public Player removedPlayer() { return removedPlayer; }
}
