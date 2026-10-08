// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de formato de paquete PCP — RFC 6887.
 *
 * <p>§ Por qué: los errores de byte-offset en protocolos binarios <b>compilan
 * perfectamente</b> y fallan en la red con mensajes ilegibles. Estos tests
 * fijan el layout contra la RFC de una vez por todas, igual que se hizo con
 * NAT-PMP (donde un resultado de 2 bytes en vez de 1 costó un bug real).
 */
class PcpClientTest {

    private static final int INTERNAL_PORT = 25565;
    private static final long LIFETIME = 3600L;

    // ===== Petición (RFC 6887 §7.1 + §11.1 Figure 9) =====

    @Test
    void peticionMide60Bytes() throws Exception {
        final byte[] req = PcpClient.buildMapRequest(
                InetAddress.getByName("192.168.1.50"), INTERNAL_PORT, 0, LIFETIME, nonce());
        assertEquals(60, req.length, "petición MAP = 24 B cabecera + 36 B payload");
    }

    @Test
    void cabeceraEsVersion2OpcodeMapSinR() throws Exception {
        final byte[] req = PcpClient.buildMapRequest(
                InetAddress.getByName("10.0.0.5"), INTERNAL_PORT, 0, LIFETIME, nonce());
        assertEquals(2, req[0] & 0xFF, "Version = 2 (§7.1)");
        assertEquals(0, (req[1] & 0x80) >> 7, "R = 0 (es petición)");
        assertEquals(1, req[1] & 0x7F, "Opcode MAP = 1");
        assertEquals(0, req[2], "Reserved 16b alto = 0");
        assertEquals(0, req[3], "Reserved 16b bajo = 0");
    }

    @Test
    void lifetimeVaEnBigEndianEnLosBytes4a7() throws Exception {
        final byte[] req = PcpClient.buildMapRequest(
                InetAddress.getByName("10.0.0.5"), INTERNAL_PORT, 0, LIFETIME, nonce());
        assertEquals(0x00, req[4] & 0xFF);
        assertEquals(0x00, req[5] & 0xFF);
        assertEquals(3600 >> 8, req[6] & 0xFF, "3600 = 0x00000E10");
        assertEquals(0x10, req[7] & 0xFF);
    }

    @Test
    void ipClienteEsIpv4Mapped() throws Exception {
        final byte[] req = PcpClient.buildMapRequest(
                InetAddress.getByName("192.168.1.50"), INTERNAL_PORT, 0, LIFETIME, nonce());
        // 8..23 = IP cliente. IPv4-mapped = ::ffff:a.b.c.d ⇒ bytes 18..19 = FF FF
        for (int i = 8; i < 18; i++) {
            assertEquals(0, req[i], "byte " + i + " debe ser 0 en ::ffff:…");
        }
        assertEquals(0xFF, req[18] & 0xFF, "primer 0xFF del mapeo IPv4");
        assertEquals(0xFF, req[19] & 0xFF, "segundo 0xFF del mapeo IPv4");
        assertEquals(192, req[20] & 0xFF);
        assertEquals(168, req[21] & 0xFF);
        assertEquals(1, req[22] & 0xFF);
        assertEquals(94, req[23] & 0xFF);
    }

