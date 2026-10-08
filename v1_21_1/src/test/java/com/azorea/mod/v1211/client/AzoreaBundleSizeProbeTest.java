// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import com.azorea.mod.v1211.identity.AzoreaId;
import com.azorea.mod.v1211.identity.AzoreaIdentity;
import com.azorea.mod.v1211.identity.HwFingerprint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sonda: mide la longuitud REAL del invite que se copia al clipboard.
 *
 * <p>§ Por qué medir y no estimar: la estimación a mano estaba desviada — los
 * campos {@code x25519}/{@code ed25519} no llevan 32 B crudos sino X.509 DER de
 * 44 B (prefijo fijo de 12 B) ⇒ ya son 60 chars en base64 y no 44. Decidir "cómo
 * acortar" sobre un cálculo equivocado habría llevado al optmismo erróneo.
 *
 * <p>Escribe el desglose a {@code build/bundle-measure.txt} (o a temp si no puede).
 * No es un test de regresión todavía: primero hay que medir, después se fija el
 * techo y se convierte en aserción.
 */
final class AzoreaBundleSizeProbeTest {

    private static AzoreaInviteBundle.Bundle realisticBundle(
            final AzoreaIdentity.Keys ed, final byte[] x25519, final byte[] hw,
            final String id) {
        // Réplica de lo que construye AzoreaHostSessionScreen (líneas 232-243):
        // candidatos = v4 pública + IPv6 globales + v4 privada al final.
        return new AzoreaInviteBundle.Bundle(
                id,
                "General_Beria",                       // displayName (MC username)
                x25519,                                // X.509 44 B
                ed.publicKey(),                        // X.509 44 B
                hw,                                    // SHA-256 32 B
                "203.0.113.9", 25565,
                UUID.randomUUID().toString(),          // gameId  (36 chars)
                "Mundo de Pruebas",                    // worldName
                "1.21.1",
                System.currentTimeMillis() / 1000L + 3600L,
                java.util.List.of(
                        "203.0.113.9",
                        "2a02:2f08:1111:2222::1"));
    }

