// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import com.azorea.mod.v1211.identity.AzoreaId;
import com.azorea.mod.v1211.identity.AzoreaIdentity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * Invite bundle copiable/pegable — ver AGENTS.md § DA-10 (D0 serverless).
 *
 * <p>§ Qué es: <b>un solo artefacto</b> que sustituye a la "card" + al invite.
 * Al verificarlo, el joiner obtiene de golpe (a) la identidad del host — id,
 * claves X25519/Ed25519, hwCommit — p/ guardarla como amigo, y (b) su endpoint
 * público p/ conectar. Sin tracker, sin infraestructura, out-of-band (Discord, etc.).
 *
 * <p>§ Flujo:
 * <pre>{@code
 * HOST                                AMIGO
 * ----                                -----
 * hostea (autohost + UPnP)
 *   → Copy Invite (bundle firmado) →  pega en Azorea
 *                                        → decodeAndVerify()
 *                                        → ConnectScreen.startConnecting(host:port)
 * }</pre>
 *
 * <p>§ Dos comprobaciones, ambas necesarias:
 * <ol>
 *   <li><b>Firma Ed25519</b> sobre el canónico — cubre <i>todo</i> el contenido,
 *       incluido {@code host}. <b>Es la que impide</b> que un tercero que vea el
 *       bundle redirija el puerto a un servidor suyo: sin firma, el ID seguiría
 *       cuadrando (el ID no cubre el endpoint) y la redirección pasaría.</li>
 *   <li><b>Auto-certificación</b> — {@code azorea_id} debe re-derivarse de las
 *       claves publicadas (DA-8). Con la firma sola, alguien podría emitir un
 *       bundle autoconsistente con claves propias; con ambas, la ID, las claves
 *       y el endpoint quedan atados.</li>
 * </ol>
 *
 * <p>§ Formato de alambre: {@code AZB1.<b64url(canonical)>.<b64url(firma)>}.
 * Un único token. El parser <b>elimina todo whitespace</b> antes de leer, así que
 * sobrevive a saltos de línea que meten Discord/WhatsApp al envolver texto largo.
 * B64URL sin padding ⇒ ni {@code .} ni {@code /} ni {@code +} ⇒ el split en 3
 * partes es seguro.
 *
 * <p>§ Caducidad: {@code exp} en epoch seconds. 0 = no caduca (p. ej. tests).
 *
 * <p>§ Requiere: Ed25519 builtin (JDK 15+) — ya usado por DA-8/DA-9.
 */
public final class AzoreaInviteBundle {

    /**
     * Prefijo <b>legacy</b> — payload sin comprimir ({@code b64url(canonical)}).
     * Se sigue <b>leyendo</b>: los bundles ya emitidos siguen siendo válidos.
     */
    public static final String PREFIX = "AZB1";

    /**
     * Prefijo <b>actual</b> — payload {@code b64url(deflate(canonical))}.
     *
     * <p>§ Medido 2026-10-02 con la sonda, ⊘ estimado: 526 → <b>482</b> chars aún
     * <b>añadiendo</b> {@code trackers=} (−8 %). Sin el campo, 450 (−14 %).
     * La firma sigue cubriendo el canónico <b>crudo</b> ⇒ el algoritmo y el nivel
     * de compresión quedan <b>fuera</b> de la firma: se pueden cambiar sin romper
     * lectores.
     */
    public static final String PREFIX_COMPRESSED = "AZB2";

    private AzoreaInviteBundle() {
    }

