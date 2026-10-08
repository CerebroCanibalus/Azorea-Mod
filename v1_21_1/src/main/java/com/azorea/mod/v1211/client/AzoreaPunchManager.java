// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import com.azorea.mod.v1211.AzoreaNetLog;
import com.azorea.mod.v1211.AzoreaNetLog.Category;

import com.azorea.mod.tracker.TrackerProtocol;
import com.azorea.mod.v1211.tracker.AzoreaPunchExchange;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Hole-punch TCP coordinado vía embedded tracker (ver AGENTS.md § F8.x, F6.3).
 *
 * <p>§ Protocolo (adaptado de World Host UDP_HOLE_PUNCH_PACKET_FLOW.md):
 * <ol>
 *   <li>Peer A (host) y Peer B (joiner) anuncian al tracker: "listo en puerto local X".
 *       El tracker guarda además la IP pública de cada peer (source IP del request HTTP —
 *       esto reemplaza STUN para el caso donde ambos alcanzan el tracker).</li>
 *   <li>Ambos pollean al tracker hasta ver el announce del otro peer.</li>
 *   <li>Cuando ambos listos → <b>simultaneous open</b>: ambos intentan TCP connect
 *       al otro al mismo tiempo (ventana ~200ms). El NAT crea el mapping con el SYN saliente
 *       y acepta el SYN entrante del peer esperado.</li>
 *   <li>Si ambos SYNs se cruzan → conexión TCP directa establecida.</li>
 *   <li>Fallback: si falla → relay via tracker.</li>
 * </ol>
 *
 * <p>§ Requisitos:
 * <ul>
 *   <li>Ambos peers deben alcanzar el MISMO tracker (embedded o bootstrap).</li>
 *   <li>Para LAN: cada peer tiene su propio embedded tracker → NO alcanzan el mismo.
 *       En LAN usamos LAN discovery en su lugar.</li>
 *   <li>Para WAN: necesita bootstrap público, O el tracker del host expuesto via UPnP.</li>
 * </ul>
 *
 * <p>§ Límite: simultaneous open funciona en ~60-70% de NATs domésticos
 * (endpoint-independent mapping + port preservation). Para NAT simétrico falla → relay.
 */
