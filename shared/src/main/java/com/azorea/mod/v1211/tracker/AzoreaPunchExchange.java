// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.tracker;

import com.azorea.mod.v1211.identity.AzoreaId;
import com.azorea.mod.v1211.identity.AzoreaIdentity;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * Intercambio de endpoints firmado — el "PEER_FOUND" de Azorea (DA-12).
 *
 * <p>§ Qué es: el payload q/ viaja por el <b>rendezvous</b> cuando dos peers se
 * encuentran y cada uno le dice al otro <i>"púncame en esta dirección"</i>. Sustituye
 * al intercambio PLANO de PeerCraft (su {@code PEER_FOUND} no lleva firma).
 *
 * <p>§ Por qué firmar (innovación sobre su base):
 * <pre>
 *   Sin firma:  rendezvous HOSTIL dice "el otro está en 1.2.3.4"   ⇒ te REDIRIGE
 *   Con firma:  la dirección va c/ la Ed25519 d/ su dueño ⊕ el id se DERIVA d/ ella
 *               ⇒ un rendezvous hostil solo puede NO ATENDIÉRTE     ⇒ corte, ⊘ redirect
 * </pre>
 * Quien controla el rendezvous <b>no puede mentir</b>: no posee la clave privada d/ la
 * dirección q/ anuncia. Y ⊘ se confía en ningún campo d/ identidad del alambre — el
 * {@code azorea_id} se rederiva (DA-8), igual q/ en el bundle v2.
 *
 * <p>§ Relación c/ el bundle: el endpoint q/ va DENTRO del {@code AZB1.…} ya está firmado
 * por la firma d/ canónico. Este es el hueco contrario — la dirección q/ llega por el
 * <b>reverse path</b> (joiner → host) v/ {@code /punch/announce}, q/ hasta ahora viajaba
 * en claro y era spoofable por cualquier host d/ tracker.
 *
 * <p>§ Formato: JSON v/ Gson (mismo criterio q/ {@code TrackerProtocol}). El canónico q/ se
 * firma es determinista y <b>sin</b> {@code signature} (evita ciclicidad — idéntico al
 * truco d/ {@code canonicalForSigning} del bundle).
 *
 * <p>§ Caducidad: los announces d/ punch son d/ vida corta (TTL ~30 s).
 */
public final class AzoreaPunchExchange {

    /** Prefijo d/ versión del canónico firmado. */
    public static final String CANONICAL_VERSION = "azorea-punch/v1";

    /** Vida por defecto d/ un announce (coincide c/ el TTL del tracker). */
    public static final long DEFAULT_TTL_MS = 30_000L;

    private static final Gson GSON = new Gson();

    private AzoreaPunchExchange() {
    }

    /**
     * Endpoint firmado d/ un peer.
     *
     * <p>§ <b>⊗ validación en el compact constructor</b>: Gson construye el record por su
     * canónico al deserializar, así que un throw aquí se pierde dentro d/ {@code fromJson}
     * y el parseador no puede dar un mensaje preciso. La validación vive en {@link #unsigned}
     * (al construir) y en {@link #parseAndVerify} (al recibir), q/ es donde tiene sentido
     * dar mensajes accionables.
     *
     * <p>Las 3 claves son obligatorias: DA-8 define
     * {@code azorea_id = f(x25519, ed25519, hwCommit)} ⇒ sin las 3 no se puede rederivar
     * el id, y sin rederivación habría q/ <i>creerle</i> al alambre (DA-8 existe p/ eso).
     *
     * @param displayName cosmético p/ logs
     * @param ip          dirección q/ el peer quiere q/ le puncen
     * @param port        puerto UDP <b>externo</b> (d/ STUN) q/ observó EL PROPIO PEER
     * @param x25519Key   X25519 X.509 (b64) — p/ derivar el id (DA-8)
     * @param signingKey  Ed25519 X.509 (b64) — la q/ firma este mensaje
     * @param hwCommit    hwCommit (b64) — p/ derivar el id (DA-8)
     * @param expEpochMs  caducidad (0 = ⊘ caduca, solo tests)
     * @param signature   Ed25519 (b64) sobre {@link #canonical} — ⊘ se firma a sí misma
     */
    public record Endpoint(
            String displayName,
            String ip,
            int port,
            @SerializedName("x25519_key") String x25519Key,
            @SerializedName("signing_key") String signingKey,
            @SerializedName("hw_commit") String hwCommit,
            long expEpochMs,
            String signature) {
    }