    /** Contenido verificado de un invite. */
    public record Bundle(
            String azoreaId,
            String displayName,
            byte[] x25519Pub,
            byte[] ed25519Pub,
            byte[] hwCommit,
            String host,
            int port,
            String gameId,
            String worldName,
            String mcVersion,
            long expiresAtEpochSec,
            java.util.List<String> hosts,
            /**
             * Trackers d/ rendezvous c/ los q/ el host anuncia (Vía 2).
             *
             * <p>§ <b>Nuevo 2026-10-02.</b> El host y el joiner tienen q/ hablar c/ el
             * MISMO tracker p/ q/ el punch funcione, y eso no puede salir d/ la config
             * d/ l'amigo (⊘ instala nada). Viaja <b>dentro d' la firma</b> ⇒ nadie puede
             * redirigirte a un tracker suyo. Vacío en bundles antiguos.
             */
            java.util.List<String> trackers) {

        /** Normaliza: un {@code trackers} ausente (bundle viejo) ⇒ vacío, ⊘ null. */
        public Bundle {
            trackers = trackers == null
                    ? java.util.List.of() : java.util.List.copyOf(trackers);
        }

        /**
         * Compatibilidad: los bundles antiguos (sin campo {@code hosts}) solo
         * traen {@code host}. La firma se verifica sobre los bytes RECIBIDOS y
         * el parser ignora campos desconocidos ⇒ añadir campos al canónico
         * <b>no rompe</b> bundles viejos.
         */
        public Bundle(final String azoreaId, final String displayName,
                      final byte[] x25519Pub, final byte[] ed25519Pub, final byte[] hwCommit,
                      final String host, final int port, final String gameId,
                      final String worldName, final String mcVersion,
                      final long expiresAtEpochSec,
                      final java.util.List<String> hosts) {
            this(azoreaId, displayName, x25519Pub, ed25519Pub, hwCommit,
                    host, port, gameId, worldName, mcVersion, expiresAtEpochSec, hosts,
                    java.util.List.of());
        }

        public Bundle(final String azoreaId, final String displayName,
                      final byte[] x25519Pub, final byte[] ed25519Pub, final byte[] hwCommit,
                      final String host, final int port, final String gameId,
                      final String worldName, final String mcVersion,
                      final long expiresAtEpochSec) {
            this(azoreaId, displayName, x25519Pub, ed25519Pub, hwCommit,
                    host, port, gameId, worldName, mcVersion, expiresAtEpochSec,
                    host == null ? java.util.List.of() : java.util.List.of(host));
        }

        /**
         * § D0/T3: todos los endpoints a probar en orden de prioridad. Si no
         * vino {@code hosts} (bundle antiguo), el único es {@code host}.
         */
        public java.util.List<String> candidates() {
            if (hosts != null && !hosts.isEmpty()) return hosts;
            return host == null ? java.util.List.of() : java.util.List.of(host);
        }
    }

    // ===== Codificación =====

    /**
     * Serialización canónica que se firma. Orden fijo, {@code \n} como separador
     * y campos de texto saneados (sin {@code \n} — un salto de línea dentro de
     * {@code name} o {@code world} rompería la estructura de líneas).
     *
     * <p>Versionado con prefijo {@code azorea-invite/vN}: si cambia el conjunto de
     * campos firmados, se bump para que los bundles viejos fallen en vez de
     * verificar mal.
     *
     * <p>§ <b>v2 (2026-10-01) — acortado de 422 a 325 bytes ⇒ invite 655 → 526 chars.</b>
     * Se eliminan 3 campos que aportaban peso y ninguna garantía nueva:
     * <table>
     *   <tr><th>campo</th><th>B</th><th>por qué se va</th></tr>
     *   <tr><td>{@code id}</td><td>34</td>
     *       <td><b>Redundante.</b> DA-8 define {@code azorea_id = f(x25519, ed25519, hw)}.
     *           El receptor lo <b>deriva</b> de las claves: por construcción no puede
     *           mentir, así que es <i>más</i> seguro que creer un valor del alambre.</td></tr>
     *   <tr><td>{@code host}</td><td>21</td>
     *       <td><b>Duplica a {@code hosts}</b> (que sí se firma). El endpoint sigue
     *           cubierto por la firma ⇒ la protección contra redirección no se toca.</td></tr>
     *   <tr><td>{@code game}</td><td>42</td>
     *       <td>Vestigial en el bundle: solo se loguea. El {@code game_id} que de
     *           verdad importa vive en {@code TrackerProtocol}, tipo aparte.</td></tr>
     * </table>
     *
     * <p>§ <b>Compatibilidad de lectura</b>: el parser sigue <b>leyendo</b> los tres
     * campos si aparecen (bundles v1). Solo se dejaron de <i>emitir</i>. Por eso el
     * bump es del canónico y no del token {@link #PREFIX} — el formato de alambre
     * (3 segmentos b64url) no cambió.
     */
    public static String canonicalForSigning(final Bundle b) {
        return "azorea-invite/v2\n"
                + "name=" + sanitize(b.displayName()) + "\n"
                + "x25519=" + enc(b.x25519Pub()) + "\n"
                + "ed25519=" + enc(b.ed25519Pub()) + "\n"
                + "hw=" + enc(b.hwCommit()) + "\n"
                + "hosts=" + sanitize(hostsCsv(b)) + "\n"
                + "port=" + b.port() + "\n"
                + "world=" + sanitize(b.worldName()) + "\n"
                + "mc=" + sanitize(b.mcVersion()) + "\n"
                + "exp=" + b.expiresAtEpochSec() + "\n"
                + trackersLine(b);
    }

