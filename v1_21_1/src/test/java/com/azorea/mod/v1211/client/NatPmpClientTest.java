// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de formato de paquete NAT-PMP (RFC 6886) — ver AGENTS.md § DA-10.
 *
 * <p>Un protocolo binario se prueba contra bytes concretos: si el orden o el
 * tamaño cambian, el gateway ignora el paquete y el mod concluiría "router sin
 * NAT-PMP" (falso negativo imposible de distinguir de un router de verdad sin
 * soporte). Por eso se fijan los bytes exactos.
 */
final class NatPmpClientTest {

    // ===== Construcción de peticiones =====

    @Test
    @DisplayName("Petición de IP pública = exactamente 2 bytes {0x00, 0x00}")
    void externalAddressRequestIsTwoBytes() {
        final byte[] req = NatPmpClient.buildExternalAddressRequest();
        assertEquals(2, req.length, "RFC 6886 §3.2: opcode 0 son 2 bytes");
        assertArrayEquals(new byte[]{0x00, 0x00}, req);
    }

    @Test
    @DisplayName("Petición de mapeo TCP = 12 bytes con TODOS los campos en orden de red")
    void mappingRequestLayout() {
        final byte[] req = NatPmpClient.buildMappingRequest(2, 0x61DD, 0x61DD, 0x012C_4E80L);
        assertEquals(12, req.length, "RFC 6886 §3.3: 12 bytes");
        assertEquals(0x00, req[0], "version");
        assertEquals(0x02, req[1], "opcode 2 = TCP");
        assertEquals(0x00, req[2], "reserved");
        assertEquals(0x00, req[3], "reserved");
        // BE16 puerto interno (0x61DD = 25053)
        assertEquals(0x61, req[4] & 0xFF, "puerto int byte alto");
        assertEquals(0xDD, req[5] & 0xFF, "puerto int byte bajo");
        // BE16 puerto externo
        assertEquals(0x61, req[6] & 0xFF, "puerto ext byte alto");
        assertEquals(0xDD, req[7] & 0xFF, "puerto ext byte bajo");
        // BE32 lifetime (0x012C4E80 = 19648128)
        assertEquals(0x01, req[8] & 0xFF, "lifetime b0");
        assertEquals(0x2C, req[9] & 0xFF, "lifetime b1");
        assertEquals(0x4E, req[10] & 0xFF, "lifetime b2");
        assertEquals(0x80, req[11] & 0xFF, "lifetime b3");
    }

    @Test
    @DisplayName("OPCode 1 = UDP, 2 = TCP (distintos sobre los mismos puertos)")
    void udpAndTcpUseDifferentOpcodes() {
        assertEquals(0x01, NatPmpClient.buildMappingRequest(1, 25565, 25565, 3600)[1]);
        assertEquals(0x02, NatPmpClient.buildMappingRequest(2, 25565, 25565, 3600)[1]);
    }

    @Test
    @DisplayName("Validación de entrada en la construcción")
    void buildValidatesInput() {
        assertThrows(IllegalArgumentException.class,
                () -> NatPmpClient.buildMappingRequest(7, 25565, 25565, 3600));
        assertThrows(IllegalArgumentException.class,
                () -> NatPmpClient.buildMappingRequest(2, 0, 25565, 3600));
        assertThrows(IllegalArgumentException.class,
                () -> NatPmpClient.buildMappingRequest(2, 25565, 70000, 3600));
        assertThrows(IllegalArgumentException.class,
                () -> NatPmpClient.buildMappingRequest(2, 25565, 25565, -1));
    }

    // ===== Parseo de respuestas =====

    /** Respuesta de IP pública válida: ver=0, op=128, result=0, epoch, IP. */
    private static byte[] okExternalIp(final int a, final int b, final int c, final int d) {
        return new byte[]{0x00, (byte) 128, 0x00, 0x00,
                0x00, 0x00, 0x10, 0x00,
                (byte) a, (byte) b, (byte) c, (byte) d};
    }

    /** Respuesta de mapeo válida: ver=0, op=130, result=0, epoch, int, ext, lifetime. */
    private static byte[] okMapping(final int internal, final int external, final long lifetime) {
        return new byte[]{0x00, (byte) 130, 0x00, 0x00,
                0x00, 0x00, 0x10, 0x00,
                (byte) (internal >> 8), (byte) internal,
                (byte) (external >> 8), (byte) external,
                (byte) (lifetime >> 24), (byte) (lifetime >> 16),
                (byte) (lifetime >> 8), (byte) lifetime};
    }

