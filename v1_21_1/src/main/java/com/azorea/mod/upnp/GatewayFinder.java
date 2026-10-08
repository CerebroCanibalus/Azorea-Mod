/*
 * Copyright (C) 2015 Federico Dossena (adolfintel.com).
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301  USA
 *
 * ---------------------------------------------------------------------------
 * MODIFICADO POR AZOREA (2026-09-30) — § D0 "automatizar el port-forward"
 *
 * El original solo enviaba M-SEARCH a la dirección MULTICAST de SSDP con 3
 * tipos de servicio (:1). Con eso, en la red real del General el scan daba
 * "timeout — ¿router no soporta UPnP?" pese a tener gateway detectado.
 *
 * Tres cambios (reversibles, sin tocar el resto del contrato):
 *
 *  1) TIPOS: se añade `ssdp:all` (pregunta por TODO, no puede fallar por
 *     tipo) y las variantes `:2` (IGD:2 / WANIPConnection:2 / WANPPPConnection:2)
 *     que es lo que anuncian los routers modernos. El original solo miraba `:1`.
 *
 *  2) DESTINO MULTICAST: el multicast 239.255.255.250 se enruta mal en Windows
 *     cuando hay varios adaptadores (esta máquina tiene Realtek + Hyper-V
 *     Virtual Ethernet) ⇒ el paquete sale por la interfaz equivocada o se cae.
 *
 *  3) DOS DESTINOS EXTRA, ambos sin multicast:
 *       · BROADCAST DIRIGIDO de subred (192.168.1.255:1900) — llega aunque el
 *         multicast falle. Puro Java v/ InterfaceAddress.getBroadcast().
 *       · UNICAST DIRECTO al gateway por defecto (192.168.1.1:1900) — la vía
 *         más fiable de todas. Se resuelve en un hilo aparte porque
 *         detectDefaultGateway() lanza `route print -4` y runScan() puede
 *         invocarse desde el render thread (rescan() en pantalla).
 *
 * Si el router tiene UPnP desactivado en su panel, NADA de esto funciona —
 * eso no es automatizable desde un mod (sin credenciales del router), y
 * sustituirlo por un túnel de terceros sería infra central = DA-10 prohíbe.
 * ---------------------------------------------------------------------------
 */
package com.azorea.mod.upnp;

import com.azorea.mod.v1211.client.AzoreaPortForwardGuide;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 *
 * @author Federico
 */
public class GatewayFinder {

    private static final InetAddress SSDP_MULTICAST;
    private static final String[] SEARCH_MESSAGES;

    static {
        InetAddress mcast;
        try {
            mcast = InetAddress.getByName("239.255.255.250");
        } catch (final UnknownHostException e) {
            mcast = null;   // imposible en la práctica; los listeners lo ignoran
        }
        SSDP_MULTICAST = mcast;

        LinkedList<String> m = new LinkedList<>();
        // § (1) ssdp:all PRIMERO: es la búsqueda que no puede fallar por tipo.
        // Después los tipos concretos, por si algún router filtra ssdp:all.
        for (String type : new String[]{
                "ssdp:all",
                "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
                "urn:schemas-upnp-org:service:WANIPConnection:1",
                "urn:schemas-upnp-org:service:WANIPConnection:2",
                "urn:schemas-upnp-org:service:WANPPPConnection:1",
                "urn:schemas-upnp-org:service:WANPPPConnection:2"}) {
            // HOST siempre es la dirección de multicast SSDP: es lo que exige la
            // spec (RFC 8151 / UPnP DA) incluso cuando el datagrama va en unicast.
            m.add("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nST: " + type + "\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n\r\n");
        }
        SEARCH_MESSAGES = m.toArray(new String[]{});
    }

    private class GatewayListener implements Runnable {

        private final InetAddress bindIp;
        private final InetAddress target;
        private final String[] reqs;

        /** Compat con el original: multicast, una petición. */
        public GatewayListener(InetAddress ip, InetAddress target, String req) {
            this(ip, target, new String[]{req});
        }

        /**
         * @param ip     IP local de la que sale el datagrama (el socket se ata a ella)
         * @param target destino: multicast SSDP, broadcast de subred o el gateway
         * @param reqs   todos los M-SEARCH a enviar desde ESTE socket
         */
        public GatewayListener(InetAddress ip, InetAddress target, String[] reqs) {
            this.bindIp = ip;
            this.target = target;
            this.reqs = reqs;
        }