    /**
     * {@code trackers=} — <b>sólo si hay</b>.
     *
     * <p>§ Emitirlo vacío costaría ~11 B d'gratis en cada invite. Y como la firma
     * cubre los bytes recibidos (no un re-serializado), un bundle viejo sin la línea
     * sigue verificando igual.
     */
    private static String trackersLine(final Bundle b) {
        final java.util.List<String> t = b.trackers();
        if (t == null || t.isEmpty()) return "";
        return "trackers=" + sanitize(String.join(",", t)) + "\n";
    }

    /**
     * Codifica + firma → string copiable al clipboard.
     *
     * @param ed25519Priv clave privada Ed25519 (PKCS8) del host
     * @return {@code AZB1.<payload>.<firma>}
     * @throws GeneralSecurityException si la firma falla
     * @throws IllegalArgumentException si falta material obligatorio
     */
    public static String encode(final Bundle b, final byte[] ed25519Priv)
            throws GeneralSecurityException {
        require(b.azoreaId(), "azoreaId");
        // § v2: `host` ya no se emite (duplicaba `hosts`). Validamos lo que de verdad
        // se firma — hostsCsv() cae a `host` si `hosts` falta, así que un Bundle solo
        // con `host` sigue codificando. Si los dos vienen en blanco, no hay endpoint.
        final String endpoints = sanitize(hostsCsv(b));
        if (endpoints.isBlank()) {
            throw new IllegalArgumentException("falta host/hosts");
        }
        if (b.x25519Pub() == null || b.x25519Pub().length == 0)
            throw new IllegalArgumentException("falta x25519Pub");
        if (b.ed25519Pub() == null || b.ed25519Pub().length == 0)
            throw new IllegalArgumentException("falta ed25519Pub");
        if (b.hwCommit() == null || b.hwCommit().length == 0)
            throw new IllegalArgumentException("falta hwCommit");
        if (b.port() < 1 || b.port() > 65535)
            throw new IllegalArgumentException("port fuera de rango: " + b.port());

        // Se firma el canónico tal cual lo produce canonicalForSigning() (que ya
        // sanea texto). El receptor verifica sobre los bytes RECIBIDOS, así que
        // encode y verify no tienen que re-serializar — mismo criterio que
        // signAnnounce (F9): el mensaje firmado y el verificado son idénticos.
        final byte[] msg = canonicalForSigning(b).getBytes(StandardCharsets.UTF_8);
        final byte[] sig = AzoreaIdentity.sign(ed25519Priv, msg);
        // § AZB2: el payload va COMPRIMIDO, la firma va sobre el canónico CRUDO.
        //   ⇒ el deflate queda fuera d/ la firma (se puede cambiar sin romper lectores)
        //   y un lector antiguo q/ no entienda AZB2 lo rechaza con mensaje claro.
        return PREFIX_COMPRESSED + "." + b64url(deflate(msg)) + "." + b64url(sig);
    }

    // ===== Verificación =====

