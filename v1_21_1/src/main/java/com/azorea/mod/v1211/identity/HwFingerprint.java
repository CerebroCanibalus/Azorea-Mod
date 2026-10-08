// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.Locale;

/**
 * Fingerprint de hardware para Azorea Identity (ver AGENTS.md § F5.1, modelo C).
 *
 * § Multi-factor binding (anti-spoof):
 *   - {@code macAddress}: NIC primaria (no loopback, no virtual, UP). Java API builtin.
 *   - {@code hardwareUuid}: SMBIOS UUID via WMI/CIM (Windows). NO es MachineGuid
 *     (cambia con cada instalación de Windows). SMBIOS UUID vive en la placa base.
 *   - Fallback a MAC-only si el SO no es Windows o si la extracción falla.
 *
 * § No-invasivo:
 *   - NIC MAC: API Java builtin, sin permisos especiales.
 *   - SMBIOS UUID: query WMI read-only, sin elevación.
 *   - PowerShell: binario universal en Windows 10+.
 *
 * § Limitaciones v1:
 *   - Linux/macOS: solo MAC. Spoof-resistant solo en Windows.
 *   - Si la SMBIOS UUID falla (formato custom, VM con UUID vacío): fingerprint = MAC.
 *
 * § Privacidad:
 *   - Solo el SHA-256 del fingerprint se usa como azorea_id.
 *   - Los componentes individuales NO se exponen fuera del mod.
 *   - Se loguean a nivel DEBUG (no INFO).
 */
