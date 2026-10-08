// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests para {@link AzoreaStunClient} (ver AGENTS.md § F6.2).
 *
 * <p>§ Notas:
 * <ul>
 *   <li>Tests de red pueden ser flaky. Marcados con {@code @EnabledIfEnvironmentVariable("RUN_NETWORK_TESTS")}
 *       para skip por defecto. Ejecutar con {@code RUN_NETWORK_TESTS=1 ./gradlew :v1_21_1:test}.</li>
 *   <li>Los tests unitarios del packet format (buildBindingRequest, parseMappedAddress) no
 *       requieren red y siempre corren.</li>
 * </ul>
 */
class AzoreaStunClientTest {

    /**
     * Test unitario: parseMappedAddress con XOR-MAPPED-ADDRESS (RFC 5389 §15.2).
     */
    @Test
    @DisplayName("parseMappedAddress XOR-MAPPED-ADDRESS extrae IP+puerto XOR'd con magic cookie")
    void parseXorMappedAddressRoundTrip() throws Exception {
        // Caso real: respuesta real de stun.l.google.com:
        // family=IPv4, port=19245 (XOR'd), ip=24.16.207.65 (XOR'd)
        final byte[] attr = buildXorMappedAddressAttribute((short) 19245, new byte[]{24, 16, (byte) 207, 65});

        final AzoreaStunClient.StunResult result =
                AzoreaStunClient.parseMappedAddress(attr, 0, attr.length, true);

        assertEquals("24.16.207.65", result.publicIp());
        assertEquals(19245, result.publicPort());
    }

    /**
     * Test unitario: parseMappedAddress con MAPPED-ADDRESS legacy (RFC 3489) — sin XOR.
     */
    @Test
    @DisplayName("parseMappedAddress MAPPED-ADDRESS (legacy) extrae IP+puerto sin XOR")
    void parseMappedAddressLegacyNoXor() throws Exception {
        final byte[] attr = buildMappedAddressAttribute((short) 8080,
                new byte[]{(byte) 192, (byte) 168, (byte) 1, (byte) 100});

        final AzoreaStunClient.StunResult result =
                AzoreaStunClient.parseMappedAddress(attr, 0, attr.length, false);

        assertEquals("192.168.1.100", result.publicIp());
        assertEquals(8080, result.publicPort());
    }

    /**
     * Test unitario: buildBindingRequest produce 20-byte packet con magic cookie correcto.
     */
    @Test
    @DisplayName("buildBindingRequest produce 20-byte packet con magic cookie 0x2112A442")
    void buildBindingRequestFormat() {
        final byte[] request = AzoreaStunClient.buildBindingRequest();

        assertEquals(20, request.length);
        // Message Type: 0x0001 (Binding Request).
        assertEquals(0x00, request[0]);
        assertEquals(0x01, request[1]);
        // Message Length: 0.
        assertEquals(0x00, request[2]);
        assertEquals(0x00, request[3]);
        // Magic Cookie: 0x2112A442.
        assertEquals(0x21, request[4] & 0xFF);
        assertEquals(0x12, request[5] & 0xFF);
        assertEquals(0xA4, request[6] & 0xFF);
        assertEquals(0x42, request[7] & 0xFF);
        // Transaction ID: 12 bytes.
        assertEquals(12, request.length - 8);
    }

