// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.access;

import com.azorea.mod.v1211.identity.AzoreaId;
import com.azorea.mod.v1211.identity.AzoreaIdentity;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Verificación de identidad Azorea en la fase de CONFIGURATION (F10, § A2).
 *
 * <p><b>§ Puro a propósito.</b> Este fichero <b>no</b> toca ningún tipo de MC ni de red —
 * sólo crypto del JDK y {@link AzoreaId}. Así la pieza de seguridad se testea entera sin
 * Minecraft (igual que {@code TrackerProtocol} o {@code AzoreaInviteBundle}), y el cableado
 * ({@code IdentityProofPayload} → aquí) queda reducido a un mapeo de campos.
 *
 * <p><b>§ Qué demuestra un proof válido</b> — y qué NO:
 * <ul>
 *   <li><b>SÍ</b>: que quien responde <b>posee la clave Ed25519</b> que deriva ese
 *       {@code azorea_id} (auto-certificación DA-8) y que firmó <b>exactamente</b> el
 *       reto de <b>esta</b> conexión (anti-replay: el reto es de un solo uso).</li>
 *   <li><b>NO</b>: que el nombre de MC declarado sea suyo. Con {@code online-mode=false}
 *       nadie puede probarlo — por eso el nombre sólo <b>flaggea</b> duplicados aparentes
 *       en {@link AzoreaWorldAccess} y nunca decide el acceso.</li>
 * </ul>
 *
 * <p><b>§ Límite de diseño</b>: aquí <b>no</b> se decide si entra. Sólo se decide <i>si
 * puede demostrar quién es</i>. Que entre o no tras eso lo dice
 * {@link AzoreaWorldAccess#register} (auto-registro universal + la única excepción
 * manual, {@code blocked}).
 */
public final class AzoreaAccessGate {

    /**
     * Dominio del mensaje firmado. Concatenado con {@code 0x00} + reto ⇒ un reto capturado
     * de otra parte del protocolo <b>no</b> sirve aquí, y viceversa.
     */
    public static final String CANONICAL_DOMAIN = "azorea-gate/v1";

    /** Versión soportada del formato. */
    public static final String VERSION = "1";

    /** Reto de 32 B: suficiente contra adivinanza, 44 chars en Base64. */
    public static final int CHALLENGE_BYTES = 32;

    /** Firma Ed25519 = 64 B. */
    public static final int ED25519_SIG_BYTES = 64;

    /** {@code hwCommit} = SHA-256 = 32 B. */
    public static final int HW_COMMIT_BYTES = 32;

    /** Límite vanilla de un nombre de jugador (MC 1.21.1). */
    public static final int MAX_MC_NAME = 16;

    // ===== Límites de cada campo (toda validación vive aquí) =====
    public static final int MAX_VERSION = 8;
    public static final int MAX_ID = 40;
    public static final int MAX_NAME = 40;
    public static final int MAX_KEY_B64 = 256;
    public static final int MAX_SIG_B64 = 256;

    private AzoreaAccessGate() {
    }

    // ===== Utilidades de transporte =====

    /** Codifica bytes crudos a Base64 estándar (sin saltos de línea). */
    public static String encode(final byte[] raw) {
        return raw == null ? null : Base64.getEncoder().encodeToString(raw);
    }