    /** Constructor SIN firma — p/ construir antes d/ firmar (valida entrada). */
    public static Endpoint unsigned(final String displayName, final String ip, final int port,
                                    final String x25519KeyB64, final String signingKeyB64,
                                    final String hwCommitB64, final long expEpochMs) {
        if (ip == null || ip.isBlank()) throw new IllegalArgumentException("falta ip");
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("port fuera de rango: " + port);
        return new Endpoint(displayName, ip, port, x25519KeyB64, signingKeyB64,
                hwCommitB64, expEpochMs, null);
    }

    // ===== Canónico =====

    /**
     * Serialización canónica q/ se firma. Orden fijo, {@code \n} separador, texto saneado
     * (un salto d/ línea dentro d/ name/ip rompería la estructura).
     *
     * <p>⊗ <b>no</b> viaja {@code azorea_id}: se deriva d/ las claves al verificar,
     * igual q/ en el bundle v2 ⇒ ⊘ spoof d/ identidad.
     */
    public static String canonical(final Endpoint e) {
        return CANONICAL_VERSION + "\n"
                + "name=" + sanitize(e.displayName()) + "\n"
                + "ip=" + sanitize(e.ip()) + "\n"
                + "port=" + e.port() + "\n"
                + "x25519=" + sanitize(e.x25519Key()) + "\n"
                + "key=" + sanitize(e.signingKey()) + "\n"
                + "hw=" + sanitize(e.hwCommit()) + "\n"
                + "exp=" + e.expEpochMs() + "\n";
    }

    // ===== Firma =====

    /**
     * Firma el endpoint → copia c/ {@code signature} relleno.
     *
     * @param ed25519Priv PKCS8 d/ la clave d/ firmas del emisor
     */
    public static Endpoint sign(final Endpoint e, final byte[] ed25519Priv)
            throws GeneralSecurityException {
        require(e.x25519Key(), "x25519Key");
        require(e.signingKey(), "signingKey");
        require(e.hwCommit(), "hwCommit");
        final byte[] msg = canonical(e).getBytes(StandardCharsets.UTF_8);
        final String sig = Base64.getEncoder().encodeToString(AzoreaIdentity.sign(ed25519Priv, msg));
        return new Endpoint(e.displayName(), e.ip(), e.port(), e.x25519Key(),
                e.signingKey(), e.hwCommit(), e.expEpochMs(), sig);
    }

    // ===== Verificación =====

