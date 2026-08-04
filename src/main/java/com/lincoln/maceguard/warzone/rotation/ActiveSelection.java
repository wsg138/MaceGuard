package com.lincoln.maceguard.warzone.rotation;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;

public record ActiveSelection(
        SelectionSourceType sourceType,
        String sourceId,
        WarzoneConfig.ActiveSet activeSet,
        boolean manualOverride
) { }