    @Test
    void payloadMapTieneNonceProtocoloYPuertos() throws Exception {
        final byte[] n = nonce();
        final byte[] req = PcpClient.buildMapRequest(
                InetAddress.getByName("10.0.0.5"), INTERNAL_PORT, 0, LIFETIME, n);
        // 24..35 = Mapping Nonce (12 B)
        assertArrayEquals(n, java.util.Arrays.copyOfRange(req, 24, 36), "nonce tal y como se envió");
        // 36 = Protocol. IANA: TCP = 6 (RFC 6887 §11.1)
        assertEquals(6, req[36] & 0xFF, "Protocol = 6 (TCP)");
        // 37..39 = Reserved 24b
        assertEquals(0, req[37]);
        assertEquals(0, req[38]);
        assertEquals(0, req[39]);
        // 40..41 = Internal Port, 42..43 = Suggested External Port (0 = sin preferencia)
        assertEquals(25565 >> 8, req[40] & 0xFF);
        assertEquals(25565 & 0xFF, req[41] & 0xFF);
        assertEquals(0, req[42], "suggested external = 0 ⇒ sin preferencia (§11.1)");
        assertEquals(0, req[43]);
        // 44..59 = Suggested External IP ⇒ all-zeros = sin preferencia (§11.1)
        for (int i = 44; i < 60; i++) {
            assertEquals(0, req[i], "IP externa sugerida debe ser all-zeros");
        }
    }

    @Test
    void rechazaPuertoInternoInvalido() {
        assertThrows(IllegalArgumentException.class, () -> PcpClient.buildMapRequest(
                InetAddress.getByName("10.0.0.5"), 0, 0, LIFETIME, nonce()));
        assertThrows(IllegalArgumentException.class, () -> PcpClient.buildMapRequest(
                InetAddress.getByName("10.0.0.5"), 70000, 0, LIFETIME, nonce()));
    }

    @Test
    void rechazaNonceDeLongitudDistinta() {
        assertThrows(IllegalArgumentException.class, () -> PcpClient.buildMapRequest(
                InetAddress.getByName("10.0.0.5"), INTERNAL_PORT, 0, LIFETIME, new byte[8]));
    }

    // ===== Respuesta (RFC 6887 §7.2 + §11.1 Figure 10) =====

    /** Respuesta MAP válida sintética, para no depender de un router en el test. */
    private static byte[] validResponse(final byte[] nonce) throws Exception {
        final byte[] r = new byte[60];
        r[0] = 2;                     // Version
        r[1] = (byte) 0x81;           // R=1 | Opcode=1 (MAP)
        r[2] = 0;                     // Reserved
        r[3] = 0;                     // Result Code = SUCCESS
        r[4] = 0x00;                  // Lifetime BE32 = 3600
        r[5] = 0x00;
        r[6] = 0x0E;
        r[7] = 0x10;
        // 8..11 Epoch, 12..23 Reserved → ya en 0
        System.arraycopy(nonce, 0, r, 24, 12);
        r[36] = 6;                    // Protocol = TCP
        r[40] = (byte) (INTERNAL_PORT >> 8);
        r[41] = (byte) INTERNAL_PORT;
        r[42] = 0x61;                 // Assigned External Port
        r[43] = (byte) 0xA8;            //   0x61A8 = 25000
        // 44..59 = Assigned External IP = 8.8.8.8 en IPv4-mapped
        r[54] = (byte) 0xFF;
        r[55] = (byte) 0xFF;
        r[56] = 8;
        r[57] = 8;
        r[58] = 8;
        r[59] = 8;
        return r;
    }

    @Test
    void parseaRespuestaValida() throws Exception {
        final byte[] n = nonce();
        final PcpClient.Mapping m =
                PcpClient.parseMapResponse(validResponse(n), n, INTERNAL_PORT);
        assertEquals(INTERNAL_PORT, m.internalPort());
        assertEquals(25000, m.externalPort(), "0x61A8 = 25000");
        assertEquals("8.8.8.8", m.externalIp());
        assertEquals(3600L, m.lifetimeSeconds());
    }

    @Test
    void resultadoNoCeroLanzaConTextoDeLaRfc() throws Exception {
        final byte[] n = nonce();
        final byte[] r = validResponse(n);
        r[3] = 12;                    // ADDRESS_MISMATCH (§7.4)
        final IOException e = assertThrows(IOException.class,
                () -> PcpClient.parseMapResponse(r, n, INTERNAL_PORT));
        assertTrue(e.getMessage().contains("ADDRESS_MISMATCH"),
                "debe citar el nombre de la RFC, llegó: " + e.getMessage());
    }

