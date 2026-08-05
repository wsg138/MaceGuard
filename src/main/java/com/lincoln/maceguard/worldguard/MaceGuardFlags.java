package com.lincoln.maceguard.worldguard;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.StringFlag;

import java.util.logging.Logger;

public final class MaceGuardFlags {
    public static final String DURABILITY_NAME = "maceguard-mace-durability";
    public static final String COBWEBS_NAME = "maceguard-cobwebs";
    public static final String EXPLOSIVES_NAME = "maceguard-explosives";
    public static final String RESET_PROFILE_NAME = "maceguard-reset-profile";
    public static final String BLOCK_POLICY_NAME = "maceguard-block-policy";
    public static final String WARZONE_COBWEBS_NAME = "warzonerotator-cobwebs";
    public static final String WARZONE_COMBAT_ZONE_NAME = "warzonerotator-combat-zone";
    public static final String WARZONE_STASIS_NAME = "warzonerotator-stasis";

    private StateFlag durability;
    private StateFlag cobwebs;
    private StateFlag explosives;
    private StateFlag warzoneCobwebs;
    private StateFlag warzoneCombatZone;
    private StateFlag warzoneStasis;
    private StringFlag resetProfile;
    private StringFlag blockPolicy;

    public void register(Logger logger) {
        durability = registerState(DURABILITY_NAME, logger);
        cobwebs = registerState(COBWEBS_NAME, logger);
        explosives = registerState(EXPLOSIVES_NAME, logger);
        warzoneCobwebs = registerState(WARZONE_COBWEBS_NAME, true, logger);
        warzoneCombatZone = registerState(WARZONE_COMBAT_ZONE_NAME, false, logger);
        warzoneStasis = registerState(WARZONE_STASIS_NAME, true, logger);
        resetProfile = registerString(RESET_PROFILE_NAME, logger);
        blockPolicy = registerString(BLOCK_POLICY_NAME, logger);
    }

    private StateFlag registerState(String name, Logger logger) {
        return registerState(name, false, logger);
    }

    private StateFlag registerState(String name, boolean defaultValue, Logger logger) {
        StateFlag proposed = new StateFlag(name, defaultValue);
        try {
            WorldGuard.getInstance().getFlagRegistry().register(proposed);
            return proposed;
        } catch (FlagConflictException ex) {
            Flag<?> existing = WorldGuard.getInstance().getFlagRegistry().get(name);
            if (existing instanceof StateFlag state) return state;
            logger.severe("WorldGuard flag '" + name + "' has the wrong type; this feature is disabled.");
            return null;
        }
    }

    private StringFlag registerString(String name, Logger logger) {
        StringFlag proposed = new StringFlag(name);
        try {
            WorldGuard.getInstance().getFlagRegistry().register(proposed);
            return proposed;
        } catch (FlagConflictException ex) {
            Flag<?> existing = WorldGuard.getInstance().getFlagRegistry().get(name);
            if (existing instanceof StringFlag string) return string;
            logger.severe("WorldGuard flag '" + name + "' has the wrong type; this feature is disabled.");
            return null;
        }
    }

    public StateFlag durability() { return durability; }
    public StateFlag cobwebs() { return cobwebs; }
    public StateFlag explosives() { return explosives; }
    public StateFlag warzoneCobwebs() { return warzoneCobwebs; }
    public StateFlag warzoneCombatZone() { return warzoneCombatZone; }
    public StateFlag warzoneStasis() { return warzoneStasis; }
    public boolean combatFlagsAvailable() { return warzoneCombatZone != null && warzoneStasis != null; }
    public StringFlag resetProfile() { return resetProfile; }
    public StringFlag blockPolicy() { return blockPolicy; }
}
