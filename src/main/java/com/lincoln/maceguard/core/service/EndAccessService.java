package com.lincoln.maceguard.core.service;

import com.lincoln.maceguard.config.PluginConfigLoader;
import com.lincoln.maceguard.core.model.EndAccessSettings;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.Instant;

public final class EndAccessService {
    private final FileConfiguration config;
    private final PluginConfigLoader configLoader;

    private boolean eyesAllowed;
    private Instant eyesEnableAt;
    private boolean portalsAllowed;
    private Instant portalsEnableAt;

    public EndAccessService(FileConfiguration config, PluginConfigLoader configLoader, EndAccessSettings settings) {
        this.config = config;
        this.configLoader = configLoader;
        this.eyesAllowed = settings.allowEyes();
        this.eyesEnableAt = settings.eyesEnableAt();
        this.portalsAllowed = settings.allowPortals();
        this.portalsEnableAt = settings.portalsEnableAt();
    }

    public boolean areEyesAllowed() {
        if (!eyesAllowed && eyesEnableAt != null && Instant.now().isAfter(eyesEnableAt)) {
            setEyes(true, null);
        }
        return eyesAllowed;
    }

    public boolean arePortalsAllowed() {
        if (!portalsAllowed && portalsEnableAt != null && Instant.now().isAfter(portalsEnableAt)) {
            setPortals(true, null);
        }
        return portalsAllowed;
    }

    public void setEyes(boolean allowed, Instant enableAt) {
        this.eyesAllowed = allowed;
        this.eyesEnableAt = allowed ? null : enableAt;
        config.set("end_access.allow_eyes", eyesAllowed);
        config.set("end_access.eyes_enable_at_est", eyesEnableAt == null ? "" : formatEst(eyesEnableAt));
    }

    public void setPortals(boolean allowed, Instant enableAt) {
        this.portalsAllowed = allowed;
        this.portalsEnableAt = allowed ? null : enableAt;
        config.set("end_access.allow_portals", portalsAllowed);
        config.set("end_access.portals_enable_at_est", portalsEnableAt == null ? "" : formatEst(portalsEnableAt));
    }

    public String statusLine(boolean eyes) {
        boolean allowed = eyes ? areEyesAllowed() : arePortalsAllowed();
        Instant scheduledAt = eyes ? eyesEnableAt : portalsEnableAt;
        String label = eyes ? "Ender Eyes" : "End Portals";
        if (allowed) {
            return "\u00A7a" + label + " are enabled.";
        }
        if (scheduledAt != null) {
            return "\u00A7e" + label + " enable at \u00A7f" + formatEst(scheduledAt) + " EST";
        }
        return "\u00A7c" + label + " are disabled.";
    }

    public Instant parseEst(String value) {
        return configLoader.parseEstInstant(value);
    }

    public String formatEst(Instant instant) {
        return PluginConfigLoader.EST_FORMAT.withZone(PluginConfigLoader.EST_ZONE).format(instant);
    }
}
