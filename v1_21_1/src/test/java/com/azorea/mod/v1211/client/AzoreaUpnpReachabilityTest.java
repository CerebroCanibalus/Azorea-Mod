// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests del detector de NAT aguas arriba (§ D0/T2).
 *
 * <p>Es la pieza que decide qué mensaje ve el host: si su router reporta una WAN
 * no pública, el port-forward NO sirve y decirle "revisa UPnP" sería mentirle.
 * Por eso los bordes del rango CGNAT (RFC 6598 = 100.64.0.0/10) se prueban justo
 * por dentro y por fuera.
 */
final class AzoreaUpnpReachabilityTest {

    // ===== CGNAT (RFC 6598 — 100.64.0.0/10): el caso que nos interesa =====

    @Test
    @DisplayName("Rango CGNAT 100.64/10 detectado: 100.64.0.1 … 100.127.255.255")
    void cgnatRangeDetected() {
        assertTrue(AzoreaUpnpService.isUpstreamNat("100.64.0.1"), "100.64.0.1 es CGNAT");
        assertTrue(AzoreaUpnpService.isUpstreamNat("100.100.100.100"), "mitad del rango");
        assertTrue(AzoreaUpnpService.isUpstreamNat("100.127.255.255"), "última del rango");
    }

    @Test
    @DisplayName("Bordes de 100.64/10: 100.63 y 100.128 NO son CGNAT")
    void cgnatRangeEdgesAreNotCgnat() {
        assertFalse(AzoreaUpnpService.isUpstreamNat("100.63.255.255"),
                "100.63.x queda justo por debajo del rango");
        assertFalse(AzoreaUpnpService.isUpstreamNat("100.128.0.1"),
                "100.128.x queda justo por encima del rango");
        assertFalse(AzoreaUpnpService.isUpstreamNat("100.0.0.1"),
                "100.0.x es ruta clásica, no Shared Address Space");
    }

    // ===== RFC1918 (doble NAT / router detrás de otro) =====

    @Test
    @DisplayName("Direcciones privadas detectadas (10/8, 172.16/12, 192.168/16)")
    void privateRangesDetected() {
        assertTrue(AzoreaUpnpService.isUpstreamNat("10.1.2.3"), "10/8");
        assertTrue(AzoreaUpnpService.isUpstreamNat("172.16.0.1"), "inicio 172.16/12");
        assertTrue(AzoreaUpnpService.isUpstreamNat("172.31.255.254"), "fin 172.16/12");
        assertTrue(AzoreaUpnpService.isUpstreamNat("192.168.1.1"), "192.168/16");
        assertTrue(AzoreaUpnpService.isUpstreamNat("169.254.10.10"), "link-local");
    }

    @Test
    @DisplayName("Fronteras RFC1918: 172.32 y 192.169 NO son privadas")
    void privateRangeEdges() {
        assertFalse(AzoreaUpnpService.isUpstreamNat("172.32.0.1"), "fuera de 172.16/12");
        assertFalse(AzoreaUpnpService.isUpstreamNat("192.169.1.1"), "fuera de 192.168/16");
        assertFalse(AzoreaUpnpService.isUpstreamNat("11.0.0.1"), "fuera de 10/8");
    }

    // ===== IP pública real: aquí UPnP SÍ sirve =====

    @Test
    @DisplayName("IP pública ⇒ false (UPnP sí expone el puerto)")
    void publicIpIsNotUpstreamNat() {
        assertFalse(AzoreaUpnpService.isUpstreamNat("8.8.8.8"));
        assertFalse(AzoreaUpnpService.isUpstreamNat("203.0.113.42"), "TEST-NET-3");
        assertFalse(AzoreaUpnpService.isUpstreamNat("1.2.3.4"));
    }

    // ===== ⊘ datos: no alarmamos sin poder afirmar =====

    @Test
    @DisplayName("Entradas no parseables ⇒ false (no afirmamos CGNAT sin datos)")
    void garbageNeverClaimsCgnat() {
        assertFalse(AzoreaUpnpService.isUpstreamNat(null), "null");
        assertFalse(AzoreaUpnpService.isUpstreamNat(""), "vacío");
        assertFalse(AzoreaUpnpService.isUpstreamNat("   "), "blanco");
        assertFalse(AzoreaUpnpService.isUpstreamNat("not-an-ip"), "texto");
        assertFalse(AzoreaUpnpService.isUpstreamNat("1.2.3"), "3 octetos");
        assertFalse(AzoreaUpnpService.isUpstreamNat("100.64.1"), "incompleto");
        assertFalse(AzoreaUpnpService.isUpstreamNat("256.1.1.1"), "octeto > 255");
        assertFalse(AzoreaUpnpService.isUpstreamNat("a.b.c.d"), "no numérico");
        assertFalse(AzoreaUpnpService.isUpstreamNat("2001:db8::1"), "no es IPv4");
    }

    @Test
    @DisplayName("Nunca lanza excepción con cualquier basura")
    void neverThrows() {
        final String[] junk = {"...", "-1.-1.-1.-1", "100.64.0.", "1,2,3,4", " 100.64.0.1 ",
                "99999999999999999999"};
        for (final String s : junk) {
            // No lanza ⇒ no afirmar no es lo mismo que no crashear.
            AzoreaUpnpService.isUpstreamNat(s);
        }
        // Y con espacios alrededor de un CGNAT real sí debe detectarlo.
        assertTrue(AzoreaUpnpService.isUpstreamNat(" 100.64.0.1 "), "trim implícito");
    }
}