public final class AzoreaPunchManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaPunchManager.class);
    private static final Gson GSON = TrackerProtocol.gson();

    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    /** Observación d/ endpoint — corto p/ no bloquear el arranque d/ sesión. */
    private static final int OBSERVE_TIMEOUT_MS = 3000;
    private static final int PUNCH_WINDOW_MS = 200;
    private static final int PUNCH_ATTEMPTS = 5;
    private static final int PUNCH_INTERVAL_MS = 25;
    /**
     * RST <b>rápido</b> = "tu stack está al otro lado, su socket todavía no" ⇒ estamos a
     * un salto ⇒ reintentar YA. Silencio = el NAT derramó el SYN ⇒ esperar más (y dura
     * el mapeo vivo).
     *
     * <p>§ Sin éste ajuste el simultaneous open es <b>casi imposible en loopback</b>:
     * un RST instantáneo deja el socket en `SYN-SENT` µs ⇒ "deber" bajísimo ⇒ pocos
     * intentos coinciden. Con 5 ms d'espacio sube a cientos d/ intentos/segundo.
     */
    private static final int FAST_FAIL_MS = 10;
    private static final int FAST_RETRY_MS = 5;
    /** Pausa entre anuncios: TTL d/ announce = 30 s ⇒ refrescar a 1/3. */
    private static final int REANNOUNCE_MS = 10_000;
    private static final int POLL_INTERVAL_MS = 250;

    private AzoreaPunchManager() {
    }

    /** Resultado del punch: socket TCP conectado, o null si falló. */
    public record PunchResult(Socket socket, String peerEndpoint) {
    }

    // ===== Identidad =====

    /**
     * Material p/ firmar un announce.
     *
     * <p>§ <b>Extraído a propósito</b>: antes el manager leía {@code AzoreaMod.get()}
     * ⇒ in-testeable (no hay partida corriendo). Así los tests generan sus claves con
     * {@code AzoreaIdentity.generate*} y derivan el id con {@code AzoreaId.derive} —
     * que es exactamente lo q/ hace el peer al verificar.
     *
     * @param displayName cosmético
     * @param azoreaId    id DERIVADO (DA-8) — clave del buzón
     * @param x25519Pub   X25519 X.509 — p/ rederivar el id en el receptor
     * @param ed25519Pub  Ed25519 X.509 — pública con la q/ se verifica
     * @param ed25519Priv PKCS8 — firma
     * @param hwCommit    huella d/ hardware — 3.er componente d/ la id
     */
    public record Signer(String displayName, String azoreaId, byte[] x25519Pub,
                         byte[] ed25519Pub, byte[] ed25519Priv, byte[] hwCommit) {

        /** Del identity service d/ la partida. {@code null} si aún no hay identidad. */
        public static Signer fromMod() {
            final var mod = com.azorea.mod.v1211.AzoreaMod.get();
            if (mod == null) return null;
            final var idSvc = mod.identityService();
            if (idSvc == null || idSvc.getIdentity() == null) return null;
            final var me = idSvc.getIdentity();
            return new Signer(me.displayName(), me.azoreaId(), me.x25519PublicKey(),
                    idSvc.signingPublicKey(), idSvc.ed25519PrivateKey(), idSvc.hwCommit());
        }
    }

    // ===== Observación d/ endpoint TCP =====

    /** Dirección pública q/ tu NAT acaba d/ asignarle a UN puerto local concreto. */
    public record Observed(String ip, int port) {
    }

    /** JSON d/ {@code GET /punch/observe}. */
    private static final class ObservedJson {
        String ip;
        int port;
    }

    /**
     * Observa el endpoint público <b>TCP</b> q/ queda para {@code localPort}.
     *
     * <p>§ <b>Por qué TCP y ⊘ STUN</b>: STUN mide el mapeo <b>UDP</b>; el punch d'Azorea
     * es TCP. En un NAT cónico el mapeo es por (protocolo, puerto local) ⇒ medir uno ⊘
     * da el otro. Esto conecta <b>desde el propio puerto del punch</b> al tracker ⇒ el
     * tracker ve tu dirección <b>ya traducida</b>, q/ es exactamente lo q/ el peer ha d/
     * discar — y de paso <b>crea el mapeo</b> (q/ si no no existe hasta q/ sale un paquete).
     *
     * <p>§ <b>∋ el bind es lo q/ no se puede perder</b>: si ligáramos efímero, el puerto
     * observado pertenecería a OTRO socket y el peer discaría a un sitio q/ ya no existe.
     *
     * <p>§ <b>JDK ⊘ deja fijar el puerto local en {@code HttpClient}</b> ⇒ HTTP/1.1 a mano
     * sobre un {@code Socket} ligado. 12 líneas, sin dependencias nuevas.
     *
     * @param trackerUrl URL base d/ un tracker ALCANZABLE desde fuera (el embebido solo
     *                   sirve en loopback — ése es justo el punto d/ W-1)
     * @param localPort  puerto del punch: tiene q/ ser el MISMO q/ se anuncia y se puntea
     */
    public static Optional<Observed> observe(final String trackerUrl, final int localPort) {
        final String base = trackerUrl.endsWith("/")
                ? trackerUrl.substring(0, trackerUrl.length() - 1) : trackerUrl;
        final URI uri;
        try {
            uri = URI.create(base + "/punch/observe");
        } catch (final IllegalArgumentException e) {
            AzoreaNetLog.failure(Category.PUNCH, "observe", trackerUrl, "URL inválida: " + e.getMessage());
            return Optional.empty();
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            AzoreaNetLog.failure(Category.PUNCH, "observe", trackerUrl,
                    "solo http — el tracker embebido no es TLS (" + uri.getScheme() + ")");
            return Optional.empty();
        }
        final String host = uri.getHost();
        final int trackerPort = uri.getPort() > 0 ? uri.getPort() : 80;
        // § localPort 0 = MODO SONDA (AzoreaRendezvous): bind efímero ⇒ sirve p/ saber
        //   SI responde y QUÉ IP pública ve. ⊘ sirve p/ puntear (el puerto d/ vuelta es
        //   efímero y nadie lo discará) — p/ eso reservePort() da un puerto real.
        if (host == null || localPort < 0) {
            AzoreaNetLog.failure(Category.PUNCH, "observe", trackerUrl,
                    "host nulo o puerto local inválido (" + localPort + ")");
            return Optional.empty();
        }

        try (Socket s = new Socket()) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(localPort));
            s.setSoTimeout(OBSERVE_TIMEOUT_MS);
            s.connect(new InetSocketAddress(host, trackerPort), OBSERVE_TIMEOUT_MS);

            final String path = uri.getRawPath() == null || uri.getRawPath().isEmpty()
                    ? "/punch/observe" : uri.getRawPath();
            final String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + trackerPort + "\r\n"
                    + "Accept: application/json\r\n"
                    + "Connection: close\r\n\r\n";
            s.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();

            final String raw = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            final int eol = raw.indexOf("\r\n");
            final int cut = raw.indexOf("\r\n\r\n");
            if (eol < 0 || cut < 0) {
                AzoreaNetLog.failure(Category.PUNCH, "observe", trackerUrl, "respuesta malformada");
                return Optional.empty();
            }
            if (!raw.substring(0, eol).contains(" 200 ")) {
                AzoreaNetLog.failure(Category.PUNCH, "observe", trackerUrl,
                        abbreviate(raw.substring(0, eol) + " " + raw.substring(cut + 4)));
                return Optional.empty();
            }
            final ObservedJson json;
            try {
                json = GSON.fromJson(raw.substring(cut + 4), ObservedJson.class);
            } catch (final RuntimeException e) {
                AzoreaNetLog.failure(Category.PUNCH, "observe", trackerUrl, "JSON inválido");
                return Optional.empty();
            }
            if (json == null || json.ip == null || json.port < 1 || json.port > 65535) {
                AzoreaNetLog.failure(Category.PUNCH, "observe", trackerUrl, "sin endpoint válido");
                return Optional.empty();
            }
            AzoreaNetLog.info(Category.PUNCH, "observe local=" + localPort + " → "
                    + json.ip + ":" + json.port);
            return Optional.of(new Observed(json.ip, json.port));
        } catch (final IOException e) {
            AzoreaNetLog.failure(Category.PUNCH, "observe", "local=" + localPort, e.toString());
            return Optional.empty();
        }
    }

    /** Puerto libre q/ ligaremos para el punch (se cierra y se vuelve a ligar ⇒ mismas manos). */
    private static int reservePort() {
        // § ⊘ SO_REUSEADDR a propósito: con él, Windows puede devolver un puerto q/ OTRO
        //   socket ya tiene (semántica trampa d/ Windows) y nos quedaríamos c/ un "puerto
        //   libre" q/ no lo está. Sin él el SO sólo da puertos REALMENTE libres.
        try (ServerSocket ss = new ServerSocket()) {
            ss.bind(new InetSocketAddress(0));
            return ss.getLocalPort();
        } catch (final IOException e) {
            AzoreaNetLog.failure(Category.PUNCH, "reserve-port", "-", e.toString());
            return -1;
        }
    }

    // ===== Anuncio =====

    /**
     * Publica un endpoint <b>firmado</b>, observado ya, opcionalmente dirigido a alguien.
     *
     * @param target id d/ q/ queremos q/ nos encuentre, o {@code null} (modo host: espera
     *               a q/ alguien publique c/ {@code target=nuestro id})
     */
    public static boolean announceSigned(final String trackerUrl, final Signer me,
                                         final Observed endpoint, final String target) {
        final String payload;
        try {
            final AzoreaPunchExchange.Endpoint unsigned = AzoreaPunchExchange.unsigned(
                    me.displayName(), endpoint.ip(), endpoint.port(),
                    Base64.getEncoder().encodeToString(me.x25519Pub()),
                    Base64.getEncoder().encodeToString(me.ed25519Pub()),
                    Base64.getEncoder().encodeToString(me.hwCommit()),
                    System.currentTimeMillis() + AzoreaPunchExchange.DEFAULT_TTL_MS);
            payload = AzoreaPunchExchange.toJson(
                    AzoreaPunchExchange.sign(unsigned, me.ed25519Priv()));
        } catch (final GeneralSecurityException | IllegalArgumentException e) {
            AzoreaNetLog.failure(Category.PUNCH, "announce", me.azoreaId(), e.getMessage());
            return false;
        }
        try {
            final String body = GSON.toJson(new Envelope(me.azoreaId(), payload, target));
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trackerUrl + "/punch/announce"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            final HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                AzoreaNetLog.success(Category.PUNCH, "announce",
                        me.azoreaId() + " " + endpoint.ip() + ":" + endpoint.port()
                                + (target == null ? "" : " → " + target), 0);
                return true;
            }
            AzoreaNetLog.failure(Category.PUNCH, "announce", me.azoreaId(),
                    "HTTP " + response.statusCode() + " " + abbreviate(response.body()));
            return false;
        } catch (final IOException e) {
            AzoreaNetLog.failure(Category.PUNCH, "announce", me.azoreaId(), e.toString());
            return false;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** JSON d/ salida — Gson escapa el payload q/ contiene JSON anidado. */
    private static final class Envelope {
        final String azoreaId;
        final String payload;
        final String target;

        Envelope(final String azoreaId, final String payload, final String target) {
            this.azoreaId = azoreaId;
            this.payload = payload;
            this.target = target;
        }
    }

    /**
     * Los cuerpos d/ error d/ un tracker son JSON largo ⇒ cortar p/ q/ entre en el log
     * y se vea el motivo real (igual criterio q/ `AzoreaInvitePasteScreen.abbreviate`).
     */
    private static String abbreviate(final String msg) {
        if (msg == null) return "";
        final String oneLine = msg.replace('\n', ' ').trim();
        return oneLine.length() <= 96 ? oneLine : oneLine.substring(0, 93) + "...";
    }

    /**
     * Devuelve el endpoint del peer si hay announce válido y <b>no expiró</b>.
     *
     * <p>§ Tres comprobaciones en el cliente (el tracker ya verificó al almacenar, pero
     * no hay q/ fiarse d/ él):
     * <ol>
     *   <li>firma + auto-certificación + caducidad — {@link AzoreaPunchExchange}.</li>
     *   <li><b>ata id↔clave</b>: el id <b>rederivado</b> debe ser EXACTAMENTE el q/
     *       polleamos. Si un rendezvous hostil colara el announce d/ otro peer (firma
     *       válida, pero d/ otro), aquí saltaría.</li>
     * </ol>
     */
    public static Optional<PeerEndpoint> pollPeer(final String trackerUrl, final String peerAzoreaId) {
        try {
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trackerUrl + "/punch/poll?azoreaId=" + peerAzoreaId))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            final HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            final PunchAnnounceJson parsed = GSON.fromJson(response.body(), PunchAnnounceJson.class);
            if (parsed == null || parsed.payload == null || parsed.payload.isBlank()) {
                AzoreaNetLog.failure(Category.PUNCH, "poll-peer", peerAzoreaId,
                        "announce sin firma (tracker viejo o client antiguo)");
                return Optional.empty();
            }

            final AzoreaPunchExchange.Verified verified;
            try {
                verified = AzoreaPunchExchange.parseAndVerify(
                        parsed.payload, System.currentTimeMillis());
            } catch (final IllegalArgumentException e) {
                AzoreaNetLog.failure(Category.PUNCH, "poll-peer", peerAzoreaId, e.getMessage());
                return Optional.empty();
            }

            if (!peerAzoreaId.equals(verified.azoreaId())) {
                AzoreaNetLog.failure(Category.PUNCH, "poll-peer", peerAzoreaId,
                        "id rederivado " + verified.azoreaId() + " ≠ esperado ⇒ sustitución");
                return Optional.empty();
            }

            AzoreaNetLog.info(Category.PUNCH, "peer " + peerAzoreaId + " → "
                    + verified.ip() + ":" + verified.port());
            return Optional.of(new PeerEndpoint(verified.ip(), verified.port()));
        } catch (final IOException e) {
            // § B-2: antes se tragaba el IOException ⊘ log ⇒ el user veía solo el
            // timeout genérico y ⊘ podía diagnosticar (refused vs DNS vs caído).
            AzoreaNetLog.failure(Category.PUNCH, "poll-peer", peerAzoreaId, e.toString());
            return Optional.empty();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** Peer q/ nos habló — announce <b>dirigido</b> a nosotros (modo host). */
    public record PeerFound(String azoreaId, String publicIp, int port) {
    }

    /**
     * Busca al peer q/ puso {@code target=nuestro id} — el host, q/ <b>no sabe</b> quién
     * va a unirse y por tanto no puede pollear "por su id".
     *
     * <p>Mismas 3 comprobaciones que {@link #pollPeer}: firma ⊕ auto-certificación ⊕
     * id rederivado == clave del buzón. Un rendezvous hostil solo puede <b>no</b>
     * entregarnos el announce, ⊘ sustituirlo.
     */
    public static Optional<PeerFound> pollForMe(final String trackerUrl, final String myAzoreaId) {
        try {
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trackerUrl + "/punch/poll?target=" + myAzoreaId))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            final HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            final PunchAnnounceJson parsed = GSON.fromJson(response.body(), PunchAnnounceJson.class);
            if (parsed == null || parsed.azoreaId == null || parsed.payload == null
                    || parsed.payload.isBlank()) {
                AzoreaNetLog.failure(Category.PUNCH, "poll-for-me", myAzoreaId,
                        "announce sin firma (tracker viejo o client antiguo)");
                return Optional.empty();
            }
            final AzoreaPunchExchange.Verified verified;
            try {
                verified = AzoreaPunchExchange.parseAndVerify(
                        parsed.payload, System.currentTimeMillis());
            } catch (final IllegalArgumentException e) {
                AzoreaNetLog.failure(Category.PUNCH, "poll-for-me", myAzoreaId, e.getMessage());
                return Optional.empty();
            }
            // § La clave del buzón es un dato del ALAMBRE ⇒ se rederiva y se compara.
            if (!parsed.azoreaId.equals(verified.azoreaId())) {
                AzoreaNetLog.failure(Category.PUNCH, "poll-for-me", myAzoreaId,
                        "id rederivado " + verified.azoreaId() + " ≠ clave " + parsed.azoreaId);
                return Optional.empty();
            }
            AzoreaNetLog.info(Category.PUNCH, "nos habla " + verified.azoreaId()
                    + " → " + verified.ip() + ":" + verified.port());
            return Optional.of(new PeerFound(verified.azoreaId(), verified.ip(), verified.port()));
        } catch (final IOException e) {
            AzoreaNetLog.failure(Category.PUNCH, "poll-for-me", myAzoreaId, e.toString());
            return Optional.empty();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    // ===== Intercambio completo =====

    /** Duración del punch en sí (independiente d/ c/ánto esperamos al peer). */
    private static final int PUNCH_TIMEOUT_MS = 5000;

    /**
     * El atado completo: <b>puerto → observa → anuncia → espera → punchea</b>.
     *
     * <p>§ <b>Simétrico</b> — no hay rol fijo, solo cambia si ya conoces al peer:
     * <pre>
     *   joiner (peerId = id del host, q/ lo trae el bundle)
     *        anuncia  target = hostId      ← el host lo encuentra sin listar el buzón
     *        pollea   ?azoreaId = hostId   ← lo q/ el host anunció
     *
     *   host   (peerId = null)
     *        anuncia  target = null
     *        pollea   ?target = mi id      ← sólo q/ alguien le hable
     * </pre>
     * Los dos corren <b>a la vez</b> ⇒ ambos pollean hasta verse y disparan el punch en
     * ventanas que se solapan (intervalo d'encuesta 250 ms).
     *
     * <p>§ <b>Puerto: 1 por lado, y es el MISMO en las 3 fases</b> — se reserva, se
     * observa y se puntea. Un efímero en cualquiera d/ las 3 rompe la cadena: el peer
     * discaría a un puerto q/ no es el del punch.
     *
     * <p>§ Al salir <b>limpia</b> su announce (ya caduca solo a 30 s, pero no dejar
     * basura apuntando a un socket muerto).
     *
     * @param trackerUrls trackers ALCANZABLES por ambos — se anuncia en <b>TODOS</b> y se
     *                    pollea en todos (el peer puede tener solo uno d/ ellos en config)
     * @param me          nuestra identidad
     * @param peerId      id del peer si lo conocemos; {@code null} = modo host
     * @param waitMs      cuánto esperar a q/ el peer aparezca
     * @return socket TCP ESTABLISHED, listo p/ {@link AzoreaLocalProxy}
     */
    public static Optional<Socket> exchange(final String trackerUrl, final Signer me,
                                            final String peerId, final int waitMs) {
        return exchange(List.of(trackerUrl), me, peerId, waitMs);
    }

    public static Optional<Socket> exchange(final List<String> trackerUrls, final Signer me,
                                            final String peerId, final int waitMs) {
        // § Discriminador (DA-11 aplicado al rendezvous): SONDAR y ordenar. Un candidato
        //   mal puntuado no se excluye — `announceAll` y el poll siguen recorriendo TODOS.
        final List<AzoreaRendezvous.Candidate> ranked = AzoreaRendezvous.rank(trackerUrls);
        final List<String> urls = ranked.isEmpty()
                ? preferReachable(trackerUrls)   // sin sondas (p.ej. interrumpido) ⇒ orden estático
                : ranked.stream().map(AzoreaRendezvous.Candidate::url).toList();
        if (urls.isEmpty()) {
            AzoreaNetLog.failure(Category.PUNCH, "exchange", me.azoreaId(), "s/ tracker q/ usar");
            return Optional.empty();
        }

        // (1) Puerto + observación. Se prueba en orden y se PREFIERE la IP pública:
        //     un tracker en LAN ve nuestra IP privada — sirve p/ mapeo, ⊘ p/ que el peer
        //     nos disque desde internet. El puerto queda atado al q/ dé la buena.
        int localPort = -1;
        Observed observed = null;
        Observed fallback = null;
        int fallbackPort = -1;
        for (final String u : urls) {
            final int candidate = reservePort();
            if (candidate <= 0) continue;
            final Optional<Observed> o = observe(u, candidate);
            if (o.isEmpty()) continue;
            if (fallback == null) {
                fallback = o.get();
                fallbackPort = candidate;
            }
            if (isPublicIp(o.get().ip())) {
                observed = o.get();
                localPort = candidate;
                break;
            }
        }
        if (observed == null && fallback != null) {
            observed = fallback;
            localPort = fallbackPort;
        }
        if (observed == null) {
            AzoreaNetLog.failure(Category.PUNCH, "exchange", me.azoreaId(),
                    "s/ endpoint observable en " + urls.size() + " tracker(s) ⇒ ⊘ punch (W-1)");
            return Optional.empty();
        }
        if (!isPublicIp(observed.ip())) {
            AzoreaNetLog.failure(Category.PUNCH, "exchange", me.azoreaId(),
                    "observe devolvió " + observed.ip() + " ⇒ tracker LOCAL ⇒ solo LAN. "
                            + "P/ WAN hace falta `trackers.urls` en azorea.toml con un "
                            + "rendezvous ALCANZABLE por ambos (DA-10.1 permite 1 d/ tercero).");
        }

        // (2) Anuncio firmado en TODOS — basta q/ el peer pollee uno d/ ellos.
        if (!announceAll(urls, me, observed, peerId)) {
            return Optional.empty();
        }

        // (3) Espera — re-anunciando xq/ el TTL son 30 s.
        final long deadline = System.currentTimeMillis() + waitMs;
        PeerEndpoint their = null;
        long lastAnnounce = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            Optional<PeerEndpoint> found = Optional.empty();
            for (final String u : urls) {
                found = peerId == null
                        ? pollForMe(u, me.azoreaId())
                                .map(f -> new PeerEndpoint(f.publicIp(), f.port()))
                        : pollPeer(u, peerId);
                if (found.isPresent()) break;
            }
            if (found.isPresent()) {
                their = found.get();
                break;
            }
            if (System.currentTimeMillis() - lastAnnounce >= REANNOUNCE_MS) {
                lastAnnounce = System.currentTimeMillis();
                announceAll(urls, me, observed, peerId);
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                clearAll(urls, me.azoreaId());
                return Optional.empty();
            }
        }
        if (their == null) {
            AzoreaNetLog.failure(Category.PUNCH, "exchange", me.azoreaId(),
                    "sin peer en " + waitMs + "ms"
                            + (peerId == null ? " (host esperando a q/ le hablen)"
                                    : " (el host no anuncia — ¿está hosteando?)"));
            clearAll(urls, me.azoreaId());
            return Optional.empty();
        }

        // (4) Punch — desde el MISMO puerto q/ observamos.
        AzoreaNetLog.milestone(Category.PUNCH, "exchange",
                "peer " + their.publicIp() + ":" + their.port()
                        + " ⇒ simultaneous open desde local " + localPort);
        final Optional<Socket> sock =
                punchTcp(localPort, their.publicIp(), their.port(), PUNCH_TIMEOUT_MS);
        clearAll(urls, me.azoreaId());
        if (sock.isEmpty()) {
            AzoreaNetLog.failure(Category.PUNCH, "exchange", me.azoreaId(),
                    "punch sin ruta — NAT simétrico/CGNAT ⇒ toca relay (DA-11)");
        }
        return sock;
    }

    /** Anuncia en todas; true si al menos <b>una</b> lo aceptó. */
    private static boolean announceAll(final List<String> urls, final Signer me,
                                       final Observed endpoint, final String target) {
        boolean any = false;
        for (final String u : urls) {
            if (announceSigned(u, me, endpoint, target)) {
                any = true;
            }
        }
        return any;
    }

    private static void clearAll(final List<String> urls, final String azoreaId) {
        for (final String u : urls) {
            clear(u, azoreaId);
        }
    }

    /** Loopback al FINAL — es el tracker q/ menos puede decirnos nuestra IP real. */
    private static List<String> preferReachable(final List<String> urls) {
        if (urls == null) return List.of();
        return urls.stream()
                .filter(u -> u != null && !u.isBlank())
                .distinct()
                .sorted(Comparator.comparingInt(AzoreaPunchManager::rankUrl))
                .toList();
    }

    /** p/ `AzoreaRendezvous.rank()` (mismo package) — ver score(). */
    static int rankUrl(final String url) {
        try {
            final String host = URI.create(url).getHost();
            if (host == null) return 2;
            if (host.equalsIgnoreCase("localhost") || host.startsWith("127.") || host.equals("::1")) {
                return 2;   // loopback
            }
            if (isPrivateIp(host)) return 1;   // LAN: enrutable localmente, ⊘ desde internet
            return 0;
        } catch (final IllegalArgumentException e) {
            return 2;   // URL rota ⇒ q' no priorice
        }
    }

    /**
     * ¿La IP es enrutable <b>públicamente</b>? Si no, el announce firmado con ella sirve
     * solo p/ LAN — el peer discaría a una dirección q/ no existe p/ él.
     */
    static boolean isPublicIp(final String h) {
        return h != null && !h.isBlank() && !isLoopback(h) && !isPrivateIp(h)
                && !h.equals("0.0.0.0") && !h.equals("::") && !h.startsWith("fe80");
    }

    static boolean isLoopback(final String h) {
        if (h == null) return true;
        return h.equalsIgnoreCase("localhost") || h.startsWith("127.") || h.equals("::1");
    }

    static boolean isPrivateIp(final String h) {
        if (h == null) return false;
        if (h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254.")) {
            return true;
        }
        if (h.startsWith("172.")) {
            final int dot = h.indexOf('.', 4);
            if (dot > 4) {
                try {
                    final int second = Integer.parseInt(h.substring(4, dot));
                    return second >= 16 && second <= 31;
                } catch (final NumberFormatException ignored) {
                    // no es IPv4 d/ 4 octetos
                }
            }
        }
        return false;
    }

    /** Limpia el announce de este peer. */
    public static void clear(final String trackerUrl, final String azoreaId) {
        try {
            final String body = "{\"azoreaId\":\"" + azoreaId + "\"}";
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trackerUrl + "/punch/clear"))
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
        } catch (final Exception ignored) {
            // best-effort
        }
    }

    /**
     * Hole-punch TCP — <b>simultaneous open SIN listener</b> (RFC 793 §3.4).
     *
     * <p>§ <b>FIX 2026-10-01 — bug raíz d/ M-3.</b> La versión anterior abría un
     * {@code ServerSocket} en {@code localPort} <b>y además</b> intentaba {@code bind()}
     * el socket saliente a ESE mismo puerto. En Windows eso es {@code BindException} ⇒
     * caía al {@code catch} y seguía <b>sin bind</b> (puerto efímero) ⇒ el SYN salía d/
     * OTRO puerto ⇒ el mapeo NAT creado no cubría al listener ⇒ en la práctica era un
     * <b>probe d/ alcance</b>, ⊘ un punch. Eso es literalmente M-3.
     *
     * <p>§ <b>¿Y el listener?</b> No hace falta. En simultaneous open el propio socket en
     * `SYN-SENT` recibe el SYN entrante — <b>verificado 2026-10-01</b> por
     * {@code AzoreaTcpSimultaneousOpenTest}: 2 sockets, <b>ninguno escuchando</b>, se
     * establecieron <b>al primer intento</b> y pasaron datos en ambos sentidos.
     * ∴ <b>UN socket = UN puerto</b> = el q/ se anuncia.
     *
     * <p>§ <b>Timing</b>: cada lado reintenta c/ {@link #PUNCH_INTERVAL_MS}. Si el peer
     * ⊘ está aún en `SYN-SENT`, el kernel contesta <b>RST</b> ⇒ `connect()` falla
     * <b>rápido</b> (refused) ⇒ es barato reintentar. La ventana la abre el protocolo
     * d/ announce/poll: ambos pullean y discan en el mismo intervalo.
     *
     * @param localPort puerto LOCAL q/ se binda y q/ el peer disca — tiene q/ ser el
     *                   MISMO q/ anunciamos (0 = efímero ⇒ 1 sola dirección, ⊘ punch)
     * @param peerIp    IP anunciada por el peer
     * @param peerPort  puerto anunciado por el peer
     * @param timeoutMs timeout total
     * @return socket ESTABLISHED listo p/ usar, o vacío
     */
    public static Optional<Socket> punchTcp(final int localPort,
                                            final String peerIp, final int peerPort,
                                            final int timeoutMs) {
        AzoreaNetLog.attempt(Category.PUNCH, "tcp-simultaneous-open",
                "local=" + localPort + " peer=" + peerIp + ":" + peerPort
                        + " timeout=" + timeoutMs + "ms");
        if (localPort <= 0) {
            AzoreaNetLog.info(Category.PUNCH, "localPort=0 ⇒ efímero: el peer no podrá discar"
                    + " a este lado ⇒ 1 sola dirección (eso es un probe, ⊘ punch)");
        }

        // Cada intento = bind(L) + connect(peer). RST ⇒ fallo rápido ⇒ reintentar.
        final int perAttempt = Math.max(PUNCH_INTERVAL_MS,
                Math.min(1000, timeoutMs / Math.max(1, PUNCH_ATTEMPTS)));
        final long deadline = System.currentTimeMillis() + timeoutMs;
        String lastReason = "sin intentos";
        int attempt = 0;

        while (System.currentTimeMillis() < deadline) {
            attempt++;
            final long attemptStart = System.currentTimeMillis();
            Socket s = null;
            try {
                s = new Socket();
                s.setReuseAddress(true);
                // § EL MISMO puerto q/ se anuncia — ése era el contrato roto.
                s.bind(new InetSocketAddress(localPort));
                s.connect(new InetSocketAddress(peerIp, peerPort), perAttempt);
                final Socket ok = s;
                s = null;   // ⇒ transferencia d/ propiedad: el finally NO lo cierra
                AzoreaNetLog.milestone(Category.PUNCH, "tcp-punch",
                        "connected to " + peerIp + ":" + peerPort + " (intento " + attempt + ")");
                return Optional.of(ok);
            } catch (final IOException e) {
                lastReason = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage());
                // § RST rápido ⇒ "su stack está, su socket todavía no" ⇒ reintentar YA.
                //   Silencio (timeout) ⇒ el NAT derramó el SYN ⇒ esperar (y dura vivo el mapeo).
                final long spent = System.currentTimeMillis() - attemptStart;
                try {
                    Thread.sleep(spent < FAST_FAIL_MS ? FAST_RETRY_MS : PUNCH_INTERVAL_MS);
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            } finally {
                if (s != null) {   // socket fallido ⇒ drenar (idempotente)
                    try {
                        s.close();
                    } catch (final IOException ignored) {
                    }
                }
            }
        }

        // § Diagnóstico honesto: un BindException es de NOSOTROS (puerto local ocupado),
        //   ⊘ del NAT. Decir "CGNAT" ahí manda a diagnosticar mal.
        final String hint = lastReason != null && lastReason.contains("BindException")
                ? "PUERTO LOCAL OCUPADO por otro socket — el punch necesita ese puerto exacto"
                : "NAT simétrico/CGNAT, o el peer no está discando";
        AzoreaNetLog.failure(Category.PUNCH, "tcp-simultaneous-open", peerIp + ":" + peerPort,
                "timeout " + timeoutMs + "ms tras " + attempt + " intentos — " + lastReason
                        + " (" + hint + ")");
        return Optional.empty();
    }

    /**
     * Endpoint del peer, <b>verificado</b>: ip+port vienen d/ un payload firmado y c/ el
     * id rederivado atado al q/ polleamos.
     *
     * @param publicIp dirección anunciada (firmada) del peer
     * @param port     puerto q/ el peer quiere q/ le disquen (antes `localPort` — nombre
     *                 engañoso: nunca era "nuestro" puerto local, era el d/ EL OTRO)
     */
    public record PeerEndpoint(String publicIp, int port) {
    }

    /** JSON shape del PunchAnnounce q/ devuelve el tracker (envoltorio, payload firmado). */
    private static final class PunchAnnounceJson {
        String azoreaId;
        String payload;
        long timestampMs;
    }
}
