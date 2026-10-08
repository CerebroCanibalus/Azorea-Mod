// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.identity;

/**
 * Base32 encoder para IDs Azorea (ver AGENTS.md § F5.1).
 *
 * § Alfabeto:
 *   "0123456789ABCDEFGHJKMNPQRSTUVWXYZ" (32 chars, no I/O/L para legibilidad).
 *   Mismo alfabeto que {@code TrackerProtocol.newInviteCode()} para consistencia visual
 *   entre azorea_ids e invite_codes.
 *
 * § Codificación:
 *   - 5 bytes → 8 chars (40 bits)
 *   - 15 bytes → 24 chars (120 bits, perfecto para IDs)
 *   - Sin padding; si quedan bits sueltos al final, se codifican parciales.
 *
 * § Uso:
 *   Para Azorea IDs: SHA-256(fingerprint) → primeros 15 bytes → encode() → prefix "AZ-".
 */
public final class AzoreaBase32 {

    /** Alfabeto Crockford-ish (32 chars, sin I/O/L para legibilidad). */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();

    private AzoreaBase32() {
        // utility
    }

    /**
     * Codifica bytes a base32 (este alfabeto). Sin padding.
     *
     * @param bytes datos a codificar (no null)
     * @return string base32
     */
    public static String encode(final byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes no puede ser null");
        }
        if (bytes.length == 0) {
            return "";
        }
        final StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsInBuffer = 0;
        for (final byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsInBuffer += 8;
            while (bitsInBuffer >= 5) {
                final int idx = (buffer >> (bitsInBuffer - 5)) & 0x1F;
                out.append(ALPHABET[idx]);
                bitsInBuffer -= 5;
            }
        }
        if (bitsInBuffer > 0) {
            // Pad con ceros a la derecha para los bits restantes.
            final int idx = (buffer << (5 - bitsInBuffer)) & 0x1F;
            out.append(ALPHABET[idx]);
        }
        return out.toString();
    }
}