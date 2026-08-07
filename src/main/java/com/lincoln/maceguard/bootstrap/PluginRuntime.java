package com.lincoln.maceguard.bootstrap;

import com.lincoln.maceguard.config.MaceGuardConfig;
import com.lincoln.maceguard.mace.MaceDurabilityListener;
import com.lincoln.maceguard.policy.BlockPolicyResolver;
import com.lincoln.maceguard.reset.ResetCoordinator;
import com.lincoln.maceguard.temporary.TemporaryBlockAdmissionJournal;
import com.lincoln.maceguard.temporary.TemporaryBlockService;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.concurrent.ExecutorService;

public record PluginRuntime(MaceGuardConfig settings, WorldGuardQueryService worldGuard,
                            BlockPolicyResolver blockPolicyResolver,
                            ResetCoordinator resets, TemporaryBlockService temporaryBlocks,
                            TemporaryBlockAdmissionJournal temporaryAdmissions,
                            WarzoneModule warzone, ExecutorService io,
                            List<Listener> listeners,
                            MaceDurabilityListener durabilityListener,
                            BukkitTask resetTask, BukkitTask temporaryAdmissionTask) { }
