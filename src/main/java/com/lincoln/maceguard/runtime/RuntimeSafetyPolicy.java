package com.lincoln.maceguard.runtime;

/** Central policy for the configuration kill switch; disabled mode permits only inspection and disarming. */
public final class RuntimeSafetyPolicy {
    private RuntimeSafetyPolicy() { }

    public static boolean allowsAutomaticReset(boolean enabled) { return enabled; }
    public static boolean allowsCapture(boolean enabled) { return enabled; }
    public static boolean allowsArm(boolean enabled) { return enabled; }
    public static boolean allowsManualReset(boolean enabled) { return enabled; }
    public static boolean allowsSparseOriginalInterception(boolean enabled) { return enabled; }
    public static boolean allowsTemporaryTracking(boolean enabled) { return enabled; }
    public static boolean allowsScheduleChange(boolean enabled, boolean scheduleEnabled) { return !scheduleEnabled || enabled; }
}