    /**
     * Test de integración (requiere red): query real a stun.l.google.com.
     * SKIP por defecto. Activar con {@code RUN_NETWORK_TESTS=1}.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_NETWORK_TESTS", matches = "1")
    @DisplayName("STUN query real a stun.l.google.com devuelve IP pública")
    void realStunQuery() throws Exception {
        final java.util.Optional<AzoreaStunClient.StunResult> result = AzoreaStunClient.query(5000);
        assertTrue(result.isPresent(), "STUN debe devolver un resultado en entorno con red");
        final AzoreaStunClient.StunResult r = result.get();
        assertNotNull(r.publicIp());
        assertFalse(r.publicIp().isBlank());
        final String[] parts = r.publicIp().split("\\.");
        assertEquals(4, parts.length, "IP debe tener 4 octetos");
        for (final String part : parts) {
            final int octet = Integer.parseInt(part);
            assertTrue(octet >= 0 && octet <= 255, "octet fuera de rango: " + octet);
        }
        assertTrue(r.publicPort() > 0 && r.publicPort() < 65536);
    }

    // ===== Tests del BUG 2026-10-04 (STUN devolvía IPv6) =====

    /**
     * Test unitario: el diagnóstico de family=2 debe EXPlicAR lo que pasó, no señalar
     * al servidor. El mensaje antiguo («MAPPED-ADDRESS family no es IPv4: 2») hizo que
     * el diagnóstico apuntara a «servidor STUN roto» cuando en realidad la petición
     * había salido por IPv6 por nuestra propia culpa.
     */
    @Test
    @DisplayName("parseMappedAddress family=2 explica «respondió IPv6» en vez de culpar al servidor")
    void parseMappedAddressIpv6FamilyExplainsItself() {
        final byte[] attr = buildMappedAddressAttributeWithFamily((short) 19302, 0x02);

        final IOException e = assertThrows(IOException.class,
                () -> AzoreaStunClient.parseMappedAddress(attr, 0, attr.length, false));

        assertTrue(e.getMessage().contains("IPv6"), "debe nombrar la familia: " + e.getMessage());
        assertTrue(e.getMessage().contains("pidió IPv4"), "debe decir qué pedimos: " + e.getMessage());
    }

    /**
     * Test unitario: {@code resolveIpv4} elige la dirección IPv4 cuando el host tiene
     * <b>ambas</b> familias — que es exactamente el escenario del bug (Windows ordena
     * IPv6 primero, así que {@code getByName} devolvía la v6 y STUN respondía v6).
     *
     * <p>Usa {@code localhost} porque en Windows/Linux/macOS resuelve a {@code 127.0.0.1}
     * <b>y</b> {@code ::1}, sin salir de la máquina: no depende de red externa.
     */
    @Test
    @DisplayName("resolveIpv4 elige la IPv4 aunque el host tenga IPv4+IPv6 (regresión 2026-10-04)")
    void resolveIpv4PrefersV4EvenWhenV6Exists() throws Exception {
        final InetAddress resolved = AzoreaStunClient.resolveIpv4("localhost");

        assertInstanceOf(Inet4Address.class, resolved,
                "debe devolver IPv4 aunque el SO ordene IPv6 primero, pero vino: " + resolved);
    }

    // ===== Helpers para construir attributes sintéticos =====

    /** Construye MAPPED-ADDRESS con familia arbitraria (p/ probar family=2 IPv6). */
    private static byte[] buildMappedAddressAttributeWithFamily(short port, int family) {
        return new byte[]{
                0,
                (byte) family,
                (byte) ((port >>> 8) & 0xFF),
                (byte) (port & 0xFF),
                0, 0, 0, 0
        };
    }

    /** Construye XOR-MAPPED-ADDRESS con valores XOR'd (RFC 5389 §15.2). */
    private static byte[] buildXorMappedAddressAttribute(short realPort, byte[] realIp) {
        final int magic = 0x2112A442;
        final int xorPort = (realPort & 0xFFFF) ^ ((magic >>> 16) & 0xFFFF);
        final int xorIp = ((realIp[0] & 0xFF) << 24 | (realIp[1] & 0xFF) << 16
                | (realIp[2] & 0xFF) << 8 | (realIp[3] & 0xFF)) ^ magic;
        return new byte[]{
                0,
                0x01,
                (byte) ((xorPort >>> 8) & 0xFF),
                (byte) (xorPort & 0xFF),
                (byte) ((xorIp >>> 24) & 0xFF),
                (byte) ((xorIp >>> 16) & 0xFF),
                (byte) ((xorIp >>> 8) & 0xFF),
                (byte) (xorIp & 0xFF)
        };
    }

    /** Construye MAPPED-ADDRESS legacy sin XOR (RFC 3489). */
    private static byte[] buildMappedAddressAttribute(short port, byte[] ip) {
        return new byte[]{
                0,
                0x01,
                (byte) ((port >>> 8) & 0xFF),
                (byte) (port & 0xFF),
                ip[0], ip[1], ip[2], ip[3]
        };
    }
}