// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de la guía de port-forward (§ D0.3 / DA-10).
 *
 * <p>Es la pieza que le dice al usuario qué hacer cuando UPnP falla y NO hay
 * CGNAT — si la guía muestra valores vacíos o basura, el usuario se queda igual
 * que sin ella, y encima confía en que el mod sabe de lo que habla.
 */
final class AzoreaPortForwardGuideTest {

    @Test
    @DisplayName("La guía incrusta el router, la IP local, el puerto y la IP pública")
    void guideContainsConcreteValues() {
        final String text = AzoreaPortForwardGuide.buildText(
                "192.168.1.1", "192.168.1.50", 25565, "203.0.113.9");

        assertTrue(text.contains("192.168.1.1"), "IP del router");
        assertTrue(text.contains("192.168.1.50"), "IP local (destino del reenvío)");
        assertTrue(text.contains("25565"), "puerto");
        assertTrue(text.contains("203.0.113.9"), "IP pública");
        assertTrue(text.contains("TCP"), "protocolo explícito (MC no usa UDP)");
        // El usuario tiene que saber DÓNDE buscar en el router.
        assertTrue(text.contains("Port Forwarding"), "nombre de sección");
        assertTrue(text.contains("Virtual Server"), "nombre de sección alternativo");
    }

    @Test
    @DisplayName("Sin datos no deja huecos: muestra un fallback legible")
    void guideFallsBackWhenValuesUnknown() {
        final String text = AzoreaPortForwardGuide.buildText(null, "", 25565, null);

        assertTrue(text.contains("no detectada"), "gateway desconocido → se dice");
        assertFalse(text.contains("null"), "⊗ nunca debe aparecer 'null'");
        assertTrue(text.contains("25565"), "el puerto sí lo sabemos siempre");
        // Si no hay IP pública, al menos indicamos cómo conseguirla.
        assertTrue(text.contains("desconocida"), "IP pública desconocida → se dice");
    }

    @Test
    @DisplayName("Lleva autocomprobación de CGNAT (rango 100.64) por si el diagnóstico falló")
    void guideMentionsCgnatSelfCheck() {
        final String text = AzoreaPortForwardGuide.buildText(
                "192.168.1.1", "192.168.1.50", 25565, "203.0.113.9");
        assertTrue(text.contains("100.64"), "rango CGNAT documentado en la guía");
        assertTrue(text.contains("ISP"), "indica a quién preguntar");
    }

    @Test
    @DisplayName("detectDefaultGateway() no revienta y, si devuelve algo, es una IP válida")
    void gatewayDetectionIsSafe() {
        // Entorno-dependiente: en una máquina con ruta por defecto da IP; sin ella
        // (contenedor aislado) da null. Lo que NO debe hacer nunca es lanzar.
        final String gw = AzoreaPortForwardGuide.detectDefaultGateway();
        if (gw != null) {
            assertFalse(gw.isBlank(), "si devuelve algo no puede ser vacío");
            final String[] o = gw.split("\\.");
            assertTrue(o.length == 4, "IPv4 con 4 octetos, fue: " + gw);
            for (final String part : o) {
                final int v = Integer.parseInt(part.trim());
                assertTrue(v >= 0 && v <= 255, "octeto rango válido: " + gw);
            }
            assertFalse(gw.equalsIgnoreCase("on-link"), "descarta 'on-link'");
            assertNotNull(gw);
        }
    }
}
