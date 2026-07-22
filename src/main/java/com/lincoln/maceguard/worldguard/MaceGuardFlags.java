package com.lincoln.maceguard.worldguard;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.StringFlag;

import java.util.logging.Logger;

public final class MaceGuardFlags {
    /** Stable WorldGuard registry keys. Renaming one would orphan that flag in existing region data. */
    public static final String DURABILITY_NAME = "maceguard-mace-durability";
    public static final String COBWEBS_NAME = "maceguard-cobwebs";
    public static final String EXPLOSIVES_NAME = "maceguard-explosives";
    public static final String RESET_PROFILE_NAME = "maceguard-reset-profile";
    private StateFlag durability;
    private StateFlag cobwebs;
    private StateFlag explosives;
    private StringFlag resetProfile;

    public void register(Logger logger) {
        durability = registerState(DURABILITY_NAME, logger);
        cobwebs = registerState(COBWEBS_NAME, logger);
        explosives = registerState(EXPLOSIVES_NAME, logger);
        resetProfile = registerString(RESET_PROFILE_NAME, logger);
    }

    private StateFlag registerState(String name, Logger logger) {
        StateFlag proposed = new StateFlag(name, false);
        try { WorldGuard.getInstance().getFlagRegistry().register(proposed); return proposed; }
        catch (FlagConflictException ex) {
            Flag<?> existing = WorldGuard.getInstance().getFlagRegistry().get(name);
            if (existing instanceof StateFlag state) return state;
            logger.severe("WorldGuard flag '" + name + "' has the wrong type; this feature is disabled.");
            return null;
        }
    }

    private StringFlag registerString(String name, Logger logger) {
        StringFlag proposed = new StringFlag(name);
        try { WorldGuard.getInstance().getFlagRegistry().register(proposed); return proposed; }
        catch (FlagConflictException ex) {
            Flag<?> existing = WorldGuard.getInstance().getFlagRegistry().get(name);
            if (existing instanceof StringFlag string) return string;
            logger.severe("WorldGuard flag '" + name + "' has the wrong type; reset support is disabled.");
            return null;
        }
    }

    public StateFlag durability() { return durability; }
    public StateFlag cobwebs() { return cobwebs; }
    public StateFlag explosives() { return explosives; }
    public StringFlag resetProfile() { return resetProfile; }
}