        @Override
        public void run() {
            boolean foundgw=false;
            Gateway gw=null;
            // § AZOREA: el original tragaba TODO ("catch (Throwable ignored)"),
            // así que cuando el LOCATION anunciado tenía el HTTP muerto, la
            // excepción "Unsupported Gateway" desaparecía y afuera solo se veía
            // "scan timeout — ¿router no soporta UPnP?" — un diagnóstico FALSO.
            // Guardamos el motivo real para poder decir la verdad.
            String problem = null;
            try {
                try (DatagramSocket s = new DatagramSocket(new InetSocketAddress(bindIp, 0))) {
                    for (final String r : reqs) {
                        byte[] req = r.getBytes(StandardCharsets.UTF_8);
                        s.send(new DatagramPacket(req, req.length, new InetSocketAddress(target, 1900)));
                    }
                    s.setSoTimeout(3000);
                    for (; ; ) {
                        try {
                            DatagramPacket recv = new DatagramPacket(new byte[1536], 1536);
                            s.receive(recv);
                            ssdpAnswered = true;   // § AZOREA: el router SÍ habla SSDP
                            gw = new Gateway(recv.getData(), bindIp, recv.getAddress());
                            String extIp = gw.getExternalIP();
                            if ((extIp != null) && (!extIp.equalsIgnoreCase(
                                "0.0.0.0"))) { //Exclude gateways without an external IP
                                onFound.accept(gw);
                                foundgw = true;
                            }
                        } catch (SocketTimeoutException t) {
                            break;
                        } catch (Throwable t) {
                            // El SSDP contestó pero no se pudo montar el gateway.
                            problem = t.getMessage();
                        }
                    }
                }
            } catch (Throwable t) {
                problem = t.getMessage();
            }
            if (problem != null && problemCallback != null) {
                problemCallback.accept(problem);
            }
            if( (!foundgw) && (gw!=null)){ //Pick the last GW if none have an external IP - internet not up yet??
                onFound.accept(gw);
            }
        }
    }

    private final LinkedList<Thread> listeners = new LinkedList<>();
    private final Consumer<Gateway> onFound;
    /**
     * § AZOREA: motivo por el que un SSDP que SÍ contestó no acabó en gateway.
     * Permite distinguir "router no responde" de "router anuncia pero el control
     * está caído" — que es un problema de firmware, no de soporte.
     */
    private volatile Consumer<String> problemCallback;
    /** § AZOREA: ¿algún M-SEARCH contestó con 200? Separa "sin SSDP" de "SSDP ok, control caído". */
    private volatile boolean ssdpAnswered;

    /** ¿El router contestó al menos un SSDP? */
    public boolean ssdpAnswered() {
        return ssdpAnswered;
    }

    public GatewayFinder(Consumer<Gateway> onFound) {
        this(onFound, null);
    }

    public GatewayFinder(Consumer<Gateway> onFound, Consumer<String> onProblem) {
        this.onFound = onFound;
        this.problemCallback = onProblem;

        // § (2) multicast — el camino original, sigue siendo el estándar.
        // § (3a) broadcast dirigido de subred — puro Java, sin procesos.
        for (InetAddress ip : getLocalIPs()) {
            startListener(ip, SSDP_MULTICAST);
            for (final InetAddress bcast : getBroadcasts(ip)) {
                startListener(ip, bcast);
            }
        }

        // § (3b) unicast al gateway por defecto. Se resuelve FUERA del hilo
        // llamante: detectDefaultGateway() lanza un proceso y runScan() puede
        // venir del render thread (rescan() al abrir la pantalla de host).
        Thread.ofVirtual().name("UPnP Gateway Resolver").start(() -> {
            final String gwIp = AzoreaPortForwardGuide.detectDefaultGateway();
            if (gwIp == null || gwIp.isBlank()) return;
            InetAddress gw;
            try {
                gw = InetAddress.getByName(gwIp);
            } catch (final UnknownHostException e) {
                return;
            }
            if (gw.isAnyLocalAddress() || gw.isLoopbackAddress() || gw.isMulticastAddress()) return;
            for (InetAddress ip : getLocalIPs()) {
                startListener(ip, gw);
            }
        });
    }

    /** Crea un listener virtual que pregunta a {@code target} por todos los tipos. */
    private void startListener(final InetAddress bindIp, final InetAddress target) {
        if (target == null) return;
        GatewayListener l = new GatewayListener(bindIp, target, SEARCH_MESSAGES);
        final Thread thread = Thread.ofVirtual()
            .name("UPnP Gateway Finder " + bindIp + "->" + target)
            .start(l);
        listeners.add(thread);
    }

    /**
     * Broadcast de subred de la IP local dada (p. ej. 192.168.1.50 → 192.168.1.255).
     *
     * <p>Puro Java: InterfaceAddress.getBroadcast() ya nos da la dirección, sin
     * procesos ni parsing. Vacío si la interfaz es point-to-point (túnel/VPN).
     */
    private static Set<InetAddress> getBroadcasts(final InetAddress localIp) {
        final Set<InetAddress> ret = new HashSet<>();
        if (localIp == null) return ret;
        try {
            final Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                final NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                for (final InterfaceAddress ia : iface.getInterfaceAddresses()) {
                    if (!localIp.equals(ia.getAddress())) continue;
                    final InetAddress bcast = ia.getBroadcast();
                    if (bcast != null) ret.add(bcast);
                }
            }
        } catch (final Throwable ignored) {
        }
        return ret;
    }

    public boolean isSearching() {
        for (Thread l : listeners) {
            if (l.isAlive()) {
                return true;
            }
        }
        return false;
    }

    private static InetAddress[] getLocalIPs() {
        Set<InetAddress> ret = new HashSet<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs != null && addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address) ret.add(a);   // SSDP/UPnP es IPv4
                }
            }
        } catch (Throwable ignored) {
        }
        return ret.toArray(new InetAddress[0]);
    }
}