    @Test
    @DisplayName("IP pública: bytes 8..11 en orden de red")
    void parseExternalAddress() throws IOException {
        assertEquals("203.0.113.42",
                NatPmpClient.parseExternalAddress(okExternalIp(203, 0, 113, 42)));
        assertEquals("10.0.0.1",
                NatPmpClient.parseExternalAddress(okExternalIp(10, 0, 0, 1)));
        assertEquals("203.0.113.9",
                NatPmpClient.parseExternalAddress(okExternalIp(203, 0, 113, 9)));
    }

    @Test
    @DisplayName("Mapeo: puertos y lifetime leídos en orden de red")
    void parseMapping() throws IOException {
        final NatPmpClient.Mapping m = NatPmpClient.parseMappingResponse(
                okMapping(25565, 25565, 3600), 130, 25565);
        assertEquals(25565, m.internalPort());
        assertEquals(25565, m.externalPort());
        assertEquals(3600, m.lifetimeSeconds());
    }

    @Test
    @DisplayName("El router puede REASIGNAR el puerto externo — se respeta, no se ignora")
    void parseMappingWhenRouterAssignsDifferentPort() throws IOException {
        final NatPmpClient.Mapping m = NatPmpClient.parseMappingResponse(
                okMapping(25565, 40123, 7200), 130, 25565);
        assertEquals(40123, m.externalPort(),
                "si el router reasigna, hay que anunciar ESE puerto");
        assertEquals(7200, m.lifetimeSeconds());
    }

    // ===== Rechazos =====

    @Test
    @DisplayName("Códigos de resultado RFC 6886 §3.5 con mensaje legible")
    void resultCodesAreExplained() throws IOException {
        for (final int code : new int[]{1, 2, 3, 4, 5}) {
            final byte[] resp = okMapping(25565, 25565, 3600);
            resp[2] = (byte) (code >> 8);
            resp[3] = (byte) code;
            final IOException e = assertThrows(IOException.class,
                    () -> NatPmpClient.parseMappingResponse(resp, 130, 25565),
                    "código " + code + " debe rechazarse");
            assertTrue(!e.getMessage().contains("desconocido"),
                    "código " + code + " debe estar documentado: " + e.getMessage());
        }
        // 2 = el más útil: UPnP/NAT-PMP apagado por el usuario.
        assertEquals("denegado (el gateway no autoriza mapeos — ¿UPnP/NAT-PMP desactivado?)",
                NatPmpClient.describeResultCode(2));
    }

    @Test
    @DisplayName("Respuestas corruptas o de otra petición ⇒ rechazo claro")
    void malformedResponsesRejected() {
        // Demasiado corta
        assertThrows(IOException.class,
                () -> NatPmpClient.parseExternalAddress(new byte[]{0x00, (byte) 128, 0x00, 0x00}));
        // Versión no soportada (PCP usa la 2)
        final byte[] v2 = okExternalIp(1, 2, 3, 4);
        v2[0] = 2;
        IOException e = assertThrows(IOException.class,
                () -> NatPmpClient.parseExternalAddress(v2));
        assertTrue(e.getMessage().contains("versión"), e.getMessage());
        // Opcode fuera de rango
        final byte[] badOp = okExternalIp(1, 2, 3, 4);
        badOp[1] = 7;
        e = assertThrows(IOException.class, () -> NatPmpClient.parseExternalAddress(badOp));
        assertTrue(e.getMessage().contains("opcode"), e.getMessage());
        // Respuesta a otra petición (op 130 cuando esperábamos 128)
        e = assertThrows(IOException.class,
                () -> NatPmpClient.parseExternalAddress(okMapping(1, 1, 1)));
        assertTrue(e.getMessage().contains("otra petición"), e.getMessage());
        // Mapeo incompleto
        assertThrows(IOException.class,
                () -> NatPmpClient.parseMappingResponse(
                        new byte[]{0x00, (byte) 130, 0x00, 0x00, 0, 0, 0, 0}, 130, 25565));
        // Puerto interno distinto ⇒ respuesta de una petición antigua
        assertThrows(IOException.class,
                () -> NatPmpClient.parseMappingResponse(okMapping(1234, 1234, 60), 130, 25565));
    }
}