public record HwFingerprint(
        String macAddress,
        String hardwareUuid,
        String osVersion,
        String hostname
) {

    private static final Logger LOGGER = LoggerFactory.getLogger(HwFingerprint.class);

    /** Hash SHA-256 → 15 bytes (120 bits) → base32 con prefijo "AZ-". */
    private static final int FINGERPRINT_BYTES = 15;

    /**
     * Extrae el fingerprint del HW actual. OS-specific; v1 soporta Windows completo.
     *
     * @return fingerprint (nunca null)
     */
    public static HwFingerprint extract() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final String mac = extractPrimaryMac();
        final String uuid;
        if (os.contains("win")) {
            uuid = extractWindowsSmbiosUuid();
        } else {
            uuid = null;
            LOGGER.warn("HW fingerprint: OS={} → solo MAC (UUID SMBIOS no extraído). Spoof más fácil.",
                    os);
        }
        return new HwFingerprint(
                mac,
                uuid,
                System.getProperty("os.version", "unknown"),
                hostnameOrUnknown()
        );
    }

    // ===== Extracción por fuente =====

    /**
     * Primera NIC no-loopback, no-virtual, UP. Devuelve formato "AA:BB:CC:DD:EE:FF"
     * en mayúsculas. Si no hay ninguna, devuelve "00:00:00:00:00:00" (no aborta).
     */
    private static String extractPrimaryMac() {
        try {
            final Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) {
                return "00:00:00:00:00:00";
            }
            while (en.hasMoreElements()) {
                final NetworkInterface ni = en.nextElement();
                try {
                    if (ni.isLoopback() || ni.isVirtual() || !ni.isUp()) {
                        continue;
                    }
                    final byte[] hw = ni.getHardwareAddress();
                    if (hw == null || hw.length == 0) {
                        continue;
                    }
                    final StringBuilder sb = new StringBuilder(hw.length * 3);
                    for (int i = 0; i < hw.length; i++) {
                        if (i > 0) sb.append(':');
                        sb.append(String.format(Locale.ROOT, "%02X", hw[i] & 0xFF));
                    }
                    return sb.toString();
                } catch (SocketException e) {
                    // Esta NIC en particular falló; probamos la siguiente.
                    continue;
                }
            }
            return "00:00:00:00:00:00";
        } catch (SocketException e) {
            LOGGER.warn("No pude enumerar NetworkInterfaces: {}", e.getMessage());
            return "00:00:00:00:00:00";
        }
    }

    /**
     * SMBIOS UUID via PowerShell + CIM. Solo Windows.
     * Comando: {@code (Get-CimInstance Win32_ComputerSystemProduct).UUID}.
     *
     * @return UUID string o null si falla
     */
    private static String extractWindowsSmbiosUuid() {
        try {
            final ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-Command",
                    "(Get-CimInstance Win32_ComputerSystemProduct).UUID"
            );
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            String uuid = null;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        uuid = line;
                        break;
                    }
                }
            }
            final int exit = p.waitFor();
            if (exit != 0 || uuid == null || uuid.isBlank()) {
                LOGGER.warn("PowerShell CIM exit={}, uuid={} → fallback MAC-only", exit, uuid);
                return null;
            }
            return uuid;
        } catch (IOException | InterruptedException e) {
            LOGGER.warn("Error extrayendo SMBIOS UUID: {}", e.getMessage());
            return null;
        }
    }

    private static String hostnameOrUnknown() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "unknown";
        }
    }

    // ===== Derivación del Azorea ID =====

    /**
     * Cadena canónica de este fingerprint (mismo formato que v1).
     *
     * <p>Formato: {@code mac|uuid|os|hostname}.
     *
     * @return canonical string (nunca null)
     */
    public String canonical() {
        return (macAddress == null ? "" : macAddress)
                + "|" + (hardwareUuid == null ? "" : hardwareUuid)
                + "|" + (osVersion == null ? "" : osVersion)
                + "|" + (hostname == null ? "" : hostname);
    }

    /**
     * § F9: commit público del fingerprint (32 bytes, SHA-256 del canonical).
     *
     * <p>Entra como componente del azorea_id v2 y se publica en el announcement
     * para que el tracker pueda re-derivar el ID y verificar la firma.
     *
     * <p>Privacidad: es un hash — NO expone MAC/UUID/hostname. Es un pseudónimo
     * estable por máquina, misma naturaleza que el propio azorea_id.
     *
     * @return SHA-256(canonical) — 32 bytes
     */
    public byte[] hwCommit() {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(canonical().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en el JRE", e);
        }
    }

    /**
     * Calcula el Azorea ID derivado de este fingerprint.
     *
     * <p><b>@deprecated F9</b>: usado por el modelo v1 (ID = f(HW)). Reemplazado por
     * {@link AzoreaId#derive} (ID = f(claves, hw_commit)) — ver AGENTS.md § DA-8.
     * Se conserva solo para referencia/migración.
     *
     * <p>Formato: {@code AZ-XXXXXX-XXXXXX-XXXXXX-XXXXXX} (24 chars base32 después del prefijo,
     * 120 bits de entropía). Mismo formato visual que {@code invite_code}.
     *
     * <p>Algoritmo: SHA-256(canonical(fingerprint)) → primeros 15 bytes → base32.
     *
     * <p>Colisiones: ~10^36 IDs únicos antes de colisión (birthday paradox ~10^18).
     * Suficiente para una comunidad MC.
     *
     * @return Azorea ID (nunca null)
     */
    @Deprecated
    public String computeAzoreaId() {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(canonical().getBytes(StandardCharsets.UTF_8));
            final byte[] fifteen = new byte[FINGERPRINT_BYTES];
            System.arraycopy(hash, 0, fifteen, 0, FINGERPRINT_BYTES);
            return AzoreaBase32.encode(fifteen);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en el JRE", e);
        }
    }

    /** @deprecated F9 — ver {@link #computeAzoreaId()}. Formato final con prefijo (helper). */
    @Deprecated
    public String computeAzoreaIdFormatted() {
        // § F5.2 fix: inserta dashes cada 6 chars para matchear el formato canónico
        // de invite_code (AZ-XXXXXX-XXXXXX-XXXXXX-XXXXXX, ver TrackerProtocol).
        final String raw = computeAzoreaId(); // 24 chars sin dashes
        return "AZ-" + raw.substring(0, 6) + "-" + raw.substring(6, 12)
                + "-" + raw.substring(12, 18) + "-" + raw.substring(18, 24);
    }
}