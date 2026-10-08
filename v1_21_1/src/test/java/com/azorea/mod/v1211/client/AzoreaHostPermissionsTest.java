// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import com.azorea.mod.v1211.client.AzoreaHostPermissions.Decision;
import com.azorea.mod.v1211.client.AzoreaHostPermissions.Mode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests para {@link AzoreaHostPermissions} (§ DA-rediseño — Trampas con 3 estados).
 *
 * <p>§ <b>Qué protegen</b>: la tabla q/ dice qué ven el host y los q/ se unen. Se deriva
 * d/ fuentes d/ vanilla (ver javadoc d/ la clase), no d/ opinión:
 * <ul>
 *   <li><b>joiners ⇒ 0 siempre</b> que {@code allowCommandsForAllPlayers} sea false —
 *       ésa es la mitad fija del contrato;</li>
 *   <li><b>el host sólo es controlable v/ op-list</b> ⇒ su nivel sale d/ aquí y debe ser
 *       0 ó 4 deterministamente, sin depender d/ cómo se creó el mundo;</li>
 *   <li><b>el estado null no revienta</b> — un toggle recién creado aún no tiene valor.</li>
 * </ul>
 *
 * <p>Sin MC ni red ⇒ siempre corren bajo {@code compileTestJava}.
 */
class AzoreaHostPermissionsTest {

    @Test
    @DisplayName("OFF ⇒ nadie: allowAll=false y host en nivel 0")
    void offMeansNobody() {
        final Decision d = AzoreaHostPermissions.resolve(Mode.OFF);

        assertFalse(d.allowCommandsForAllPlayers(),
                "joiners ⇒ isOp=false ⇒ 0; si fuera true TODO el mundo sería op");
        assertEquals(0, d.hostOpLevel(),
                "el host tiene q/ quedar en 0 — es lo único q/ puede con su dueño");
    }

    @Test
    @DisplayName("HOST_ONLY ⇒ allowAll=false (ellos 0) y host en 4")
    void hostOnlyMeansJoinersOffHostOn() {
        final Decision d = AzoreaHostPermissions.resolve(Mode.HOST_ONLY);

        assertFalse(d.allowCommandsForAllPlayers(),
                "SÓLO si esto es false los q/ se unen valen 0 — es la mitad fija");
        assertEquals(4, d.hostOpLevel());
    }

    @Test
    @DisplayName("ALL ⇒ allowAll=true y host en 4")
    void allMeansEveryone() {
        final Decision d = AzoreaHostPermissions.resolve(Mode.ALL);

        assertTrue(d.allowCommandsForAllPlayers());
        assertEquals(4, d.hostOpLevel());
    }

    @Test
    @DisplayName("OFF y HOST_ONLY comparten allowAll=false ⇒ sólo se distinguen en el host")
    void offAndHostOnlyOnlyDifferOnHost() {
        final Decision off = AzoreaHostPermissions.resolve(Mode.OFF);
        final Decision hostOnly = AzoreaHostPermissions.resolve(Mode.HOST_ONLY);

        assertEquals(off.allowCommandsForAllPlayers(), hostOnly.allowCommandsForAllPlayers(),
                "los dos modos «sin comandos pa/ ellos» deben dejar allowAll igual");
        assertNotEquals(off.hostOpLevel(), hostOnly.hostOpLevel(),
                "si no difirieran en el host, los dos botones harían lo mismo");
    }

    @Test
    @DisplayName("el host sólo recibe 0 en OFF — nunca en los otros dos")
    void hostLevelZeroOnlyInOff() {
        assertEquals(0, AzoreaHostPermissions.resolve(Mode.OFF).hostOpLevel());
        assertEquals(4, AzoreaHostPermissions.resolve(Mode.HOST_ONLY).hostOpLevel());
        assertEquals(4, AzoreaHostPermissions.resolve(Mode.ALL).hostOpLevel());
    }

    @Test
    @DisplayName("modo null ⇒ cae a HOST_ONLY (no lanza, no deja el gate a 0 por casualidad)")
    void nullModeFallsBackSafely() {
        final Decision d = AzoreaHostPermissions.resolve(null);

        assertNotNull(d);
        assertFalse(d.allowCommandsForAllPlayers());
        assertEquals(4, d.hostOpLevel());
    }

    @Test
    @DisplayName("all() refleja allowCommandsForAllPlayers (conveniencia d/ publishServer)")
    void allowAllConvenienceMatches() {
        assertTrue(AzoreaHostPermissions.resolve(Mode.ALL).allowAll());
        assertFalse(AzoreaHostPermissions.resolve(Mode.OFF).allowAll());
        assertFalse(AzoreaHostPermissions.resolve(Mode.HOST_ONLY).allowAll());
    }

    @Test
    @DisplayName("los 3 modos producen decisiones distintas (el toggle no es decorativo)")
    void allThreeModesAreDistinct() {
        final Decision off = AzoreaHostPermissions.resolve(Mode.OFF);
        final Decision host = AzoreaHostPermissions.resolve(Mode.HOST_ONLY);
        final Decision all = AzoreaHostPermissions.resolve(Mode.ALL);

        final java.util.Set<String> seen = new java.util.HashSet<>();
        seen.add(off.allowCommandsForAllPlayers() + ":" + off.hostOpLevel());
        seen.add(host.allowCommandsForAllPlayers() + ":" + host.hostOpLevel());
        seen.add(all.allowCommandsForAllPlayers() + ":" + all.hostOpLevel());

        assertEquals(3, seen.size(), "dos modos producen la misma decisión ⇒ 1 botón sobra");
    }
}
