package com.lincoln.maceguard.warzone.runtime;

import com.lincoln.maceguard.temporary.TemporaryBlockService;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WarzoneModuleReloadHandoffTest {
    @TempDir Path directory;

    @Test void oldCleanupFailuresCannotRestoreOldRuntimeAuthority() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        TemporaryBlockService temporaryBlocks = mock(TemporaryBlockService.class);
        Logger logger = mock(Logger.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(logger);
        WarzoneModule module = new WarzoneModule(plugin, temporaryBlocks,
                Runnable::run, Clock.systemUTC(), null, null);
        WarzoneRuntime old = mock(WarzoneRuntime.class);
        WarzoneRuntime replacement = mock(WarzoneRuntime.class);
        doThrow(new IllegalStateException("shutdown failed")).when(old).shutdown(false);
        doThrow(new IllegalStateException("cobweb cleanup failed")).when(old).clearTrackedCobwebs();

        assertDoesNotThrow(() -> module.activateReplacement(old, replacement, true));

        assertSame(replacement, module.runtime());
        verify(old).releaseReloadState();
        verify(old).shutdown(false);
        verify(old).clearTrackedCobwebs();
        verify(replacement).reconcileVisualCooldowns();
        verify(logger).severe(contains("Previous Warzone runtime cleanup failed"));
        verify(logger).severe(contains("Previous Warzone cobweb cleanup failed"));
    }
}
