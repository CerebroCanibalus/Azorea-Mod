// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Azorea ID auto-certificante (ver AGENTS.md § DA-8).
 *
 * <p><b>§ Por qué v2:</b> en v1 el ID era {@code SHA-256(HW)} — independiente de
 * cualquier clave. Consecuencia: una firma sobre el anuncio NO probaba identidad,
 * porque un atacante generaba su propio par de claves y firmaba con él (verificación
 * autoconsistente pasaba). Además había una fisura: la ID decía "esta máquina"
 * (HW) mientras la clave venía del archivo — si se perdía {@code identity.json}
 * se conservaba la ID con clave nueva y cualquier confianza se rompía.
 *
 * <p><b>§ Fórmula v2:</b>
 * <pre>{@code
 * canonical = "azorea-id/v2" || 0x00 || ed25519Pub || 0x00 || x25519Pub || 0x00 || hwCommit
 * azorea_id = "AZ-" + base32( SHA-256(canonical)[:15] )   // dashes cada 6 chars
 * }</pre>
 *
 * <p><b>§ Qué aporta cada componente:</b>
 * <ul>
 *   <li><b>ed25519Pub</b> — la clave que FIRMA. Es la componente crítica: sin ella
 *       atada al ID, la firma no prueba nada. Verificar = recomputar el ID con la
 *       clave publicada y comparar.</li>
 *   <li><b>x25519Pub</b> — la clave de ECDH (cifrado de invites). Añadida por
 *       defensa en profundidad: si alguien la intercambiara en un anuncio, el ID
 *       ya no cuadraría. (Aun sin esto, la firma cubre el campo — redundante pero
 *       barato y elimina una clase entera de errores futuros.)</li>
 *   <li><b>hwCommit</b> — SHA-256 del fingerprint de máquina. <b>No aporta
 *       seguridad</b> (las claves ya la dan): aporta la estabilidad por máquina
 *       que era la intención del modelo C original. Cambiar de equipo → ID nuevo,
 *       igual que en v1.</li>
 * </ul>
 *
 * <p><b>§ Verificación (cualquier peer o tracker):</b> dado un anuncio con
 * {@code azoreaId}, {@code ed25519Pub}, {@code x25519Pub} y {@code hwCommit}
 * publicados, se recomputa y se compara con el ID reclamado. Si cuadra + la
 * firma Ed25519 es válida sobre el contenido del anuncio → identidad probada.
 * Sin TOFU, sin autoridad, sin lockout.
 *
 * <p><b>§ Ruptura:</b> v2 cambia el ID de todos los usuarios existentes.
 * Justificado: pre-release (sin usuarios reales) — ver AGENTS.md § DA-8.
 */
public final class AzoreaId {

    /** Domain separator — evita colisiones con otros esquemas de hash. */
    public static final String DOMAIN = "azorea-id/v2";

    /** Bytes del hash usados para el ID (120 bits, mismo tamaño que v1). */
    private static final int ID_BYTES = 15;

    private AzoreaId() {
    }

    /**
     * Deriva el azorea_id v2 desde las claves y el commit de hardware.
     *
     * @param ed25519Pub clave pública Ed25519 (X.509 encoded)
     * @param x25519Pub  clave pública X25519 (X.509 encoded)
     * @param hwCommit   SHA-256(canonical(hw_fingerprint)) — 32 bytes
     * @return ID con formato canónico {@code AZ-XXXXXX-XXXXXX-XXXXXX-XXXXXX}
     * @throws IllegalArgumentException si algún argumento es vacío
     */
    public static String derive(final byte[] ed25519Pub, final byte[] x25519Pub,
                                final byte[] hwCommit) {
        require(ed25519Pub, "ed25519Pub");
        require(x25519Pub, "x25519Pub");
        require(hwCommit, "hwCommit");

        final byte[] domain = DOMAIN.getBytes(StandardCharsets.UTF_8);
        final byte[] buf = new byte[domain.length + 1 + ed25519Pub.length + 1
                + x25519Pub.length + 1 + hwCommit.length];
        int off = 0;
        System.arraycopy(domain, 0, buf, off, domain.length);
        off += domain.length;
        buf[off++] = 0;
        System.arraycopy(ed25519Pub, 0, buf, off, ed25519Pub.length);
        off += ed25519Pub.length;
        buf[off++] = 0;
        System.arraycopy(x25519Pub, 0, buf, off, x25519Pub.length);
        off += x25519Pub.length;
        buf[off++] = 0;
        System.arraycopy(hwCommit, 0, buf, off, hwCommit.length);

        final byte[] hash = sha256(buf);
        final byte[] fifteen = Arrays.copyOf(hash, ID_BYTES);
        return format(AzoreaBase32.encode(fifteen));
    }

    /**
     * Verifica que un azorea_id reclamado corresponda realmente a las claves +
     * hwCommit publicados. Esta es la comprobación de auto-certificación.
     *
     * @return true si {@code claimedId == derive(ed, x, hwCommit)}
     */
    public static boolean verify(final String claimedId, final byte[] ed25519Pub,
                                 final byte[] x25519Pub, final byte[] hwCommit) {
        if (claimedId == null || ed25519Pub == null || x25519Pub == null || hwCommit == null) {
            return false;
        }
        try {
            return claimedId.equals(derive(ed25519Pub, x25519Pub, hwCommit));
        } catch (final RuntimeException e) {
            return false;
        }
    }

    /**
     * Aplica el formato canónico al string base32 de 24 chars:
     * {@code AZ-XXXXXX-XXXXXX-XXXXXX-XXXXXX}.
     *
     * <p>Si ya está formateado o no tiene 24 chars, se devuelve tal cual
     * (idempotente — no rompe IDs ya canónicos).
     */
    public static String format(final String raw24) {
        if (raw24 == null) return null;
        // "AZ-" (3) + 24 + 3 dashes = 30 chars ya formateado.
        if (raw24.startsWith("AZ-") && raw24.length() == 30) return raw24;
        if (raw24.length() != 24) return raw24;
        return "AZ-" + raw24.substring(0, 6) + "-" + raw24.substring(6, 12)
                + "-" + raw24.substring(12, 18) + "-" + raw24.substring(18, 24);
    }

    private static byte[] sha256(final byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en el JRE", e);
        }
    }

    private static void require(final byte[] b, final String name) {
        if (b == null || b.length == 0) {
            throw new IllegalArgumentException(name + " no puede ser vacío");
        }
    }
}
