// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import com.azorea.mod.v1211.AzoreaConfig;
import com.azorea.mod.v1211.AzoreaNetLog;
import com.azorea.mod.v1211.AzoreaNetLog.Category;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Discriminador d/ endpoints p/ el <b>rendezvous</b> — DA-11 aplicado a DA-12.
 *
 * <p>§ El motor d/ relays d/ DA-11 ya decidía <i>"cuál d/ esta lista le conviene
 * al jugador"</i>. Ésto es el mismo rol, sólo q/ cambia el objeto discutido:
 *
 * <table>
 *   <tr><th>relay</th><th>rendezvous</th></tr>
 *   <tr><td>TCP crudo ⇒ <b>c/ualquiera sirve</b>, 3.ºs ya existen</td>
 *       <td>habla <b>nuestro</b> protocolo ⇒ sólo sirve quien corra
 *           {@code azorea-tracker.jar}</td></tr>
 *   <tr><td>la lista curada arranca <b>llena</b></td>
 *       <td>la lista curada arranca <b>VACÍA</b> — nadie lo opera aún (DA-10 ⊘ nosotros)</td></tr>
 * </table>
 *
 * <p>§ <b>La sonda YA existe</b> y ése es el truco: {@code GET /punch/observe}
 * hace las dos cosas a la vez —
 * <ol>
 *   <li><b>contesta</b> ⇒ está vivo y alcanzable desde aquí, y</li>
 *   <li><b>devuelve tu IP pública</b> ⇒ exactamente lo q/ hay q/ firmar.</li>
 * </ol>
 * ∴ un health-check aparte sería redundante.
 *
 * <p>§ <b>Ordena, ⊘ excluye</b>: un candidato mal puntuado sigue d/ última
 * oportunidad en {@code exchange()} (q/ anuncia en TODOS y pollea en todos).
 * Sólo el circuit breaker aminora el ritmo, ⊘ tira la URL.
 *
 * <p>§ <b>Reglas d/ DA-11 aplicadas</b>: health cache (60 s) · circuit breaker
 * (3 fallos ⇒ 5 min) · timeout d/ sonda 2 s · decisión <b>visible</b> en
 * {@code AzoreaNetLog/TRACKER}.
 */
public final class AzoreaRendezvous {

    /** Sonda cacheada d/ 60 s — volver a medir el mismo tracker no aporta nada. */
    static final long CACHE_TTL_MS = 60_000L;
    /**
     * TTL d' un <b>fallo</b> — corto a propósito.
     *
     * <p>§ Si un fallo se cachease 60 s como el éxito, `fails` nunca llegaría al umbral
     * y el circuit breaker **sería decorativo**: caché → retorno temprano → sin reintentos
     * ⇒ contador congelado en 1. Con 1 s, cada `rank()` separado reintenta y suma.
     */
    static final long NEG_TTL_MS = 1_000L;
    /** Circuit breaker: 3 fallos seguidos ⇒ 5 min sin molestar. */
    static final int BREAKER_THRESHOLD = 3;
    static final long BREAKER_OPEN_MS = 5 * 60_000L;
    /** Timeout d/ sonda individual (DA-11: ≤8 s en total; en paralelo ⇒ ≈2 s). */
    static final int PROBE_TIMEOUT_MS = 2000;

    /** Resultado d/ sondear 1 candidato + su puntuación. */
    public record Candidate(String url, boolean responds, String observedIp,
                            long rttMs, boolean fromConfig, int score, String reason) {
        /** ¿Podría servir p/ 2 PCs remotas? (≐ "¿devolvió una IP pública?") */
        public boolean usableOverWan() {
            return responds && AzoreaPunchManager.isPublicIp(observedIp);
        }
    }

    private record Entry(Candidate candidate, long atMs, int fails, long openUntilMs) {
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    private AzoreaRendezvous() {
    }

    // ===== Fuentes =====

