// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.tracker.lan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Auto-discovery LAN via UDP multicast (ver AGENTS.md § DA-7, F7.5).
 *
 * <p>§ Protocolo:
 * <pre>{@code
 * Packet format (UTF-8, una línea terminada en \n):
 *   AZ_HELLO|azoreaId|displayName|trackerUrl|bindAddress|gameIdOrEmpty|mcPort
 *
 * - azoreaId:     AZ-XXXXXX-XXXXXX-XXXXXX-XXXXXX
 * - displayName:  nombre visible
 * - trackerUrl:   http://192.168.x.x:8765 (URL del embedded tracker del peer)
 * - bindAddress:  192.168.x.x:25565 (puerto MC cuando hostea) o vacío si no hostea
 * - gameIdOrEmpty: UUID del game si hostea, vacío si no
 * - mcPort:       puerto del MC server (25565 por defecto) o 0 si no hostea
 * }</pre>
 *
 * <p>§ Multicast group: 239.255.42.42 (Site-local scope, no se filtra fuera d/ LAN).
 *
 * <p>§ Limitaciones v1:
 * <ul>
 *   <li>Solo funciona en LAN (mismo segmento de red).</li>
 *   <li>Si el router filtra multicast, no funciona (raro en redes domésticas).</li>
 *   <li>Sin autenticación (asumimos LAN trusted).</li>
 * </ul>
 */
