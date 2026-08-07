package com.lincoln.maceguard.warzone.runtime;

import com.lincoln.maceguard.temporary.TemporaryBlockService;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WarzoneModuleFullReloadBridgeTest {
    @TempDir Path directory;

    @Test
    void snapshotDoesNotRelinquishOldAuthorityUntilExplicitRelease() {
        WarzoneModule module = module();
        WarzoneRuntime old = mock(WarzoneRuntime.class);
        WarzoneRuntime.ReloadState inner = mock(WarzoneRuntime.ReloadState.class);
        when(old.snapshotReloadState()).thenReturn(inner);

        module.activateReplacement(null, old, false);
        WarzoneModule.ReloadState snapshot = module.snapshotReloadState();

        assertNotNull(snapshot);
        verify(old).snapshotReloadState();
        verify(old, never()).releaseReloadState();

        module.releaseReloadState();
        verify(old).releaseReloadState();
    }

    @Test
    void stagedFullReloadDoesNotAdoptCooldownOwnershipUntilActivation() {
        WarzoneModule module = module();
        WarzoneRuntime old = mock(WarzoneRuntime.class);
        WarzoneRuntime replacement = mock(WarzoneRuntime.class);
        WarzoneRuntime.ReloadState inner = mock(WarzoneRuntime.ReloadState.class);
        when(old.snapshotReloadState()).thenReturn(inner);
        module.activateReplacement(null, old, false);
        WarzoneModule.ReloadState snapshot = module.snapshotReloadState();

        module.stageReloadState(snapshot);
        module.activateReplacement(null, replacement, false);
        clearInvocations(replacement);

        verify(replacement, never()).adoptReloadState(inner);
        module.completeReloadStateHandoff();
        verify(replacement).adoptReloadState(inner);
        verify(replacement).reconcileVisualCooldowns();
    }

    private WarzoneModule module() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        TemporaryBlockService temporary = mock(TemporaryBlockService.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("WarzoneModuleFullReloadBridgeTest"));
        return new WarzoneModule(plugin, temporary,
                Runnable::run, Clock.systemUTC(), null, null);
    }
}
