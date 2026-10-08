// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests para {@link AzoreaNatPuncher} (ver AGENTS.md § F6.3).
 *
 * <p>§ Cobertura:
 * <ul>
 *   <li>PUNCH_PACKET y PONG_PACKET tienen los magic bytes correctos.</li>
 *   <li>punch() retorna false si no hay peer escuchando (timeout).</li>
 *   <li>punch() + listenForPunch() en threads paralelas: punch exitoso.</li>
 * </ul>
 */
class AzoreaNatPuncherTest {

    @Test
    @DisplayName("PUNCH_PACKET y PONG_PACKET tienen magic bytes correctos")
    void magicPacketsHaveCorrectFormat() {
        assertEquals("AZPUNCH\n", new String(AzoreaNatPuncher.PUNCH_PACKET));
        assertEquals("AZPONG\n", new String(AzoreaNatPuncher.PONG_PACKET));
    }

    @Test
    @DisplayName("punch() retorna false si nadie escucha (timeout)")
    void punchReturnsFalseOnTimeout() {
        // 127.0.0.1:1 — nadie escucha, packets se descartan
        long start = System.currentTimeMillis();
        boolean result = AzoreaNatPuncher.punch("127.0.0.1", 1, 1000);
        long elapsed = System.currentTimeMillis() - start;
        assertFalse(result, "punch debe fallar sin listener");
        // Debe esperar ~5 timeouts de 200ms = ~1s (con margen).
        assertTrue(elapsed >= 800, "debe respetar timeout ~1s, elapsed=" + elapsed);
    }

    @Test
    @DisplayName("punch() + listenForPunch() round-trip exitoso")
    void punchRoundTripSucceeds() throws Exception {
        // Encuentra un puerto UDP libre para listenForPunch.
        final int port;
        try (DatagramSocket s = new DatagramSocket(0)) {
            port = s.getLocalPort();
        }

        // Lanza listenForPunch en background; espera punch; responde pong.
        final Thread listener = new Thread(() -> AzoreaNatPuncher.listenForPunch(port, 5000));
        listener.setDaemon(true);
        listener.start();

        // Punch al puerto del listener.
        long start = System.currentTimeMillis();
        boolean result = AzoreaNatPuncher.punch("127.0.0.1", port, 3000);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(result, "punch debe tener éxito cuando hay listener. elapsed=" + elapsed);
        // Listener terminó.
        listener.join(1000);
    }
}