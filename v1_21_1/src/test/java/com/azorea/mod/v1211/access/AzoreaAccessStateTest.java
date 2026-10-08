// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.access;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests del holder del modo de acceso (F10, § A3) — decide si se exige identidad a quien
 * entre.
 *
 * <p><b>Lo más importante</b>: el estado de fábrica tiene que dejar el gate <b>apagado</b>.
 * Si alguna vez arrancara encendido, un mundo que nadie ha tocado empezaría a expulsar
 * jugadores — el peor fallo posible.
 *
 * <p>Es estado estático ⇒ se reinicia en cada test para que no se contaminen.
 */
class AzoreaAccessStateTest {

    @BeforeEach
    @AfterEach
    void limpiar() {
        AzoreaAccessState.reset();
    }

    @Test
    @DisplayName("de fábrica ⇒ premium SIN mundo ⇒ el gate está APAGADO")
    void estadoDeFabricaApagaElGate() {
        assertEquals(AzoreaAccessState.Mode.PREMIUM, AzoreaAccessState.mode());
        assertNull(AzoreaAccessState.world());
        assertFalse(AzoreaAccessState.gateEnabled(),
                "el gate debe estar apagado hasta que A3 lo active — nunca al revés");
    }

    @Test
    @DisplayName("premium ⇒ gate apagado aunque haya mundo cargado")
    void premiumNoActivaElGate() {
        AzoreaAccessState.setWorld(new AzoreaWorldAccess(java.nio.file.Path.of("x", "azorea")));

        assertFalse(AzoreaAccessState.gateEnabled());
    }

    @Test
    @DisplayName("no-premium SIN mundo ⇒ gate apagado (falta la mitad: no se arriesga)")
    void noPremiumSinMundoNoAbreElGate() {
        AzoreaAccessState.setMode(AzoreaAccessState.Mode.NO_PREMIUM);

        assertFalse(AzoreaAccessState.gateEnabled(),
                "con modo pero sin mundo no se puede verificar a nadie ⇒ no se abre");
    }

    @Test
    @DisplayName("no-premium CON mundo ⇒ gate ACTIVO (las dos mitades juntas)")
    void gateActivoConAmbasMitades() {
        AzoreaAccessState.setWorld(new AzoreaWorldAccess(java.nio.file.Path.of("x", "azorea")));
        AzoreaAccessState.setMode(AzoreaAccessState.Mode.NO_PREMIUM);

        assertTrue(AzoreaAccessState.gateEnabled());
    }

    @Test
    @DisplayName("setMode(null) ⇒ no revienta y cae a premium (fallo seguro)")
    void setModeNuloFallaSeguro() {
        AzoreaAccessState.setMode(AzoreaAccessState.Mode.NO_PREMIUM);

        AzoreaAccessState.setMode(null);

        assertEquals(AzoreaAccessState.Mode.PREMIUM, AzoreaAccessState.mode());
        assertFalse(AzoreaAccessState.gateEnabled());
    }

    @Test
    @DisplayName("reset() ⇒ vuelve a premium sin mundo (lo que llama onServerStopped)")
    void resetVuelveAlOrigen() {
        AzoreaAccessState.setWorld(new AzoreaWorldAccess(java.nio.file.Path.of("x", "azorea")));
        AzoreaAccessState.setMode(AzoreaAccessState.Mode.NO_PREMIUM);
        assertTrue(AzoreaAccessState.gateEnabled());

        AzoreaAccessState.reset();

        assertEquals(AzoreaAccessState.Mode.PREMIUM, AzoreaAccessState.mode());
        assertNull(AzoreaAccessState.world());
        assertFalse(AzoreaAccessState.gateEnabled(),
                "al parar el mundo, el gate tiene que quedar apagado — no colgarse activo");
    }
}
