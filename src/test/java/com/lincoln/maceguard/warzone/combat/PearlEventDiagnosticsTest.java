package com.lincoln.maceguard.warzone.combat;

import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PearlEventDiagnosticsTest {
    @Test void disabledTracingDoesNotEvaluateDiagnosticDetails() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        PearlEventDiagnostics diagnostics = PearlEventDiagnostics.forPlugin(plugin);
        diagnostics.clear();

        AtomicInteger evaluations = new AtomicInteger();
        diagnostics.record(UUID.randomUUID(), "impact", () -> {
            evaluations.incrementAndGet();
            return "expensive detail";
        });

        assertEquals(0, evaluations.get());
        diagnostics.clear();
    }

    @Test void enabledTracingEvaluatesAndRetainsDiagnosticDetails() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        Player target = mock(Player.class);
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        UUID owner = UUID.randomUUID();
        when(plugin.getServer()).thenReturn(server);
        when(server.getConsoleSender()).thenReturn(console);
        when(target.getUniqueId()).thenReturn(owner);

        PearlEventDiagnostics diagnostics = PearlEventDiagnostics.forPlugin(plugin);
        diagnostics.clear();
        diagnostics.enable(target, console);
        AtomicInteger evaluations = new AtomicInteger();

        diagnostics.record(owner, "impact", () -> {
            evaluations.incrementAndGet();
            return "pearl=example";
        });

        assertEquals(1, evaluations.get());
        assertEquals(1, diagnostics.lines(owner).size());
        diagnostics.clear();
    }
}