    @Test
    @DisplayName("sonda: longuitud real del invite + desglose por campo + compresión")
    void measureBundle() throws Exception {
        final AzoreaIdentity.Keys ed = AzoreaIdentity.generateSigningKeyPair();
        final AzoreaIdentity.Keys x = AzoreaIdentity.generateKeyPair();
        final byte[] hw = new HwFingerprint(
                "50:46:4A:8A:CE:8D", "4C1B-3F2A", "10.0", "DESKTOP").hwCommit();
        final String id = AzoreaId.derive(ed.publicKey(), x.publicKey(), hw);

        final AzoreaInviteBundle.Bundle b = realisticBundle(ed, x.publicKey(), hw, id);
        final String canonical = AzoreaInviteBundle.canonicalForSigning(b);
        final String wire = AzoreaInviteBundle.encode(b, ed.privateKey());

        final String[] seg = wire.split("\\.");
        final int prefixSeg = seg[0].length() + 1;   // "AZB2." = 5 (incluye el punto)
        final int sigLen = seg[2].length();          // 86

        final StringBuilder sb = new StringBuilder();
        sb.append("=== SONDA AZOREA INVITE (tamano) ===\n");
        sb.append("WIRE TOTAL      : ").append(wire.length()).append(" chars\n");
        sb.append("  prefijo       : ").append(prefixSeg).append(" (").append(seg[0]).append(".)\n");
        sb.append("  payload b64   : ").append(seg[1].length()).append(" chars\n");
        sb.append("  firma b64     : ").append(sigLen).append(" chars\n");
        sb.append("CANONICAL       : ").append(canonical.length()).append(" bytes (lo q/ firma)\n");
        sb.append("  => b64url(canonical) SIN comprimir = ")
                .append(b64urlLen(canonical.getBytes(StandardCharsets.UTF_8))).append(" chars\n");
        sb.append("  overhead base64       = ")
                .append(b64urlLen(canonical.getBytes(StandardCharsets.UTF_8)) - canonical.length())
                .append(" chars\n");
        sb.append("\n--- desglose del canonico (por linea) ---\n");

        int acc = 0;
        for (final String line : canonical.split("\n", -1)) {
            if (line.isEmpty()) continue;
            final int eq = line.indexOf('=');
            final String key = eq > 0 ? line.substring(0, eq) : line;
            acc += line.length() + 1;   // +1 = '\n'
            sb.append(String.format("  %-14s %4d bytes   (acum %4d)%s%n",
                    key, line.length() + 1, acc,
                    isFat(key) ? "  <-- MAXIMO APORTE" : ""));
        }
        sb.append("\n--- tamano de claves reales ---\n");
        sb.append("  x25519Pub bytes = ").append(x.publicKey().length)
                .append("  -> b64 = ")
                .append(Base64.getEncoder().encodeToString(x.publicKey()).length()).append('\n');
        sb.append("  ed25519Pub bytes= ").append(ed.publicKey().length)
                .append("  -> b64 = ")
                .append(Base64.getEncoder().encodeToString(ed.publicKey()).length()).append('\n');
        sb.append("  hwCommit bytes  = ").append(hw.length)
                .append("  -> b64 = ")
                .append(Base64.getEncoder().encodeToString(hw).length()).append('\n');
        sb.append("  azoreaId chars  = ").append(id.length()).append('\n');
        sb.append("  (nota) id es DERIVABLE de x25519+ed25519+hwCommit (DA-8) => "
                + "en el canonico es redundante\n");

        // ===== MEDICION: compresion del canonico (deflate raw) =====
        sb.append("\n--- COMPRESION (deflate raw / nowrap, BEST) ---\n");
        sb.append("  (la firma va SIEMPRE sobre el canonico crudo => el algoritmo d/ compresion\n");
        sb.append("   queda FUERA de la firma: se puede cambiar sin romper lectores)\n");
        sb.append(String.format("  %-34s %8s %8s%n", "variante", "payload", "WIRE"));

        // § Fila 1 = CONTRAFACTUAL: lo q/ costaria hoy si no comprimiéramos.
        reportRow(sb, "AZB1 historico (s/ comprimir)",
                b64urlLen(canonical.getBytes(StandardCharsets.UTF_8)), sigLen, prefixSeg);

        // § Fila 2 = lo q/ REALMENTE emite encode() con este bundle (s/ trackers).
        reportRow(sb, "AZB2 real (lo q/ emite hoy)", seg[1].length(), sigLen, prefixSeg);

        // § Fila 3 = lo q/ emite HOY con `trackers=` — el numero q/ importa p/ decidir.
        final AzoreaInviteBundle.Bundle conTrackers = new AzoreaInviteBundle.Bundle(
                b.azoreaId(), b.displayName(), b.x25519Pub(), b.ed25519Pub(), b.hwCommit(),
                b.host(), b.port(), b.gameId(), b.worldName(), b.mcVersion(),
                b.expiresAtEpochSec(), b.hosts(),
                java.util.List.of("http://203.0.113.7:9090"));
        final String wireRT = AzoreaInviteBundle.encode(conTrackers, ed.privateKey());
        reportRow(sb, "AZB2 real + trackers= (Vía 2)",
                wireRT.split("\\.")[1].length(), sigLen, prefixSeg);

        // § Cosmetico fuera: name/world/mc — lo q/ el HOST da al conectar (F10/Gap#2).
        final String sinCosmetico = dropLines(canonical, "name=", "world=", "mc=");
        reportRow(sb, "AZB2 - cosmetico (name/world/mc)",
                b64urlLen(deflate(sinCosmetico.getBytes(StandardCharsets.UTF_8))),
                sigLen, prefixSeg);

        final String conTrackersTxt = sinCosmetico + "trackers=http://203.0.113.7:9090\n";
        reportRow(sb, "AZB2 - cosmetico + trackers=",
                b64urlLen(deflate(conTrackersTxt.getBytes(StandardCharsets.UTF_8))),
                sigLen, prefixSeg);

        sb.append("  (aviso) las filas miden el PAYLOAD EN b64url — comparar 434 b64 con\n");
        sb.append("          266 bytes crudos seria mentir: +33 % es el b64.\n");

        // § Techo de regresion (fijado 2026-10-02 tras MEDIR, no estimar):
        //   v1 = 422 B canonico -> 655 chars de invite
        //   v2 = 325 B canonico -> 526 chars  (id 34 + host 21 + game 42 = 97 B fuera)
        //   v2+AZB2 (deflate) + trackers= -> 482 chars  <<< HOY
        // Holgura p/ listas de candidatos mas largas (c/ IPv6 extra suman ~40 B
        // de canonico ~= 54 chars sin comprimir, menos con deflate). Si esto salta,
        // el formato crecio.
        assertTrue(wire.length() <= 540,
                "invite crecio: " + wire.length() + " chars (AZB2 medido = 482 c/ trackers, "
                        + "526 era el techo d'AZB1, 655 el d'v1)");
        // § El numero q/ importa: el formato q/ se ESCRIBE ya, c/ el campo nuevo.
        assertTrue(wireRT.length() <= 560,
                "invite c/ trackers crecio: " + wireRT.length() + " chars");

        final String report = sb.toString();

        // Escribir a build/ del directorio de trabajo del test, con fallback a temp.
        Path out = null;
        try {
            final Path build = Path.of(System.getProperty("user.dir"), "build");
            Files.createDirectories(build);
            out = build.resolve("bundle-measure.txt");
            Files.writeString(out, report, StandardCharsets.UTF_8);
        } catch (final Exception e) {
            try {
                out = Files.createTempFile("bundle-measure", ".txt");
                Files.writeString(out, report, StandardCharsets.UTF_8);
            } catch (final Exception e2) {
                // sin donde escribir: al menos que falle con el numero
                throw new AssertionError("WIRE=" + wire.length(), e2);
            }
        }

        System.out.println(report);
        System.out.println("sonda escrita en: " + out.toAbsolutePath());
        assertFalse(wire.isEmpty());
    }