    @Test
    void unsuppVersionAnunciaNatPmp() throws Exception {
        final byte[] n = nonce();
        final byte[] r = validResponse(n);
        r[3] = 1;                     // UNSUPP_VERSION ⇒ habla NAT-PMP (§9)
        final IOException e = assertThrows(IOException.class,
                () -> PcpClient.parseMapResponse(r, n, INTERNAL_PORT));
        assertTrue(e.getMessage().contains("NAT-PMP"),
                "debe indicar que hay que bajar a NAT-PMP, llegó: " + e.getMessage());
    }

    @Test
    void rechazaNonceAjeno() throws Exception {
        final byte[] n = nonce();
        final byte[] r = validResponse(n);
        r[24] = (byte) (r[24] ^ 0xFF);  // corromper el nonce
        assertThrows(IOException.class, () -> PcpClient.parseMapResponse(r, n, INTERNAL_PORT));
    }

    @Test
    void rechazaPaqueteQueNoEsRespuesta() throws Exception {
        final byte[] n = nonce();
        final byte[] r = validResponse(n);
        r[1] = 0x01;                  // R = 0 ⇒ es otra petición, no una respuesta
        assertThrows(IOException.class, () -> PcpClient.parseMapResponse(r, n, INTERNAL_PORT));
    }

    @Test
    void rechazaPuertoInternoDistinto() throws Exception {
        final byte[] n = nonce();
        final byte[] r = validResponse(n);
        r[40] = 0x30;                 // 0x3039 = 12345 ≠ 25565
        r[41] = 0x39;
        assertThrows(IOException.class, () -> PcpClient.parseMapResponse(r, n, INTERNAL_PORT));
    }

    @Test
    void rechazaIpExternaAllZeros() throws Exception {
        // §11.1: all-zeros = router sin NAT (modo firewall) ⇒ no aporta IP pública.
        final byte[] n = nonce();
        final byte[] r = validResponse(n);
        java.util.Arrays.fill(r, 44, 60, (byte) 0);
        final IOException e = assertThrows(IOException.class,
                () -> PcpClient.parseMapResponse(r, n, INTERNAL_PORT));
        assertTrue(e.getMessage().contains("STUN"),
                "debe decir que seguimos a STUN, llegó: " + e.getMessage());
    }

    @Test
    void rechazaRespuestaCorta() {
        final byte[] n = new byte[12];
        assertThrows(IOException.class,
                () -> PcpClient.parseMapResponse(new byte[20], n, INTERNAL_PORT));
    }

    // ===== Direcciones (§5) =====

    @Test
    void parseaIpv4Mapped() {
        final byte[] a = new byte[16];
        a[10] = (byte) 0xFF;
        a[11] = (byte) 0xFF;
        a[12] = (byte) 203;
        a[13] = 0;
        a[14] = 113;
        a[15] = 7;
        assertEquals("203.0.113.7", PcpClient.parseAddress(a, 0));
    }

    @Test
    void allZerosDevuelveNull() {
        assertEquals(null, PcpClient.parseAddress(new byte[16], 0),
                "all-zeros = sin preferencia / sin NAT ⇒ null");
    }

    // ===== Códigos de resultado (§7.4) =====

    @Test
    void todosLosResultCodesDeLaRfcEstanTraducidos() {
        // 0..13 son todos los definidos en §7.4; ninguno debe caer a "desconocido".
        for (int code = 1; code <= 13; code++) {
            final String msg = PcpClient.describeResultCode(code);
            assertFalse(msg.contains("desconocido"),
                    "falta traducir el result code " + code + " → " + msg);
        }
    }

    @Test
    void codigoFueraDeRfcCaeEnDesconocido() {
        assertTrue(PcpClient.describeResultCode(99).contains("desconocido"));
    }

    private static byte[] nonce() {
        final byte[] n = new byte[12];
        new SecureRandom().nextBytes(n);
        return n;
    }
}