    /**
     * Parsea y verifica un bundle pegado por el usuario.
     *
     * @param text    texto copiado (tolerante a whitespace/saltos de línea)
     * @param nowSec  epoch seconds actual, p/ comprobar caducidad
     * @return bundle verificado
     * @throws IllegalArgumentException con la razón concreta si algo no cuadra
     */
    public static Bundle decodeAndVerify(final String text, final long nowSec) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("invite vacío");
        }
        // Los chats envuelven texto largo: quitamos TODO whitespace antes de parsear.
        final String cleaned = text.replaceAll("\\s", "");
        final String[] parts = cleaned.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "no es un invite de Azorea (" + PREFIX + ", se esperaban 3 partes; hay "
                            + parts.length + ")");
        }
        // § Dos prefijos válidos: AZB1 = payload crudo (legacy), AZB2 = comprimido.
        //   Un lector viejo q/ sólo conoce AZB1 recibe un mensaje claro, ⊘ un crash.
        final boolean compressed = PREFIX_COMPRESSED.equals(parts[0]);
        if (!compressed && !PREFIX.equals(parts[0])) {
            throw new IllegalArgumentException(
                    "versión de invite desconocida: " + parts[0]
                            + " (esperado " + PREFIX_COMPRESSED + " o " + PREFIX + ")");
        }

        final byte[] payload;
        final byte[] sig;
        try {
            payload = Base64.getUrlDecoder().decode(parts[1]);
            sig = Base64.getUrlDecoder().decode(parts[2]);
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("invite corrupto (base64 inválido)");
        }

        // § El canónico CRUDO es lo q/ firma Ed25519 en los dos casos — el deflate
        //   sólo es transporte, por eso descomprimir ANTES de verificar.
        final byte[] msg;
        if (compressed) {
            msg = inflate(payload);
        } else {
            msg = payload;
        }

        final Bundle raw = parseCanonical(new String(msg, StandardCharsets.UTF_8));

        // Claves primero: sin ellas no hay nada que verificar ni de dónde derivar.
        if (raw.x25519Pub() == null || raw.ed25519Pub() == null || raw.hwCommit() == null) {
            throw new IllegalArgumentException("invite incompleto (faltan claves o hwCommit)");
        }

        // (1) Auto-certificación (DA-8).
        //
        // § v2 (2026-10-01): el id NO viaja en el alambre ⇒ se DERIVA de las claves.
        //    f(claves) por construcción ⇒ no puede mentir, es más fuerte que creer un
        //    valor externo, y ahorra 34 B (45 chars de invite).
        // § v1 (bundle antiguo, con `id=`): el id SÍ viene de fuera ⇒ se mantiene la
        //    comprobación clásica y hay que demostrar que cuadra con las claves.
        final String wireId = raw.azoreaId();
        final String id;
        if (wireId == null || wireId.isBlank()) {
            id = AzoreaId.derive(raw.ed25519Pub(), raw.x25519Pub(), raw.hwCommit());
        } else if (AzoreaId.verify(wireId, raw.ed25519Pub(), raw.x25519Pub(), raw.hwCommit())) {
            id = wireId;
        } else {
            throw new IllegalArgumentException(
                    "el azorea_id no corresponde a las claves del invite (¿suplantación?)");
        }

        // (2) Firma: cubre TODO el canónico — incluido `hosts`, del que sale el
        // endpoint. Sigue impidiendo que un tercero redirija la conexión.
        if (!AzoreaIdentity.verifySignature(raw.ed25519Pub(), msg, sig)) {
            throw new IllegalArgumentException("firma Ed25519 inválida (¿contenido alterado?)");
        }

        // (3) Endpoint: v2 lo resuelve desde `hosts` (firmado); v1 lo traía en `host`.
        final String host = (raw.host() != null && !raw.host().isBlank())
                ? raw.host()
                : (raw.hosts() == null || raw.hosts().isEmpty() ? null : raw.hosts().get(0));
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("falta host/hosts");
        }

        // (4) Puerto.
        if (raw.port() < 1 || raw.port() > 65535) {
            throw new IllegalArgumentException("puerto fuera de rango: " + raw.port());
        }

        // (5) Caducidad.
        if (raw.expiresAtEpochSec() > 0 && nowSec > raw.expiresAtEpochSec()) {
            final long min = (nowSec - raw.expiresAtEpochSec()) / 60;
            throw new IllegalArgumentException("invite caducado (hace " + min + " min)");
        }

        // Resolución: id derivado + endpoint unificado en `hosts` (normalizado para
        // que no filtren nulls; candidates() ya no necesita su fallback).
        final java.util.List<String> hosts = (raw.hosts() == null || raw.hosts().isEmpty())
                ? java.util.List.of(host)
                : raw.hosts();
        return new Bundle(id, raw.displayName(), raw.x25519Pub(), raw.ed25519Pub(), raw.hwCommit(),
                host, raw.port(), raw.gameId(), raw.worldName(), raw.mcVersion(),
                raw.expiresAtEpochSec(), hosts, raw.trackers());
    }

    // ===== Internos =====

    private static Bundle parseCanonical(final String canonical) {
        String id = null;
        String name = null;
        byte[] x25519 = null;
        byte[] ed25519 = null;
        byte[] hw = null;
        String host = null;
        java.util.List<String> hosts = null;
        int port = 0;
        String game = null;
        String world = null;
        String mc = null;
        long exp = 0L;
        java.util.List<String> trackers = null;

        for (final String line : canonical.split("\n")) {
            final int eq = line.indexOf('=');
            if (eq <= 0) continue;   // línea sin clave → ignorar
            final String v = line.substring(eq + 1);
            switch (line.substring(0, eq)) {
                case "id" -> id = v;
                case "name" -> name = v;
                case "x25519" -> x25519 = decB64(v);
                case "ed25519" -> ed25519 = decB64(v);
                case "hw" -> hw = decB64(v);
                case "host" -> host = v;
                case "hosts" -> hosts = parseHosts(v);
                case "port" -> port = parseIntSafe(v);
                case "game" -> game = v;
                case "world" -> world = v;
                case "mc" -> mc = v;
                case "exp" -> exp = parseLongSafe(v);
                case "trackers" -> trackers = parseHosts(v);   // CSV, mismo saneado q/ hosts
                default -> {
                    // campo desconocido → ignorar (compat hacia adelante)
                }
            }
        }
        // Campo `hosts` ausente (bundle antiguo) → candidates() cae a [host].
        return new Bundle(id, name, x25519, ed25519, hw, host, port, game, world, mc, exp,
                hosts == null ? null : java.util.List.copyOf(hosts), trackers);
    }

    /** {@code "1.2.3.4,2001:db8::1"} → lista saneada, sin duplicados ni vacíos. */
    private static java.util.List<String> parseHosts(final String v) {
        if (v == null || v.isBlank()) return java.util.List.of();
        final java.util.List<String> out = new java.util.ArrayList<>();
        for (final String s : v.split(",")) {
            final String t = s.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /** Versión CSV de los candidatos p/ el campo `hosts` del canónico. */
    private static String hostsCsv(final Bundle b) {
        final java.util.List<String> h = b.hosts() != null && !b.hosts().isEmpty()
                ? b.hosts()
                : (b.host() == null ? java.util.List.of() : java.util.List.of(b.host()));
        return String.join(",", h);
    }

    /** Quitar saltos de línea y retorn que romperían la estructura del canónico. */
    private static String sanitize(final String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static void require(final String s, final String field) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("falta " + field);
        }
    }

    private static String enc(final byte[] b) {
        return b == null ? "" : Base64.getEncoder().encodeToString(b);
    }

    private static byte[] decB64(final String v) {
        if (v == null || v.isEmpty()) return null;
        try {
            return Base64.getDecoder().decode(v);
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("campo base64 inválido");
        }
    }

    private static String b64url(final byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /**
     * Deflate <b>raw</b> (nowrap ⇒ sin cabecera zlib/gzip d/ 2-18 B) — el formato
     * de alambre ya trae su propio separador, la cabecera sólo gastaría chars.
     *
     * <p>§ Sólo transporta: la firma va sobre el canónico crudo ⇒ cambiar de nivel
     * (o incluso de algoritmo) no rompe bundles ya emitidos.
     *
     * <p>§ <b>package-private</b> (⊗ private): el test necesita descomprimir →
     * tamperar el canónico → recomprimir p/ probar q/ la firma cae. Sin esto el
     * helper del test tendría q/ DUPLICAR el algoritmo y se desincronizaría.
     */
    static byte[] deflate(final byte[] data) {
        final java.util.zip.Deflater d =
                new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION, true);
        try {
            d.setInput(data);
            d.finish();
            final java.io.ByteArrayOutputStream out =
                    new java.io.ByteArrayOutputStream(Math.max(64, data.length / 2));
            final byte[] buf = new byte[1024];
            while (!d.finished()) {
                final int n = d.deflate(buf);
                if (n <= 0) break;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            d.end();
        }
    }

    /**
     * Inversa d/ {@link #deflate}.
     *
     * <p>§ Cota d/ salida: un payload corrupto o malicioso podría inflar a GB
     * (zip-bomb). El canónico real ≤ ~4 KB ⇒ 64 KB de techo sobra con creces y
     * evita q/ un invite pegado nos reviente la JVM.
     *
     * <p>§ <b>package-private</b> — ver {@link #deflate}.
     */
    static byte[] inflate(final byte[] data) {
        final java.util.zip.Inflater i = new java.util.zip.Inflater(true);
        try {
            i.setInput(data);
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            final byte[] buf = new byte[1024];
            int total = 0;
            while (!i.finished()) {
                final int n = i.inflate(buf);
                if (n == 0) {
                    if (i.needsInput() || i.needsDictionary()) break;
                    continue;
                }
                total += n;
                if (total > 64 * 1024) {
                    throw new IllegalArgumentException("invite comprimido demasiado grande");
                }
                out.write(buf, 0, n);
            }
            if (!i.finished()) {
                throw new IllegalArgumentException("invite comprimido truncado o corrupto");
            }
            return out.toByteArray();
        } catch (final java.util.zip.DataFormatException e) {
            throw new IllegalArgumentException("invite comprimido corrupto");
        } finally {
            i.end();
        }
    }

    private static int parseIntSafe(final String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("entero inválido: '" + v + "'");
        }
    }

    private static long parseLongSafe(final String v) {
        try {
            return Long.parseLong(v.trim());
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("timestamp inválido: '" + v + "'");
        }
    }
}