    /**
     * Parsea + verifica un endpoint recibido d/ un rendezvous.
     *
     * <p><b>Tres comprobaciones, en orden</b> — cada una descarta un ataque distinto:
     * <ol>
     *   <li><b>Firma</b> → el emisor es quien dice ser, y su clave <b>cubre ip/port</b>
     *       ⇒ un rendezvous hostil ⊘ puede redirigirte.</li>
     *   <li><b>Auto-certificación (DA-8)</b> → el {@code azorea_id} se <b>rederiva</b> d/ las
     *       3 claves; ⊘ se confía en ningún campo d/ identidad d/l alambre.</li>
     *   <li><b>Caducidad</b> → un announce viejo ⊘ se reutiliza.</li>
     * </ol>
     *
     * <p>§ Uso: el caller compara el id derivado c/ el q/ ya verificó d/l bundle (o d/ su
     * friend list). Si el rendezvous intenta colar a OTRO peer ⇒ id distinto ⇒ rechazado.
     *
     * @param json  payload JSON recibido
     * @param nowMs epoch ms actual
     * @return endpoint verificado, c/ {@code azoreaId} ya derivado en el log
     * @throws IllegalArgumentException con la razón concreta (∤ NPE)
     */
    public static Verified parseAndVerify(final String json, final long nowMs) {
        if (json == null || json.isBlank())
            throw new IllegalArgumentException("announce vacío");

        final Endpoint e;
        try {
            e = GSON.fromJson(json, Endpoint.class);
        } catch (final RuntimeException ex) {
            throw new IllegalArgumentException("announce corrupto (JSON inválido)");
        }
        if (e == null) throw new IllegalArgumentException("announce vacío");
        if (e.ip() == null || e.ip().isBlank())
            throw new IllegalArgumentException("announce sin ip");
        if (e.port() < 1 || e.port() > 65535)
            throw new IllegalArgumentException("puerto fuera de rango: " + e.port());
        require(e.x25519Key(), "x25519Key");
        require(e.signingKey(), "signingKey");
        require(e.hwCommit(), "hwCommit");
        if (e.signature() == null || e.signature().isBlank())
            throw new IllegalArgumentException(
                    "announce sin firma (announce antiguo o tracker hostil)");

        final byte[] edPub;
        final byte[] xPub;
        final byte[] hw;
        final byte[] sig;
        try {
            edPub = Base64.getDecoder().decode(e.signingKey());
            xPub = Base64.getDecoder().decode(e.x25519Key());
            hw = Base64.getDecoder().decode(e.hwCommit());
            sig = Base64.getDecoder().decode(e.signature());
        } catch (final IllegalArgumentException ex) {
            throw new IllegalArgumentException("announce c/ base64 inválido");
        }

        // (1) Firma — cubre ip/port ⇒ redirection imposible s/ poseer la clave.
        final byte[] msg = canonical(e).getBytes(StandardCharsets.UTF_8);
        if (!AzoreaIdentity.verifySignature(edPub, msg, sig)) {
            throw new IllegalArgumentException(
                    "firma Ed25519 inválida (¿el rendezvous alteró la dirección?)");
        }

        // (2) Auto-certificación — se REderiva, ⊘ se recibe.
        final String azoreaId = AzoreaId.derive(edPub, xPub, hw);

        // (3) Caducidad.
        if (e.expEpochMs() > 0 && nowMs > e.expEpochMs()) {
            final long min = (nowMs - e.expEpochMs()) / 60_000L;
            throw new IllegalArgumentException("announce caducado (hace " + min + " min)");
        }

        return new Verified(azoreaId, sanitizeIngress(e));
    }

    /** Endpoint verificado + identidad rederivada (DA-8). */
    public record Verified(String azoreaId, Endpoint endpoint) {
        public String ip() {
            return endpoint.ip();
        }

        public int port() {
            return endpoint.port();
        }
    }

    // ===== Internos =====

    private static void require(final String s, final String field) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("falta " + field);
        }
    }

    private static String sanitize(final String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    /**
     * Sanea los campos de texto al <b>ENTRAR</b>.
     *
     * <p>El canónico ya sanea al firmar y al verificar ⇒ la firma sigue cuadrando byte a
     * byte. Esto es aparte y va dirigido al <b>UI</b>: un {@code displayName} vindo con
     * saltos de línea inyecta líneas falsas en un {@code StringWidget} o en el chat
     * (spoof de estado — p. ej. hacer creer que el punch fue OK).
     *
     * <p>Los campos criptográficos (base64) no se tocan: son el material d/ verificación.
     */
    private static Endpoint sanitizeIngress(final Endpoint e) {
        return new Endpoint(sanitize(e.displayName()), sanitize(e.ip()), e.port(),
                e.x25519Key(), e.signingKey(), e.hwCommit(), e.expEpochMs(), e.signature());
    }

    /** Serializa a JSON d/ alambre. */
    public static String toJson(final Endpoint e) {
        return GSON.toJson(e);
    }

    /** Parsea JSON s/ verificar (solo p/ inspección/logging d/ lo q/ llega). */
    public static Endpoint peek(final String json) {
        return json == null || json.isBlank() ? null : GSON.fromJson(json, Endpoint.class);
    }
}
