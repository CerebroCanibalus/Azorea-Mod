// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * Estado del firewall de Windows y apertura de puerto (§ D0 / DA-10).
 *
 * <p>§ Por qué existe: el firewall de Windows bloquea la entrada <b>por defecto</b>
 * en los 3 perfiles. Si no hay regla para Java, el host publica un endpoint que
 * nadie puede alcanzar y nadie sabe por qué. Preferimos decirlo antes.
 *
 * <p>§ Verificado en la máquina del General (2026-09-27): sus 3 perfiles están en
 * BLOCK por defecto, pero existen reglas Inbound-Allow para {@code javaw.exe} y
 * {@code java.exe} con profile <i>Private, Public</i> y <b>sin AddressFamily</b>
 * ⇒ dual-stack, o sea que cubren TCP tanto por IPv4 como por IPv6. Su red es
 * {@code Private}. En ese caso la respuesta es ALLOWED y no hay nada que tocar.
 *
 * <p>§ El chequeo es una consulta PowerShell que dura ~70 ms
 * ({@code Get-NetFirewallApplicationFilter -Program '*javaw.exe'}); solo si no
 * aparece nada se hace el barrido lento de puertos (camino poco frecuente).
 * Por eso <b>no</b> se llama desde el main thread.
 *
 * <p>§ Windows Firewall no es el único muro: el router puede filtrar el inbound
 * de IPv6 él solo (no hay NAT que lo tape, es una regla explícita). Eso
 * <b>no es comprobable desde dentro</b>; quien lo decide es el sondeo del joiner.
 *
 * <p>§ Sólo Windows: en Linux/macOS el resultado es UNKNOWN y no se intenta nada
 * (iptables/nft/pf tienen sintaxis y privilegios distintos).
 */
public final class AzoreaFirewall {

    public enum Status {
        /** ∃ regla Inbound-Allow que cubre Java/puerto ⇒ la entrada no la corta el firewall. */
        ALLOWED,
        /** Sin regla ⇒ el firewall de Windows corta la entrada. */
        BLOCKED,
        /** SO no soportado o no se pudo determinar. ⊘ alarmar. */
        UNKNOWN
    }

    private static final Object LOCK = new Object();
    private static volatile Status cached;
    private static volatile long cachedAtMs;