    private static boolean isFat(final String key) {
        return switch (key) {
            case "x25519", "ed25519", "hw", "id", "hosts", "game" -> true;
            default -> false;
        };
    }

    /** Fila de la tabla de compresion: payload comprimido + los segmentos fijos. */
    private static void reportRow(final StringBuilder sb, final String label,
                                  final int compressedLen, final int sig, final int prefixSeg) {
        // § prefixSeg YA incluye el punto final ("AZB1." = 5) => solo falta el punto
        //   que separa payload de firma.
        final int wire = prefixSeg + compressedLen + 1 + sig;
        sb.append(String.format("  %-34s %8d %8d%n", label, compressedLen, wire));
    }

    /** Quita lineas c/ los prefijos dados — p/ simular el bundle s/ campo. */
    private static String dropLines(final String canonical, final String... prefixes) {
        final StringBuilder out = new StringBuilder();
        for (final String line : canonical.split("\n", -1)) {
            boolean drop = false;
            for (final String p : prefixes) {
                if (line.startsWith(p)) {
                    drop = true;
                    break;
                }
            }
            if (!drop) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Longitud d/ lo q/ REALMENTE viaja: {@code b64url} sin padding — mismo codificador
     * q/ usa {@code AzoreaInviteBundle} (un canónico d/ 325 B da 434 chars, no 325).
     *
     * <p>§ Medir bytes crudos y compararlos c/ chars b64 **sobreestima** el ahorro un
     * 33 %. Ése fue exactamente el error d/ la 1.ª versión d/ esta sonda.
     */
    private static int b64urlLen(final byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data).length();
    }

    /**
     * Deflate <b>raw</b> (nowrap => sin cabecera zlib/gzip de 2-18 B).
     *
     * <p>§ Por que aqui y no en el alambre todavia: solo estamos <b>midiendo</b>. Si el
     * numero no justifica el bump de formato a `AZB2`, no se toca `AzoreaInviteBundle`.
     */
    private static byte[] deflate(final byte[] data) {
        final Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            d.setInput(data);
            d.finish();
            final ByteArrayOutputStream bos =
                    new ByteArrayOutputStream(Math.max(64, data.length / 2));
            final byte[] buf = new byte[1024];
            while (!d.finished()) {
                final int n = d.deflate(buf);
                if (n <= 0) break;
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            d.end();
        }
    }
}
