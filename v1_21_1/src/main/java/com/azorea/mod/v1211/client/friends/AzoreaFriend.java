// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client.friends;

/**
 * Friend entry v2 (ver AGENTS.md § F4.5 + F5.2).
 *
 * <p>v2: introduce {@code azoreaId} como primary identifier (estabiliza identity
 * aunque el MC username cambie). {@code displayName} queda como cosmético
 * (puede cambiar sin invalidar el friend).
 *
 * <p>§ Modelo:
 * <ul>
 *   <li>{@code azoreaId}: identificador estable (opcional si el friend fue añadido por MC username legacy).</li>
 *   <li>{@code displayName}: nombre mostrado en UI (MC username).</li>
 *   <li>{@code publicKeyBase64}: X.509-encoded X25519 public key del friend (necesario para enviar invites cifrados).</li>
 *   <li>{@code addedAt}: timestamp de cuándo se añadió.</li>
 *   <li>{@code lastSeen}: última vez que el friend fue visto online via tracker.</li>
 *   <li>{@code online}: estado actual (actualizado por polling).</li>
 * </ul>
 *
 * <p>§ Limitaciones v1:
 * <ul>
 *   <li>Friends añadidos antes de F5.2 tienen solo {@code displayName} (sin azoreaId).
 *       Hay que re-añadirlos para tener azoreaId (TODO F5.x: lookup por displayName via tracker).</li>
 *   <li>Online status no se actualiza automáticamente (requiere polling activo).</li>
 * </ul>
 */
public record AzoreaFriend(
        String azoreaId,         // null si legacy (added by MC username only)
        String displayName,
        String publicKeyBase64,  // null si legacy o no conocido todavía
        long addedAt,
        long lastSeen,
        boolean online
) {

    public AzoreaFriend {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName vacío");
        }
    }

    /** Constructor para friend nuevo (added ahora). */
    public static AzoreaFriend now(final String azoreaId, final String displayName, final String publicKeyBase64) {
        return new AzoreaFriend(azoreaId, displayName, publicKeyBase64,
                System.currentTimeMillis(), 0L, false);
    }

    /** Constructor para friend legacy (solo MC username). */
    public static AzoreaFriend legacy(final String displayName) {
        return new AzoreaFriend(null, displayName, null,
                System.currentTimeMillis(), 0L, false);
    }

    /** Devuelve una copia con online state actualizado. */
    public AzoreaFriend withOnline(final boolean online, final long lastSeen) {
        return new AzoreaFriend(azoreaId, displayName, publicKeyBase64, addedAt, lastSeen, online);
    }

    /** Devuelve una copia con publicKey actualizado (post-lookup). */
    public AzoreaFriend withPublicKey(final String publicKeyBase64) {
        return new AzoreaFriend(azoreaId, displayName, publicKeyBase64, addedAt, lastSeen, online);
    }
}