    /**
     * Lista <b>curada d/ fábrica</b> — hoy <b>vacía</b>, y no por pereza.
     *
     * <p>§ Un rendezvous tiene q/ hablar <b>nuestro</b> protocolo (observe +
     * announce firmado Ed25519 + poll) ⇒ sólo sirve quien corra nuestro jar.
     * A diferencia d/ los relays (TCP crudo, cualquiera vale), **nadie más lo
     * ejecuta** hoy, y DA-10 nos prohíbe operarlo nosotros.
     *
     * <p>§ Se llena en **D1** (comunidad) — misma historia q/ la lista d/ relays
     * d/ DA-11. Mientras tanto el camino es: `trackers.urls` del jugador o el
     * `trackers=` q/ trae el bundle firmado.
     */
    public static List<String> curated() {
        return List.of();
    }

    /**
     * Candidatos = curados ∪ config del jugador (**dedup**, orden d/ preferencia:
     * lo q/ eligió el jugador primero). *"El jugador debería poder añadir el q/
     * desee"* ⇒ `trackers.urls` en `azorea.toml`.
     */
    public static List<String> candidates() {
        final Set<String> out = new LinkedHashSet<>();
        out.addAll(curated());
        out.addAll(configUrlsSafe());
        return List.copyOf(out);
    }

    /**
     * `AzoreaConfig.getTrackerUrls()` <b>fuerza a inicializar `ModConfigSpec</b>, q/ sólo
     * funciona c/ el entorno cargado d/ NeoForge.
     *
     * <p>§ En tests s/ partida revienta con {@code NoClassDefFoundError} — que es un
     * <b>`Error`</b>, no una `RuntimeException`, ⇒ un `catch (RuntimeException)` no lo
     * atrapa y la sonda entera se aborta. Síntoma observado: <i>todos</i> los candidatos
     * con score −1 y `reason=sonda abortada` aunque un tracker estuviera vivo.
     *
     * <p>En partida real la config ya está cargada ⇒ se comporta igual q/ antes.
     */
    private static List<String> configUrlsSafe() {
        try {
            return AzoreaConfig.getTrackerUrls();
        } catch (final Throwable t) {
            return List.of();
        }
    }

    // ===== El discriminador =====

    /**
     * Sonda **en paralelo** todos los candidatos y devuelve los q/ responden,
     * mejor primero.
     *
     * <p>Nadie queda fuera d/ la lista: sólo cambia d/ orden.
     */
    public static List<Candidate> rank(final List<String> urls) {
        final List<String> distinct = dedup(urls);
        if (distinct.isEmpty()) return List.of();

        final List<Candidate> out = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Future<Candidate>> futures = new ArrayList<>();
            for (final String u : distinct) {
                futures.add(pool.submit(() -> probe(u)));
            }
            for (int i = 0; i < futures.size(); i++) {
                final String u = distinct.get(i);
                final Future<Candidate> f = futures.get(i);
                try {
                    out.add(f.get(PROBE_TIMEOUT_MS + 1500L, TimeUnit.MILLISECONDS));
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return out;
                } catch (final ExecutionException | java.util.concurrent.TimeoutException e) {
                    // § Conserva la URL original — si no, `exchange` recibiría "?" y
                    //   perdería un candidato q/ todavía no se había podido medir.
                    out.add(new Candidate(u, false, null, PROBE_TIMEOUT_MS,
                            false, -1, "sonda abortada: " + e.getClass().getSimpleName()));
                }
            }
        }

        out.sort(Comparator.comparingInt(Candidate::score).reversed()
                .thenComparingLong(Candidate::rttMs));

        // § Decisión visible (DA-11: la elección tiene q/ verse en el log).
        final StringBuilder sb = new StringBuilder("rendezvous (");
        sb.append(out.size()).append("): ");
        for (int i = 0; i < out.size(); i++) {
            final Candidate c = out.get(i);
            if (i > 0) sb.append(" > ");
            sb.append(c.url()).append('=').append(c.responds() ? c.score() : "KO")
                    .append(c.observedIp() != null ? " [" + c.observedIp() + "]" : "");
        }
        AzoreaNetLog.info(Category.TRACKER, sb.toString());

        return List.copyOf(out);
    }

    /** El mejor candidato, o ninguno si no responde nadie. */
    public static Optional<Candidate> best(final List<String> urls) {
        return rank(urls).stream().filter(Candidate::responds).findFirst();
    }

