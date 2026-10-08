// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests del punch UDP (DA-12 paso 2).
 *
 * <p>El q/ importa es {@link #loopbackPunchBothDirections()}: demuestra el disco
 * SIMULTÁNEO real. A diferencia d/ {@code punchTcp} (M-3 — código muerto, y en Windows
 * listener+outbound c/ mismo puerto ⊘ se puede), acá UN socket envía y recibe ⇒
 * ⊘ conflicto d/ bind.
 */
final class AzoreaUdpPuncherTest {

    private static final long DEADLINE_MS = 2000L;

    // ===== 1. El test que importa =====

    @Test
    @DisplayName("Dos sockets en loopback se punchean en simultáneo (doble dirección)")
    void loopbackPunchBothDirections() throws Exception {
        try (DatagramSocket a = AzoreaUdpPuncher.open();
             DatagramSocket b = AzoreaUdpPuncher.open()) {

            final byte[] token = AzoreaUdpPuncher.newToken();
            final int portA = a.getLocalPort();
            final int portB = b.getLocalPort();
            assertTrue(portA > 0 && portB > 0 && portA != portB, "sockets efímeros distintos");

            final AtomicReference<Optional<AzoreaUdpPuncher.PunchResult>> ra = new AtomicReference<>();
            final AtomicReference<Optional<AzoreaUdpPuncher.PunchResult>> rb = new AtomicReference<>();

            // § Disco SIMULTÁNEO: ambos mandan HELLO y ambos esperan recibir el ajeno.
            final Thread ta = Thread.ofVirtual().start(() ->
                    ra.set(AzoreaUdpPuncher.punch(a, "127.0.0.1", portB, token, DEADLINE_MS)));
            final Thread tb = Thread.ofVirtual().start(() ->
                    rb.set(AzoreaUdpPuncher.punch(b, "127.0.0.1", portA, token, DEADLINE_MS)));
            ta.join(DEADLINE_MS + 1500);
            tb.join(DEADLINE_MS + 1500);

            final Optional<AzoreaUdpPuncher.PunchResult> resA = ra.get();
            final Optional<AzoreaUdpPuncher.PunchResult> resB = rb.get();
            assertTrue(resA.isPresent(), "A debe recibir el HELLO d/ B");
            assertTrue(resB.isPresent(), "B debe recibir el HELLO d/ A");

            // § El endpoint real se aprende del source del datagrama recibido.
            assertEquals(portB, resA.get().peer().port(), "A ve el puerto d/ B");
            assertEquals(portA, resB.get().peer().port(), "B ve el puerto d/ A");
            assertEquals(a.getLocalPort(), resA.get().socket().getLocalPort(), "devuelvo el MISMO socket vivo");
            assertFalse(resA.get().socket().isClosed(), "el socket sigue abierto (lo usa el proxy)");
        }
    }

    @Test
    @DisplayName("Peer q/ recibe pero ⊘ responde ⇒ timeout ⇒ punch falla honestamente")
    void silentPeerYieldsEmpty() throws Exception {
        try (DatagramSocket a = AzoreaUdpPuncher.open();
             DatagramSocket silent = AzoreaUdpPuncher.open()) {

            final byte[] token = AzoreaUdpPuncher.newToken();
            // `silent` nunca manda HELLO ⇒ A nunca recibe nada ⇒ vacío.
            final Optional<AzoreaUdpPuncher.PunchResult> r = AzoreaUdpPuncher.punch(
                    a, "127.0.0.1", silent.getLocalPort(), token, 250);

            assertTrue(r.isEmpty(), "sin HELLO ajeno ⇒ ⊘ punch (y ⊘ se afirma falso éxito)");
        }
    }

    // ===== 2. Alambre =====

    @Test
    @DisplayName("HELLO = 'AZP1' + token, 20 bytes")
    void helloWireFormat() {
        final byte[] token = AzoreaUdpPuncher.newToken();
        final byte[] hello = AzoreaUdpPuncher.buildHello(token);

        assertEquals(AzoreaUdpPuncher.HELLO_BYTES, hello.length, "4 + 16");
        assertEquals('A', hello[0] & 0xFF, "magia byte 0");
        assertEquals('Z', hello[1] & 0xFF, "magia byte 1");
        assertEquals('P', hello[2] & 0xFF, "magia byte 2");
        assertEquals('1', hello[3] & 0xFF, "magia byte 3");
        final byte[] tail = java.util.Arrays.copyOfRange(hello, 4, hello.length);
        assertArrayEquals(token, tail, "token en los últimos 16 bytes");
    }

    @Test
    @DisplayName("isHelloFrom: ⊘ acepta magia ajena, longitud errónea ni token distinto")
    void isHelloFromRejectsGarbage() {
        final byte[] token = AzoreaUdpPuncher.newToken();
        final byte[] other = AzoreaUdpPuncher.newToken();
        final byte[] hello = AzoreaUdpPuncher.buildHello(token);

        assertTrue(AzoreaUdpPuncher.isHelloFrom(hello, hello.length, token), "el nuestro pasa");

        // Token distinto ⇒ otro peer (o intento d/ adivinar).
        assertFalse(AzoreaUdpPuncher.isHelloFrom(hello, hello.length, other), "token ≠ ⇒ ⊘");
        // Magia ajena.
        final byte[] garbage = new byte[AzoreaUdpPuncher.HELLO_BYTES];
        assertFalse(AzoreaUdpPuncher.isHelloFrom(garbage, garbage.length, token), "sin magia ⇒ ⊘");
        // Longitud errónea (truncado o basura UDP).
        assertFalse(AzoreaUdpPuncher.isHelloFrom(hello, hello.length - 1, token), "truncado ⇒ ⊘");
        assertFalse(AzoreaUdpPuncher.isHelloFrom(hello, 0, token), "cero ⇒ ⊘");
        // Nulls ⇒ ⊘ NPE.
        assertFalse(AzoreaUdpPuncher.isHelloFrom(null, 5, token), "data null ⇒ ⊘ NPE");
        assertFalse(AzoreaUdpPuncher.isHelloFrom(hello, hello.length, null), "token null ⇒ ⊘ NPE");
    }

    @Test
    @DisplayName("buildHello rechaza token d/ longitud errónea")
    void buildHelloValidatesToken() {
        assertThrows(IllegalArgumentException.class,
                () -> AzoreaUdpPuncher.buildHello(new byte[8]), "token corto");
        assertThrows(IllegalArgumentException.class,
                () -> AzoreaUdpPuncher.buildHello(null), "token null");
        assertEquals(AzoreaUdpPuncher.TOKEN_BYTES, AzoreaUdpPuncher.newToken().length,
                "el token aleatorio tiene la longitud esperada");
    }

    // ===== 3. Validación d/ args (⊕ NPE) =====

    @Test
    @DisplayName("Argumentos inválidos ⇒ IllegalArgumentException clara, ⊘ NPE")
    void punchValidatesArgs() throws Exception {
        try (DatagramSocket s = AzoreaUdpPuncher.open()) {
            final byte[] token = AzoreaUdpPuncher.newToken();
            assertThrows(IllegalArgumentException.class,
                    () -> AzoreaUdpPuncher.punch(s, null, 25565, token, 100), "peerIp null");
            assertThrows(IllegalArgumentException.class,
                    () -> AzoreaUdpPuncher.punch(s, "  ", 25565, token, 100), "peerIp en blanco");
            assertThrows(IllegalArgumentException.class,
                    () -> AzoreaUdpPuncher.punch(s, "1.2.3.4", 0, token, 100), "puerto 0");
            assertThrows(IllegalArgumentException.class,
                    () -> AzoreaUdpPuncher.punch(s, "1.2.3.4", 99999, token, 100), "puerto >65535");
        }
    }
}