public final class AzoreaLanDiscovery {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaLanDiscovery.class);

    /** Multicast group (Site-local scope, según RFC 2365). */
    public static final String MULTICAST_GROUP = "239.255.42.42";
    public static final int MULTICAST_PORT = 4446;

    private static final long HELLO_INTERVAL_SECONDS = 5;
    private static final long PEER_TIMEOUT_MS = 15_000L;  // peer "expira" si no se ve en 15s

    /** Peer descubierto en LAN. */
    public record AzoreaPeer(
            String azoreaId,
            String displayName,
            String trackerUrl,
            String bindAddress,   // host:port cuando hostea
            String gameId,
            long lastSeenMs) {

        public boolean isOnline() {
            return System.currentTimeMillis() - lastSeenMs < PEER_TIMEOUT_MS;
        }

        public boolean isHosting() {
            return bindAddress != null && !bindAddress.isBlank();
        }
    }

    /** Estado compartido. Singleton. */
    private static volatile AzoreaLanDiscovery instance;

    public static AzoreaLanDiscovery get() {
        return instance;
    }

    public static synchronized AzoreaLanDiscovery getOrCreate() {
        AzoreaLanDiscovery local = instance;
        if (local != null && local.running) return local;
        if (local != null) local.stop();
        local = new AzoreaLanDiscovery();
        if (local.start()) {
            instance = local;
            return local;
        }
        return null;
    }

    private final Map<String, AzoreaPeer> peers = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    // Contexto actual (null = no estoy hosteando).
    private volatile String myAzoreaId;
    private volatile String myDisplayName;
    private volatile String myTrackerUrl;
    private volatile String myBindAddress;   // host:port cuando hostea
    private volatile String myGameId;        // UUID del game cuando hostea

    private MulticastSocketWrapper socketWrapper;

    private AzoreaLanDiscovery() {
    }

    /** Inicia el broadcast + listener. Devuelve true si OK. */
    public boolean start() {
        if (running) return true;
        try {
            socketWrapper = MulticastSocketWrapper.create();
            if (socketWrapper == null) {
                LOGGER.warn("No pude crear multicast socket; LAN discovery desactivado");
                return false;
            }
            running = true;
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread t = new Thread(r, "azorea-lan-discovery");
                t.setDaemon(true);
                return t;
            });
            // Broadcast cada 5s.
            scheduler.scheduleAtFixedRate(this::broadcastHello,
                    0L, HELLO_INTERVAL_SECONDS, TimeUnit.SECONDS);
            // Sweeper cada 5s (purga peers expirados).
            scheduler.scheduleAtFixedRate(this::sweepPeers,
                    5L, 5L, TimeUnit.SECONDS);
            // Listener en thread separado.
            final Thread listener = new Thread(this::listenLoop, "azorea-lan-listener");
            listener.setDaemon(true);
            listener.start();
            LOGGER.info("Azorea LAN discovery activo en grupo {}:{}", MULTICAST_GROUP, MULTICAST_PORT);
            return true;
        } catch (final Exception e) {
            LOGGER.error("Error arrancando LAN discovery: {}", e.getMessage());
            return false;
        }
    }

    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (socketWrapper != null) {
            socketWrapper.close();
            socketWrapper = null;
        }
        peers.clear();
        LOGGER.info("Azorea LAN discovery detenido");
    }

    /** Identifica este mod ante otros peers. */
    public void setIdentity(final String azoreaId, final String displayName, final String trackerUrl) {
        this.myAzoreaId = azoreaId;
        this.myDisplayName = displayName;
        this.myTrackerUrl = trackerUrl;
    }

    /** Actualiza el estado de hosting. Llamar al empezar/terminar un host. */
    public void setHostingState(final String bindAddress, final String gameId) {
        this.myBindAddress = bindAddress;
        this.myGameId = gameId;
    }

    /**
     * § F9 FIX: limpia el estado de hosting.
     *
     * <p>Antes solo existía {@link #setHostingState}, nunca se limpiaba → al parar
     * de hostear el mod seguía radiodifundiendo {@code AZ_HELLO} con el bindAddress
     * y gameId viejos <b>para siempre</b>. Los peers veían {@code isHosting()==true}
     * con una dirección muerta y el joiner tomaba el camino LAN-directo contra un
     * puerto ya cerrado (y sin fallback), fallando en conexión rehusada.
     */
    public void clearHostingState() {
        this.myBindAddress = null;
        this.myGameId = null;
    }

    /** Devuelve los peers descubiertos. */
    public List<AzoreaPeer> getPeers() {
        return new ArrayList<>(peers.values());
    }

    /** Peer por Azorea ID. */
    public AzoreaPeer getPeer(final String azoreaId) {
        return peers.get(azoreaId);
    }

    private void broadcastHello() {
        if (myAzoreaId == null) return;
        try {
            final String packet = String.join("|",
                    "AZ_HELLO",
                    myAzoreaId,
                    myDisplayName == null ? "" : myDisplayName,
                    myTrackerUrl == null ? "" : myTrackerUrl,
                    myBindAddress == null ? "" : myBindAddress,
                    myGameId == null ? "" : myGameId) + "\n";
            final byte[] bytes = packet.getBytes(StandardCharsets.UTF_8);
            if (socketWrapper != null) {
                socketWrapper.send(bytes);
            }
        } catch (final Exception e) {
            LOGGER.debug("Error broadcasting HELLO: {}", e.getMessage());
        }
    }

    private void listenLoop() {
        final byte[] buf = new byte[1024];
        while (running) {
            try {
                final DatagramPacket packet = socketWrapper.receive(buf);
                if (packet == null) break;
                final String line = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                handlePacket(line);
            } catch (final Exception e) {
                if (running) {
                    LOGGER.debug("Error en listen loop: {}", e.getMessage());
                }
            }
        }
    }

    private void handlePacket(final String line) {
        if (!line.startsWith("AZ_HELLO|")) return;
        final String[] parts = line.split("\\|", -1);
        if (parts.length < 6) return;
        final String azoreaId = parts[1];
        if (azoreaId.equals(myAzoreaId)) return;  // ignorar mis propios paquetes
        if (!com.azorea.mod.tracker.TrackerProtocol.isValidAzoreaId(azoreaId)) return;
        final String displayName = parts[2];
        final String trackerUrl = parts[3];
        final String bindAddress = parts[4];
        final String gameId = parts[5];

        final AzoreaPeer peer = new AzoreaPeer(
                azoreaId, displayName, trackerUrl, bindAddress, gameId,
                System.currentTimeMillis());
        final AzoreaPeer old = peers.put(azoreaId, peer);
        if (old == null) {
            LOGGER.info("LAN peer descubierto: {} ({}) tracker={} bind={}",
                    displayName, azoreaId, trackerUrl,
                    bindAddress.isEmpty() ? "(no host)" : bindAddress);
        }
    }

    private void sweepPeers() {
        final long now = System.currentTimeMillis();
        int removed = 0;
        for (final var entry : peers.entrySet()) {
            if (now - entry.getValue().lastSeenMs() > PEER_TIMEOUT_MS) {
                peers.remove(entry.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.debug("Sweeper LAN: {} peers expirados purgados", removed);
        }
    }

    private static int safeParseInt(final String s) {
        return 0;  // unused, mcPort field removed
    }

    // ===== Socket wrapper que encapsula MulticastSocket =====

    private static final class MulticastSocketWrapper {
        private final MulticastSocket socket;
        private final InetAddress group;
        private final int port;

        static MulticastSocketWrapper create() {
            try {
                final MulticastSocket s = new MulticastSocket(MULTICAST_PORT);
                s.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                // § F8.x: permitir multicast en loopback (para tests 2-client en misma máquina).
                // Por defecto algunas JVMs/OSes filtran multicast al loopback.
                s.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true);
                // § Limitar TTL a 1 (LAN only — no queremos multicast saliendo a internet).
                try {
                    s.setTimeToLive(1);
                } catch (final IOException ignored) {
                }
                final InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
                // § F9b FIX: unirse en TODAS las interfaces (loopback + LAN).
                //
                // Antes: si existía loopback se unía SOLO a ella. En Windows ese
                // `getByInetAddress(127.0.0.1)` SIEMPRE devuelve una interfaz, así
                // que el join a la LAN jamás se hacía ⇒ el multicast de otros PCs
                // no llegaba NUNCA y nadie descubría a nadie. El join de loopback
                // era para los tests 2-client en misma máquina, pero SUSTITUYÓ al
                // de LAN en vez de sumarse a él.
                int joinedIfaces = 0;
                try {
                    final java.util.Enumeration<NetworkInterface> ifaces =
                            NetworkInterface.getNetworkInterfaces();
                    while (ifaces.hasMoreElements()) {
                        final NetworkInterface ni = ifaces.nextElement();
                        try {
                            if (!ni.isUp()) continue;
                            s.joinGroup(new java.net.InetSocketAddress(group, MULTICAST_PORT), ni);
                            joinedIfaces++;
                            if (ni.isLoopback()) {
                                LOGGER.debug("LAN discovery: unida a loopback (tests 2-client)");
                            } else {
                                LOGGER.info("LAN discovery: unida a multicast en {}", ni.getDisplayName());
                            }
                        } catch (final IOException e) {
                            // Interfaz que no acepta el join (down, sin multicast, etc.)
                            LOGGER.debug("LAN discovery: ⊘ unida a {}: {}",
                                    ni.getDisplayName(), e.getMessage());
                        }
                    }
                } catch (final IOException e) {
                    LOGGER.debug("LAN discovery: no pude enumerar interfaces: {}", e.getMessage());
                }
                if (joinedIfaces == 0) {
                    // Fallback: algunos OSes no soportan joinGroup(SocketAddress, NetworkInterface).
                    s.joinGroup(group);
                    LOGGER.info("LAN discovery: unida al grupo (interfaz por defecto)");
                }
                return new MulticastSocketWrapper(s, group, MULTICAST_PORT);
            } catch (final IOException e) {
                LOGGER.warn("No pude crear MulticastSocket: {}", e.getMessage());
                return null;
            }
        }

        private MulticastSocketWrapper(final MulticastSocket socket,
                                       final InetAddress group,
                                       final int port) {
            this.socket = socket;
            this.group = group;
            this.port = port;
        }

        void send(final byte[] bytes) throws IOException {
            final DatagramPacket packet = new DatagramPacket(bytes, bytes.length, group, port);
            socket.send(packet);
        }

        DatagramPacket receive(final byte[] buf) throws IOException {
            final DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);
            return packet;
        }

        void close() {
            try {
                socket.leaveGroup(group);
            } catch (final IOException ignored) {
            }
            socket.close();
        }
    }
}