    /**
     * Pura ⇒ testeable s/ red (ésa es la razón d/ q/ esté separada d/ `probe`).
     *
     * <pre>
     *   no responde                        → −1  (ni compite)
     *   responde                           +1000
     *   IP pública observada               +600   ← LA señal: sirve p/ 2 PCs remotas
     *   IP privada (LAN)                   +200
     *   IP loopback observada              +0     (sólo vale p/ local)
     *   URL loopback                       −500   (el tracker d/ otro no es su tracker)
     *   lo eligió el jugador (config)      +150   (intención explícita)
     *   latencia                           −rtt/5 ms, máx −400
     * </pre>
     */
    static int score(final boolean responds, final String observedIp,
                     final boolean loopbackUrl, final boolean fromConfig,
                     final long rttMs) {
        if (!responds) return -1;
        int s = 1000;
        if (AzoreaPunchManager.isPublicIp(observedIp)) s += 600;
        else if (AzoreaPunchManager.isPrivateIp(observedIp)) s += 200;
        if (loopbackUrl) s -= 500;
        if (fromConfig) s += 150;
        s -= (int) Math.min(400L, Math.max(0L, rttMs) / 5L);
        return s;
    }

    // ===== Sonda =====

    private static Candidate probe(final String url) {
        try {
            return doProbe(url);
        } catch (final Throwable t) {
            // § NUNCA dejar q/ una sonda tumbe el rank(): el orden peor d/ lo posible
            //   sigue siendo mejor q/ no ordenar (y `exchange` prueba igual TODOS).
            CACHE.remove(url);
            return new Candidate(url, false, null, 0, false, -1,
                    "sonda rota: " + t.getClass().getSimpleName());
        }
    }

    private static Candidate doProbe(final String url) {
        final long now = System.currentTimeMillis();

        final Entry cached = CACHE.get(url);
        if (cached != null) {
            if (now < cached.openUntilMs()) {
                return new Candidate(url, false, null, 0, isConfigured(url),
                        -1, "breaker abierto (" + cached.fails() + " fallos)");
            }
            // § Éxito ⇒ TTL largo (60 s). Fallo ⇒ TTL corto (1 s) o el contador
            //   d/ fallos nunca avanzaría y el breaker sería decorativo.
            final long ttl = cached.candidate().responds() ? CACHE_TTL_MS : NEG_TTL_MS;
            if (now - cached.atMs() < ttl) {
                return cached.candidate();
            }
        }

        final boolean fromConfig = isConfigured(url);
        final boolean loopbackUrl = AzoreaPunchManager.rankUrl(url) >= 2;
        final long t0 = System.nanoTime();
        // § localPort=0 = modo sonda: bind efímero. Sirve p/ saber SI responde y
        //   QUÉ IP ve; el puerto d/ vuelta no sirve p/ puntear (es efímero).
        final Optional<AzoreaPunchManager.Observed> obs = AzoreaPunchManager.observe(url, 0);
        final long rtt = (System.nanoTime() - t0) / 1_000_000L;

        final Candidate c;
        if (obs.isPresent()) {
            c = new Candidate(url, true, obs.get().ip(), rtt, fromConfig,
                    score(true, obs.get().ip(), loopbackUrl, fromConfig, rtt), "ok");
            CACHE.put(url, new Entry(c, now, 0, 0L));
        } else {
            final int fails = (cached == null ? 0 : cached.fails()) + 1;
            final long openUntil = fails >= BREAKER_THRESHOLD
                    ? now + BREAKER_OPEN_MS : 0L;
            c = new Candidate(url, false, null, rtt, fromConfig, -1,
                    fails >= BREAKER_THRESHOLD ? "breaker (" + fails + " fallos)" : "sin respuesta");
            CACHE.put(url, new Entry(c, now, fails, openUntil));
        }
        return c;
    }

    private static boolean isConfigured(final String url) {
        return configUrlsSafe().contains(url);
    }

    private static List<String> dedup(final List<String> urls) {
        if (urls == null || urls.isEmpty()) return List.of();
        final Set<String> out = new LinkedHashSet<>();
        for (final String u : urls) {
            if (u != null && !u.isBlank()) out.add(u);
        }
        return new ArrayList<>(out);
    }

    /** Sólo p/ tests — la caché es estado estático y los tests se pisan entre sí. */
    static void clearCacheForTests() {
        CACHE.clear();
    }
}