    private AzoreaFirewall() {
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * ¿Deja Windows entrar la conexión en este puerto?
     *
     * <p>Memoriza el resultado 60 s: se consulta al empezar a hostear y al abrir
     * la ayuda, no en cada render.
     *
     * @param port puerto TCP del servidor MC (p. ej. 25565)
     * @return estado, o {@link Status#UNKNOWN} en SO no soportados
     */
    public static Status check(final int port) {
        if (!isWindows()) return Status.UNKNOWN;
        final Status memo = cached;
        if (memo != null && System.currentTimeMillis() - cachedAtMs < 60_000L) {
            return memo;
        }
        final Status result = runQuery(port);
        cached = result;
        cachedAtMs = System.currentTimeMillis();
        return result;
    }

    /** Olvida el memo (llamar tras abrir una regla, p/ re-consultar). */
    public static void invalidate() {
        cached = null;
        cachedAtMs = 0L;
    }

    /**
     * Intenta crear la regla Inbound-Allow <b>con elevación</b> (dispara UAC).
     *
     * <p>Solo se llama tras un clic explícito del usuario — nunca de forma
     * sorpresiva. Si el usuario rechaza el UAC o no es admin, devuelve false.
     *
     * @return true si la regla quedó creada
     */
    public static boolean openPort(final int port) {
        if (!isWindows()) return false;
        if (port < 1 || port > 65535) return false;
        try {
            // -EncodedCommand evita TODOS los problemas de escaping:
            // base64 de UTF-16LE, tal y como lo espera PowerShell.
            final String inner = String.join("; ",
                    "New-NetFirewallRule -DisplayName 'Azorea MC " + port + "'",
                    "-Direction Inbound -Protocol TCP -LocalPort " + port,
                    "-Action Allow -Profile Any -ErrorAction Stop",
                    "$null = 1");   // evita que salga nada raro por stdout
            final String encoded = Base64.getEncoder().encodeToString(
                    inner.getBytes(StandardCharsets.UTF_16LE));

            final String launcher = "Start-Process powershell -Verb RunAs -Wait "
                    + "-WindowStyle Hidden -ArgumentList "
                    + "'-NoProfile','-ExecutionPolicy','Bypass','-EncodedCommand','" + encoded + "'";
            final Process p = new ProcessBuilder(
                    "powershell", "-NoProfile", "-Command", launcher)
                    .redirectErrorStream(true)
                    .start();
            drain(p);
            p.waitFor();

            invalidate();
            return check(port) == Status.ALLOWED;
        } catch (final Exception e) {
            return false;
        }
    }

    /** Texto corto p/ el chat/UI según el estado. */
    public static String describe(final Status status, final int port) {
        return switch (status) {
            case ALLOWED -> "Firewall Windows: permitido (regla inbound d/ Java, dual-stack)";
            case BLOCKED -> "Firewall Windows BLOQUEA la entrada al puerto " + port
                    + " ⇒ nadie podrá conectarse ni con IPv6";
            case UNKNOWN -> "Firewall Windows: no se pudo determinar en este SO";
        };
    }

    // ===== Internos =====

    private static Status runQuery(final int port) {
        // 1) Rápido: ¿java/javaw tiene regla Inbound-Allow? (~70ms)
        // 2) Si no, barrido de puertos (camino raro, pero existe: puede haber
        //    una regla dedicada a 25565 sin regla de programa).
        final String script = String.join("; ",
                "$a = Get-NetFirewallApplicationFilter -Program '*javaw.exe','*java.exe' "
                        + "-ErrorAction SilentlyContinue | ForEach-Object { "
                        + "$_.AssociatedNetFirewallRule } | Where-Object { "
                        + "$_ -and $_.Direction -eq 'Inbound' -and $_.Action -eq 'Allow' "
                        + "-and $_.Enabled -eq 'True' };",
                "if ($a) { 'ALLOWED'; exit };",
                "$b = Get-NetFirewallRule -Enabled True -Direction Inbound -Action Allow "
                        + "-ErrorAction SilentlyContinue | ForEach-Object { "
                        + "$p = $_ | Get-NetFirewallPortFilter -ErrorAction SilentlyContinue; "
                        + "if ($p -and $p.Protocol -eq 'TCP') { "
                        + "$l = @($p.LocalPort) | ForEach-Object { \"$_\" }; "
                        + "if ($l -contains 'Any' -or $l -contains '" + port + "') { $_ } } };",
                "if ($b) { 'ALLOWED' } else { 'BLOCKED' }");

        final String out = runPowerShell(script, 8000);
        if (out == null) return Status.UNKNOWN;
        if (out.contains("ALLOWED")) return Status.ALLOWED;
        if (out.contains("BLOCKED")) return Status.BLOCKED;
        return Status.UNKNOWN;
    }

    /** Lanza PowerShell c/ el script dado y devuelve su stdout, o null. */
    private static String runPowerShell(final String script, final int timeoutMs) {
        try {
            final String encoded = Base64.getEncoder().encodeToString(
                    script.getBytes(StandardCharsets.UTF_16LE));
            final Process p = new ProcessBuilder("powershell", "-NoProfile",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                    .redirectErrorStream(true)
                    .start();
            final StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (sb.length() < 4096) sb.append(line).append('\n');
                }
            }
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return sb.toString();
        } catch (final Exception e) {
            return null;
        }
    }

    /** Consume el stdout para que el proceso elevado no se quede colgado. */
    private static void drain(final Process p) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            while (r.readLine() != null) {
                // descartar
            }
        } catch (final Exception ignored) {
        }
    }
}