    /** Decodifica; devuelve {@code null} si no es Base64 válido o está vacío (no lanza). */
    public static byte[] decode(final String b64) {
        if (b64 == null || b64.isBlank()) {
            return null;
        }
        try {
            final byte[] out = Base64.getDecoder().decode(b64);
            return out.length == 0 ? null : out;
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    /** Reto aleatorio de un solo uso por conexión. */
    public static byte[] newChallenge(final SecureRandom random) {
        final byte[] c = new byte[CHALLENGE_BYTES];
        random.nextBytes(c);
        return c;
    }

    /**
     * Mensaje que el cliente debe firmar: {@code "azorea-gate/v1"} {@code 0x00} reto.
     *
     * <p>El separador binario evita la ambigüedad clásica de concatenar sin él (p.ej. que un
     * dominio y un reto arbitrarios colisionen).
     */
    public static byte[] canonic(final byte[] challenge) {
        if (challenge == null) {
            throw new IllegalArgumentException("challenge nulo");
        }
        final byte[] domain = CANONICAL_DOMAIN.getBytes(StandardCharsets.UTF_8);
        final byte[] out = new byte[domain.length + 1 + challenge.length];
        System.arraycopy(domain, 0, out, 0, domain.length);
        out[domain.length] = 0x00;
        System.arraycopy(challenge, 0, out, domain.length + 1, challenge.length);
        return out;
    }

    // ===== El proof, ya desligado de la capa de red =====

    /**
     * La respuesta del cliente, reducida a sus campos. El payload de red sólo se usa para
     * meter/quitar esto del {@code FriendlyByteBuf}.
     *
     * @param version        versión del formato
     * @param azoreaId       ID auto-certificante {@code AZ-XXXXXX-…}
     * @param displayName    nombre de MC declarado
     * @param ed25519PubB64  clave de FIRMA (X.509, Base64)
     * @param x25519PubB64   clave X25519 (X.509, Base64)
     * @param hwCommitB64    huella de hardware, 32 B (Base64)
     * @param signatureB64   firma Ed25519 del canónico (64 B, Base64)
     */
    public record Proof(String version, String azoreaId, String displayName,
                        String ed25519PubB64, String x25519PubB64,
                        String hwCommitB64, String signatureB64) {

    }

    /**
     * @param ok         {@code true} si la identidad está demostrada
     * @param reason     motivo legible cuando {@code ok == false} (para el kick y el log)
     * @param azoreaId   ID auto-certificante verificada
     * @param displayName nombre de MC declarado (sin validar como propio — sólo limitado)
     * @param ed25519PubB64 clave pública de firma (Base64)
     * @param x25519PubB64  clave X25519 (Base64)
     * @param hwCommitB64   huella de hardware (Base64)
     */
    public record Verdict(boolean ok, String reason, String azoreaId, String displayName,
                          String ed25519PubB64, String x25519PubB64, String hwCommitB64) {

        public static Verdict fail(final String reason) {
            return new Verdict(false, reason, null, null, null, null, null);
        }
    }

    // ===== La verificación =====

    /**
     * Verifica un proof contra el reto de esta conexión.
     *
     * <p>Nunca lanza: cualquier entrada hostil devuelve {@code Verdict} con motivo. Un
     * payload no puede tumbar la conexión del server (regla: input validado, sin NPE).
     */
    public static Verdict verify(final Proof proof, final byte[] challenge) {
        if (proof == null) {
            return Verdict.fail("payload nulo");
        }
        if (challenge == null || challenge.length != CHALLENGE_BYTES) {
            return Verdict.fail("challenge local inválido (" + CHALLENGE_BYTES + " B)");
        }

        // --- Versión ---
        if (!VERSION.equals(proof.version())) {
            return Verdict.fail("versión de protocolo no soportada: " + abbrev(proof.version()));
        }

        // --- Presencia y longitud de TODOS los campos ---
        final String reason = fieldProblem(proof);
        if (reason != null) {
            return Verdict.fail(reason);
        }

        // --- Base64 ---
        final byte[] ed = decode(proof.ed25519PubB64());
        final byte[] x = decode(proof.x25519PubB64());
        final byte[] hw = decode(proof.hwCommitB64());
        final byte[] sig = decode(proof.signatureB64());
        if (ed == null || x == null || hw == null || sig == null) {
            return Verdict.fail("campos binarios no son Base64 válido");
        }
        if (hw.length != HW_COMMIT_BYTES) {
            return Verdict.fail("hwCommit debe medir " + HW_COMMIT_BYTES + " B, no " + hw.length);
        }
        if (sig.length != ED25519_SIG_BYTES) {
            return Verdict.fail("firma debe medir " + ED25519_SIG_BYTES + " B, no " + sig.length);
        }

        // --- 1) Auto-certificación (DA-8): la CLAVE deriva la ID reclamada ---
        //    Un atacante puede firmar con su propia clave, pero entonces derive() devuelve
        //    OTRA id ⇒ verify() false ⇒ no puede hacerse pasar por la víctima.
        if (!AzoreaId.verify(proof.azoreaId(), ed, x, hw)) {
            return Verdict.fail("la ID reclamada no corresponde a las claves publicadas"
                    + " (auto-certificación fallida)");
        }

        // --- 2) Firma del reto de ESTA conexión (anti-replay) ---
        //    Demuestra posesión de la clave privada en este preciso intercambio.
        final byte[] message = canonic(challenge);
        if (!AzoreaIdentity.verifySignature(ed, message, sig)) {
            return Verdict.fail("la firma no valida contra el reto de esta conexión"
                    + " (¿clave ajena o replay de otra sesión?)");
        }

        return new Verdict(true, null, proof.azoreaId(), proof.displayName(),
                proof.ed25519PubB64(), proof.x25519PubB64(), proof.hwCommitB64());
    }

    /** @return motivo si el campo es vacío o supera su límite, o {@code null} si está bien */
    private static String checkField(final String name, final String value, final int max) {
        if (value == null || value.isBlank()) {
            return "campo '" + name + "' vacío";
        }
        if (value.length() > max) {
            return "campo '" + name + "' demasiado largo ("
                    + value.length() + " > " + max + ")";
        }
        return null;
    }

    /** @return motivo si algún campo es vacío o está fuera de límite, o {@code null} */
    private static String fieldProblem(final Proof p) {
        // Se evalúan todos antes de mirar el primero: son comparaciones de longitud, no
        // hay coste, y así el motivo que se devuelve es estable y legible.
        final String[] problems = {
                checkField("version", p.version(), MAX_VERSION),
                checkField("azoreaId", p.azoreaId(), MAX_ID),
                checkField("displayName", p.displayName(), MAX_NAME),
                checkField("ed25519PubB64", p.ed25519PubB64(), MAX_KEY_B64),
                checkField("x25519PubB64", p.x25519PubB64(), MAX_KEY_B64),
                checkField("hwCommitB64", p.hwCommitB64(), MAX_KEY_B64),
                checkField("signatureB64", p.signatureB64(), MAX_SIG_B64),
        };
        for (final String problem : problems) {
            if (problem != null) {
                return problem;
            }
        }

        // El nombre es lo único que MC exige tener forma válida además de corta.
        final String name = p.displayName().trim();
        if (name.length() > MAX_MC_NAME) {
            return "displayName supera el límite de MC ("
                    + name.length() + " > " + MAX_MC_NAME + ")";
        }
        if (!name.matches("[A-Za-z0-9_]{1," + MAX_MC_NAME + "}")) {
            return "displayName con caracteres no válidos para MC: " + abbrev(name);
        }
        if (!name.equals(p.displayName())) {
            return "displayName con espacios sobrantes";
        }

        // El id debe tener forma canónica ANTES de comprobar la deriva — si no, un id
        // raro llegaría a derive() y el motivo sería confuso.
        final String id = p.azoreaId();
        if (id.length() != 30 || !id.startsWith("AZ-")) {
            return "azoreaId con formato inválido (se espera AZ-XXXXXX-XXXXXX-XXXXXX-XXXXXX)";
        }
        return null;
    }

    /** Resumen corto para logs — nunca volcar campos completos. */
    private static String abbrev(final String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 24 ? s : s.substring(0, 24) + "…";
    }
}
