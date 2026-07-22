package com.lincoln.maceguard.core.service;

import com.lincoln.maceguard.config.ConfigLoader;
import com.lincoln.maceguard.core.model.EndAccessSettings;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class EndAccessService {
    private final FileConfiguration config;
    private final Runnable configSaver;
    private final Logger logger;

    private final boolean manageEyes;
    private final boolean persistAutoEnableEnabled;
    private boolean eyesAllowed;
    private Instant eyesEnableAt;
    private boolean portalsAllowed;
    private Instant portalsEnableAt;

    public EndAccessService(FileConfiguration config, Runnable configSaver, Logger logger, EndAccessSettings settings) {
        this.config = config;
        this.configSaver = configSaver;
        this.logger = logger;
        this.manageEyes = settings.manageEyes();
        this.persistAutoEnableEnabled = settings.persistAutoEnable();
        this.eyesAllowed = settings.allowEyes();
        this.eyesEnableAt = settings.eyesEnableAt();
        this.portalsAllowed = settings.allowPortals();
        this.portalsEnableAt = settings.portalsEnableAt();
    }

    public boolean managesEyes() {
        return manageEyes;
    }

    public boolean areEyesAllowed() {
        if (!eyesAllowed && eyesEnableAt != null && Instant.now().isAfter(eyesEnableAt)) {
            setEyes(true, null);
            persistAutoEnable("Ender Eyes");
        }
        return eyesAllowed;
    }

    public boolean arePortalsAllowed() {
        if (!portalsAllowed && portalsEnableAt != null && Instant.now().isAfter(portalsEnableAt)) {
            setPortals(true, null);
            persistAutoEnable("End Portals");
        }
        return portalsAllowed;
    }

    public void setEyes(boolean allowed, Instant enableAt) {
        this.eyesAllowed = allowed;
        this.eyesEnableAt = scheduledEnableAt(allowed, enableAt);
        config.set("end_access.allow_eyes", eyesAllowed);
        config.set("end_access.eyes_enable_at_est", eyesEnableAt == null ? "" : formatEst(eyesEnableAt));
    }

    public void setPortals(boolean allowed, Instant enableAt) {
        this.portalsAllowed = allowed;
        this.portalsEnableAt = scheduledEnableAt(allowed, enableAt);
        config.set("end_access.allow_portals", portalsAllowed);
        config.set("end_access.portals_enable_at_est", portalsEnableAt == null ? "" : formatEst(portalsEnableAt));
    }

    public String statusLine(boolean eyes) {
        String label = eyes ? "Ender Eyes" : "End Portals";
        if (eyes && !manageEyes) {
            return "\u00A77" + label + " are not managed by MaceGuard.";
        }
        boolean allowed = eyes ? areEyesAllowed() : arePortalsAllowed();
        Instant scheduledAt = eyes ? eyesEnableAt : portalsEnableAt;
        if (allowed) {
            return "\u00A7a" + label + " are enabled.";
        }
        if (scheduledAt != null) {
            return "\u00A7e" + label + " enable at \u00A7f" + formatEst(scheduledAt) + " EST";
        }
        return "\u00A7c" + label + " are disabled.";
    }

    public Instant parseEst(String value) {
        if (value == null || value.isBlank()) return null;
        try { return java.time.LocalDateTime.parse(value.trim(), ConfigLoader.EST_FORMAT).atZone(ConfigLoader.EST_ZONE).toInstant(); }
        catch (java.time.format.DateTimeParseException ex) { return null; }
    }

    public String formatEst(Instant instant) {
        return ConfigLoader.EST_FORMAT.withZone(ConfigLoader.EST_ZONE).format(instant);
    }

    private Instant scheduledEnableAt(boolean allowed, Instant enableAt) {
        return allowed ? null : enableAt;
    }

    private void persistAutoEnable(String label) {
        if (!persistAutoEnableEnabled) {
            return;
        }
        configSaver.run();
        if (logger.isLoggable(Level.INFO)) {
            logger.info(label + " reached scheduled enable time and were persisted as enabled.");
        }
    }
}
