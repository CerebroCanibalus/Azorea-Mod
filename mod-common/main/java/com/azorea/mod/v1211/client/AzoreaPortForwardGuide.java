// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Guía de port-forward con los valores concretos de ESTA máquina (§ D0.3 / DA-10).
 *
 * <p>§ Cuándo aplica: UPnP no abrió el puerto pero <b>no</b> hay CGNAT — ese es
 * exactamente el caso en el que el usuario <i>sí</i> puede reenviar a mano. Si
 * hay CGNAT, la guía se sustituye por un aviso honesto (un port-forward local
 * no llega a internet, así que instruir para ello sería mentir).
 *
 * <p>§ Por qué detectar la puerta de enlace: sin ella el usuario no sabe a qué
 * dirección entrar en el navegador. El UPnP solo la da si encontró gateway (que
 * es justo lo que falló aquí), así que caemos a la tabla de rutas del SO:
 * Windows {@code route print -4} · resto {@code ip route show default}.
 * ⊘ requiere elevación, ⊘ red — es una lectura local.
 *
 * <p>§ Threading: {@link #detectDefaultGateway()} lanza un proceso ⇒ llamarla
 * desde virtual thread, ⊘ desde el main thread de MC.
 */
public final class AzoreaPortForwardGuide {

    private AzoreaPortForwardGuide() {
    }

    /**
     * Construye la guía multilínea para el chat de MC (el chat <i>sí</i> hace
     * wrap de líneas largas — a diferencia de los {@code StringWidget} de las
     * pantallas, que recortan a 200 px).
     *
     * @param gateway   IP del router (null/desconocida ⇒ se dice tal cual)
     * @param localIp   IP local de esta máquina hacia internet
     * @param port      puerto TCP del servidor MC
     * @param publicIp  IP pública vista desde internet (STUN), si se conoce
     */
    public static String buildText(final String gateway, final String localIp,
                                   final int port, final String publicIp) {
        final String gw = blankTo(gateway, "(no detectada — típicamente 192.168.1.1 o 192.168.0.1)");
        final String local = blankTo(localIp, "(no detectada)");
        final String pub = blankTo(publicIp, "(desconocida aún — vuelve a abrir esta pantalla)");

        return "§e§l[Azorea] §ePort-forward manual — TCP " + port + "§r\n"
                + "§7① Tu router: §f" + gw + "\n"
                + "§7② Entra en §fhttp://" + gw + " §7con las credenciales\n"
                + "   (suelen estar en una etiqueta debajo del router)\n"
                + "§7③ Busca la sección — nombres según marca:\n"
                + "   §fPort Forwarding§7 / §fVirtual Server§7 / §fNAT§7 / "
                + "§fSingle Port Forwarding§7 / §fApplications§7\n"
                + "§7④ Añade una regla nueva:\n"
                + "   · Servicio: §fAzorea / Minecraft\n"
                + "   · Protocolo: §fTCP §8(solo TCP — MC no usa UDP)\n"
                + "   · Puerto externo e interno: §f" + port + "\n"
                + "   · IP destino: §f" + local + "\n"
                + "§7⑤ Guarda. Si no cuela, reinicia el router.\n"
                + "\n"
                + "§aTu IP pública: §f" + pub + "\n"
                + "§7Tu amigo puede conectar a §f" + pub + ":" + port
                + " §7— o mejor: pásale el invite de §fCopy Invite§7\n"
                + "   (él lo pega en Azorea y se conecta solo).\n"
                + "\n"
                + "§8Autocomprobación: si al entrar en tu router ves una IP\n"
                + "§8100.64.x.x o 10.x.x.x como \"WAN\", es CGNAT y §l§cno va a "
                + "funcionar§r§8 —\n"
                + "§8pídele a tu ISP una IP pública. Guía → §fPort-forward help§8. "
                + "§r";
    }

    /**
     * Mejor esfuerzo: IP de la puerta de enlace por defecto.
     *
     * @return la IP, o null si no se pudo determinar (nunca lanza)
     */
    public static String detectDefaultGateway() {
        Process process = null;
        try {
            final boolean windows = System.getProperty("os.name", "")
                    .toLowerCase(Locale.ROOT).contains("win");
            final ProcessBuilder pb = windows
                    ? new ProcessBuilder("route", "print", "-4")
                    : new ProcessBuilder("ip", "route", "show", "default");
            process = pb.redirectErrorStream(true).start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String gateway = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    final String[] t = line.trim().split("\\s+");
                    if (windows) {
                        // "0.0.0.0  0.0.0.0  <gw>  <iface>  <metric>"
                        if (gateway == null && t.length >= 4
                                && "0.0.0.0".equals(t[0]) && "0.0.0.0".equals(t[1])) {
                            gateway = t[2];
                        }
                    } else {
                        // "default via <gw> dev <iface> ..."
                        if (gateway == null && t.length >= 3
                                && "default".equals(t[0]) && "via".equals(t[1])) {
                            gateway = t[2];
                        }
                    }
                }
                return isPlausibleIp(gateway) ? gateway : null;
            }
        } catch (final Exception e) {
            return null;
        } finally {
            if (process != null) process.destroy();
        }
    }

    /**
     * Un gateway de verdad es una IP, y normalmente es privada — filtramos ruido
     * por si la tabla de rutas trae algo raro (p. ej. "On-link").
     */
    private static boolean isPlausibleIp(final String s) {
        if (s == null || s.isBlank()) return false;
        if (s.equalsIgnoreCase("on-link") || s.equalsIgnoreCase("onlink")) return false;
        final String[] o = s.split("\\.");
        if (o.length != 4) return false;
        for (final String part : o) {
            try {
                final int v = Integer.parseInt(part.trim());
                if (v < 0 || v > 255) return false;
            } catch (final NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static String blankTo(final String s, final String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }
}
