// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import com.azorea.mod.v1211.AzoreaNetLog;
import com.azorea.mod.v1211.AzoreaNetLog.Category;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Resuelve el descriptor UPnP cuando el {@code LOCATION} anunciado por SSDP no
 * funciona — y lo descarga <b>siempre con timeouts</b>.
 *
 * <p>§ Problema real (diagnosticado 2026-09-30 en la red del General): el gateway
 * anunciaba {@code http://192.168.1.1:49652/49652gatedesc.xml} y ese puerto
 * estaba <b>muerto</b>, mientras el daemon libupnp escuchaba en <b>37443</b>.
 * SSDP (hilo de descubrimiento) vivo, HTTP (hilo de control) en otro puerto.
 * Firmware del router con el puerto desincronizado.
 *
 * <p>§ Por qué esto importa tanto: el código original hacía
 * {@code DocumentBuilder.parse(location)} <b>sin ningún timeout</b> ⇒ con un
 * router en ese estado el hilo se quedaba <b>colgado para siempre</b>,
 * {@code onFound} nunca se disparaba, y el watchdog de 5 s concluía
 * <i>"¿router no soporta UPnP?"</i> — un diagnóstico falso <b>y</b> una fuga
 * de hilos. Aquí: fetch con timeout ⇒ o funciona, o se intenta el fallback,
 * o se devuelve null y se dice la verdad.
 *
 * <p>§ Estrategia del fallback (solo si el LOCATION anunciado falla):
 * <ol>
 *   <li>Barrer los puertos TCP del gateway buscando al daemon por su cabecera
 *       {@code SERVER: … UPnP/1.0 …} (aparece <b>también en los 404</b>, así
 *       que identifica al daemon aunque no sirva la ruta que buscamos).</li>
 *   <li>En cada puerto candidato, probar un abanico de rutas de descriptor.</li>
 *   <li>Si ninguna da XML ⇒ el control UPnP del router está caído de verdad y
 *       se devuelve null (sin inventar, sin reintentar para siempre).</li>
 * </ol>
 *
 * <p>§ Nota: el barrido puede tardar unos segundos y dispara bastantes SYN
 * contra el router. Por eso <b>solo</b> corre cuando el LOCATION ya falló —
 * nunca en el camino feliz.
 */
public final class UpnpEndpointResolver {

    /** Timeout de conexión: en LAN un puerto abierto contesta en <10 ms. */
    private static final int CONNECT_MS = 900;
    /** Timeout de lectura del descriptor. */
    private static final int READ_MS = 2500;

    /** Barrido del gateway (solo en el camino de fallo). */
    private static final int SCAN_START = 1024;
    private static final int SCAN_TIMEOUT_MS = 70;
    private static final int SCAN_THREADS = 600;

    private UpnpEndpointResolver() {
    }

    /**
     * Descarga el descriptor del gateway.
     *
     * @param advertisedLocation el {@code LOCATION} que dio SSDP (puede estar roto)
     * @param gatewayIp          IP del gateway (para el barrido de respaldo)
     * @return los bytes del descriptor XML, o <b>null</b> si no hay control UPnP
     */
    public static byte[] fetchDescriptor(final String advertisedLocation,
                                         final InetAddress gatewayIp) {
        if (advertisedLocation == null || advertisedLocation.isBlank()) return null;

        // 1) El camino feliz: exactamente lo que anuncia el router.
        final byte[] direct = fetch(advertisedLocation, CONNECT_MS, READ_MS);
        if (isXml(direct)) {
            AzoreaNetLog.debug(Category.UPnP, "descriptor OK en el LOCATION anunciado: " + advertisedLocation);
            return direct;
        }

        // 2) Fallback: el LOCATION miente/murió → buscar el daemon de verdad.
        AzoreaNetLog.info(Category.UPnP, "el LOCATION anunciado no responde ("
                + advertisedLocation + ") → buscando el daemon UPnP en los puertos del gateway");

        for (final int port : findUpnpHttpPorts(gatewayIp)) {
            for (final String path : candidatePaths(advertisedLocation, port)) {
                final String url = "http://" + gatewayIp.getHostAddress() + ":" + port + path;
                final byte[] xml = fetch(url, CONNECT_MS, READ_MS);
                if (isXml(xml)) {
                    AzoreaNetLog.milestone(Category.UPnP, "descriptor-recuperado",
                            gatewayIp.getHostAddress() + ":" + port + path);
                    return xml;
                }
            }
        }
        AzoreaNetLog.failure(Category.UPnP, "control-upnp-caido",
                gatewayIp == null ? "?" : gatewayIp.getHostAddress(),
                "el LOCATION anunciado no responde y ningún puerto del gateway sirve el descriptor");
        return null;
    }

    /** Parsea el descriptor con entidades externas deshabilitadas (XXE). */
    public static Document parse(final byte[] xml) throws Exception {
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setExpandEntityReferences(false);
        f.setNamespaceAware(false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    /** ¿Contiene algo de descriptor UPnP (service/controlURL)? */
    public static boolean isXml(final byte[] data) {
        if (data == null || data.length < 20) return false;
        final String head = new String(data, 0, Math.min(data.length, 600), StandardCharsets.UTF_8);
        return head.contains("<")
                && (head.contains("service") || head.contains("Service")
                || head.contains("device") || head.contains("Device")
                || head.contains("<?xml"));
    }

    // ===== Descarga con timeout =====

    /**
     * GET con timeout real.
     *
     * <p>⚠ Este es el método que sustituye al {@code DocumentBuilder.parse(url)}
     * original, que <b>no tenía timeout</b> y se colgaba para siempre contra un
     * puerto muerto.
     *
     * @return cuerpo si HTTP 200, null en cualquier otro caso
     */
    public static byte[] fetch(final String url, final int connectMs, final int readMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(Math.max(100, connectMs));
            conn.setReadTimeout(Math.max(100, readMs));
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Connection", "close");
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = conn.getInputStream()) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 1_000_000) break;   // no tragarnos nada enorme
                }
                return out.toByteArray();
            }
        } catch (final Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ===== Barrido de respaldo =====

    /** Puertos del gateway cuyo HTTP responde con cabecera UPnP. */
    static List<Integer> findUpnpHttpPorts(final InetAddress gatewayIp) {
        final Set<Integer> hits = new LinkedHashSet<>();
        if (gatewayIp == null) return new ArrayList<>();

        // El anunciado primero: si está vivo no gastamos el barrido.
        // (Ya sabemos que no lo está — pero es gratis comprobarlo otra vez.)

        final ExecutorService pool = Executors.newFixedThreadPool(SCAN_THREADS, r -> {
            final Thread t = new Thread(r, "azorea-upnp-scan");
            t.setDaemon(true);
            return t;
        });
        try {
            final List<Future<?>> futures = new ArrayList<>();
            for (int p = SCAN_START; p <= 65535; p++) {
                final int port = p;
                if (!hits.isEmpty()) break;   // early exit en cuanto hay
                futures.add(pool.submit(() -> {
                    if (!hits.isEmpty()) return;
                    final String banner = upnpBanner(gatewayIp, port);
                    if (banner != null) {
                        hits.add(port);
                        AzoreaNetLog.info(Category.UPnP,
                                "daemon UPnP encontrado en :" + port + "  (" + banner + ")");
                    }
                }));
            }
            for (final Future<?> f : futures) {
                try { f.get(30, TimeUnit.SECONDS); } catch (final Exception ignored) {}
            }
        } finally {
            pool.shutdownNow();
        }
        return new ArrayList<>(hits);
    }

    /** Conecta y pregunta por "/"; devuelve la cabecera si es un daemon UPnP. */
    private static String upnpBanner(final InetAddress ip, final int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), SCAN_TIMEOUT_MS);
            s.setSoTimeout(SCAN_TIMEOUT_MS);
            s.getOutputStream().write(
                    "GET / HTTP/1.0\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            final byte[] b = new byte[512];
            final int n = s.getInputStream().read(b);
            if (n <= 0) return null;
            final String head = new String(b, 0, n, StandardCharsets.UTF_8);
            final String upper = head.toUpperCase();
            if (upper.contains("UPNP/1.0") || head.contains("Portable SDK for UPnP")) {
                return head.split("\r\n")[0].trim();
            }
            return null;
        } catch (final Exception e) {
            return null;
        }
    }

    /** Rutas de descriptor a probar en un puerto candidato. */
    static List<String> candidatePaths(final String advertisedLocation, final int port) {
        final Set<String> paths = new LinkedHashSet<>();
        if (advertisedLocation != null) {
            try {
                final String p = URI.create(advertisedLocation).getPath();
                if (p != null && !p.isBlank()) paths.add(p);
            } catch (final Exception ignored) {}
        }
        paths.add("/gatedesc.xml");
        paths.add("/rootDesc.xml");
        paths.add("/" + port + "gatedesc.xml");
        paths.add("/description.xml");
        paths.add("/desc.xml");
        paths.add("/upnp/IGD1.xml");
        return new ArrayList<>(paths);
    }
}
