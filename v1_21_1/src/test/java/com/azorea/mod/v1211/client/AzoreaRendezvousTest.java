// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import com.azorea.mod.v1211.tracker.embedded.AzoreaEmbeddedTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El discriminador d/ rendezvous (DA-11 aplicado a DA-12).
 *
 * <p>Dos niveles, y ambos importan:
 * <ul>
 *   <li><b>`score()` puro</b> — la DECISIÓN. Sin red ⇒ se puede probar c/ IPs
 *       sintéticas: ¿prefiere una IP pública a una privada? ¿penaliza un tracker
 *       loopback? Éso es lo q/ hace q/ el sistema elija bien en WAN, y no se
 *       puede comprobar con loopback.</li>
 *   <li><b>E2E c/ tracker real</b> — q/ efectivamente ordene el vivo primero,
 *       q/ la caché funcione y q/ el breaker llegue a abrirse.</li>
 * </ul>
 */
@DisplayName("AzoreaRendezvous — discriminador d/ endpoints")
class AzoreaRendezvousTest {

    private AzoreaEmbeddedTracker tracker;
    private String liveUrl;

    @BeforeEach
    void up() throws Exception {
        AzoreaRendezvous.clearCacheForTests();
        tracker = new AzoreaEmbeddedTracker(0);
        tracker.start();
        liveUrl = tracker.localUrl();
    }

    @AfterEach
    void down() {
        if (tracker != null) tracker.stop();
        AzoreaRendezvous.clearCacheForTests();
    }

    // ===== La decisión (pura) =====

    @Test
    @DisplayName("lista curada vacía d' fábrica — y ése es el motivo")
    void curatedIsEmptyByDefault() {
        assertTrue(AzoreaRendezvous.curated().isEmpty(),
                "hoy nadie opera nuestro protocolo (DA-10 ⊘ nosotros); se llena en D1");
        // El jugador sí puede añadir el suyo ⇒ nunca nulo, a lo sumo vacío:
        assertNotNull(AzoreaRendezvous.candidates());
    }

    @Test
    @DisplayName("la IP pública gana a la privada, y la privada a la loopback")
    void scoreDiscriminatesByObservedIp() {
        final int publicIp  = score("203.0.113.9");
        final int privateIp = score("192.168.1.50");
        final int loopback  = score("127.0.0.1");
        final int dead      = AzoreaRendezvous.score(false, null, false, false, 0);

        assertTrue(publicIp > privateIp,
                "pública (" + publicIp + ") debe ganar a LAN (" + privateIp + ")");
        assertTrue(privateIp > loopback,
                "LAN (" + privateIp + ") debe ganar a loopback (" + loopback + ")");
        assertTrue(loopback > dead,
                "loopback aún sirve en local (" + loopback + "); muerto no (" + dead + ")");
        assertEquals(-1, dead, "no responde ⇒ ni compite");
    }

    @Test
    @DisplayName("un tracker loopback se penaliza ∧ la elección explícita d/l jugador suma")
    void scorePenalisesLoopbackUrlAndRewardsExplicitChoice() {
        final int plain   = AzoreaRendezvous.score(true, "203.0.113.9", false, false, 50);
        final int loopUrl = AzoreaRendezvous.score(true, "203.0.113.9", true,  false, 50);
        final int mine    = AzoreaRendezvous.score(true, "203.0.113.9", false, true,  50);

        assertTrue(plain > loopUrl,
                "URL loopback penalizada (" + loopUrl + " < " + plain + ") — "
                        + "el tracker d/ otro no es tu tracker");
        assertTrue(mine > plain,
                "lo q/ eligió el jugador gana el empate (" + mine + " > " + plain + ")");
    }

    @Test
    @DisplayName("la latencia es desempate, ⊘ criterio principal")
    void latencyBreaksTiesButDoesNotDominate() {
        final int fastPublic = AzoreaRendezvous.score(true, "203.0.113.9", false, false, 10);
        final int slowPublic = AzoreaRendezvous.score(true, "203.0.113.9", false, false, 4000);
        final int fastLoop   = AzoreaRendezvous.score(true, "127.0.0.1", false, false, 10);

        // Un tracker loopback rapidísimo ⊘ debe ganar a uno público lento:
        assertTrue(slowPublic > fastLoop,
                "la latencia no puede compensar q/ el endpoint sea inútil en WAN ("
                        + slowPublic + " ≤ " + fastLoop + ")");
        // Pero entre iguales sí desempata:
        assertTrue(fastPublic > slowPublic);
        // Y el castigo está acotado (≤400) para q/ no invierta el orden por sí solo.
        assertEquals(400, (1000 + 600) - slowPublic, "castigo d/ latencia acotado a −400");
    }

    // ===== El mecanismo (E2E) =====

    @Test
    @DisplayName("rank(): el tracker vivo primero, el caído al final")
    void rankOrdersLiveTrackerFirst() {
        final String dead = "http://127.0.0.1:1";
        final List<AzoreaRendezvous.Candidate> ranked =
                AzoreaRendezvous.rank(List.of(dead, liveUrl));

        assertEquals(2, ranked.size());
        assertEquals(liveUrl, ranked.get(0).url(), "el vivo debe ir primero");
        assertEquals(dead, ranked.get(1).url(), "el caído al final");
        assertTrue(ranked.get(0).responds());
        assertFalse(ranked.get(1).responds());
        assertTrue(ranked.get(0).score() > ranked.get(1).score());
    }

    @Test
    @DisplayName("caché: sirve el resultado recién medido aunque el tracker muera")
    void cacheServesResultAfterTrackerGoesDown() {
        assertTrue(AzoreaRendezvous.best(List.of(liveUrl)).isPresent(), "estando vivo");

        tracker.stop();                     // ← ahora ya no responde
        final AzoreaRendezvous.Candidate after =
                AzoreaRendezvous.rank(List.of(liveUrl)).get(0);
        assertTrue(after.responds(),
                "la caché (60 s) debe devolver el resultado d/ antes d/ pararlo; "
                        + "si falla, la caché no sirve");
    }

    @Test
    @DisplayName("circuit breaker: 3 fallos ⇒ se abre (y deja d/ martillar)")
    void circuitBreakerOpensAfterThreeFailures() {
        final String dead = "http://127.0.0.1:1";
        String reason = "";
        for (int i = 0; i < 4; i++) {
            reason = AzoreaRendezvous.rank(List.of(dead)).get(0).reason();
            if (reason.contains("breaker")) break;
            sleep(AzoreaRendezvous.NEG_TTL_MS + 150);   // el fallo caduca a 1 s
        }
        assertTrue(reason.contains("breaker"),
                "tras ≥3 fallos debía abrirse el breaker; reason=" + reason);
    }

    // ===== helpers =====

    private static int score(final String observedIp) {
        return AzoreaRendezvous.score(true, observedIp, false, false, 50);
    }

    private static void sleep(final long ms) {
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
