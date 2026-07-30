package com.lincoln.maceguard.bootstrap;

import com.lincoln.maceguard.config.MaceGuardConfig;
import com.lincoln.maceguard.reset.ResetCoordinator;
import com.lincoln.maceguard.temporary.TemporaryBlockService;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;

import java.util.concurrent.ExecutorService;

public record PluginRuntime(MaceGuardConfig settings, WorldGuardQueryService worldGuard, ResetCoordinator resets,
                            TemporaryBlockService temporaryBlocks, WarzoneModule warzone, ExecutorService io) { }
