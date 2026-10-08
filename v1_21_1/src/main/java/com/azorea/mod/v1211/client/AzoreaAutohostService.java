// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import com.azorea.mod.tracker.AzoreaHostService;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.players.ServerOpList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Servicio de autohost Azorea (F3.4).
 *
 * Implementación actual: usa {@link IntegratedServer#publishServer(GameType, boolean, int)}
 * para abrir el server singleplayer del cliente a LAN.
 *
 * § Limitaciones (vanilla API):
 * - Requiere que el usuario esté en un singleplayer world (no funciona desde menú principal).
 * - Una vez publicado, no se puede "despublicar" sin cerrar el mundo.
 *   "stop" simplemente borra el anuncio del tracker; el puerto queda abierto hasta
 *   que el user cierre el SP world.
 * - {@code LanServerPinger} también se inicia — broadcast LAN automático (vanilla behavior).
 *
 * § Decisión F3.4 vs dedicated server: usar {@code IntegratedServer} evita tener que
 * inicializar todo el {@code LevelStorageAccess}/{@code PackRepository}/{@code WorldStem}
 * que requiere {@code DedicatedServer}. Trade-off: solo funciona con un SP world activo.
 * Migrar a {@code DedicatedServer} cuando se necesite hosting desde menú principal.
 *
 * § Seguridad: bind solo a interfaces seguras. Default {@code 0.0.0.0} abre LAN pero
 * NO Internet (router NAT bloquea entrante). Para exposición pública, configurar port
 * forwarding o usar relay (F4+).
 *
 * § Cliente-only: usa {@link Minecraft#getInstance()}. ModDevGradle strip del server
 * build automáticamente.
 */
public final class AzoreaAutohostService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaAutohostService.class);

    private final AtomicReference<HostHandle> currentHandle = new AtomicReference<>();

    /** Puerto por defecto vanilla (25565). */
    public static final int DEFAULT_PORT = 25565;

    /**
     * Intenta iniciar el autohost usando el IntegratedServer del cliente.
     *
     * @param port puerto TCP (1-65535). 25565 = default Minecraft.
     * @param allowCheats si true, permite comandos (op permissions para todos).
     * @return handle si el server está activo y el puerto se abrió, vacío en caso contrario.
     */
    public Optional<HostHandle> startAutohost(final int port, final boolean allowCheats) {
        return startAutohost(port, allowCheats, "127.0.0.1");
    }

    /**
     * Overload con bind host explícito (recomendado).
     * § F4.4 fix: usar IP auto-detectada para que el HostHandle muestre la dirección
     * correcta a usuarios en LAN (en lugar de hardcodear 127.0.0.1).
     */
    public Optional<HostHandle> startAutohost(final int port, final boolean allowCheats, final String bindHost) {
        // § SURVIVAL = histórico; el overload con GameType lo decide ahora la UI d/ hosteo.
        return startAutohost(port, allowCheats, bindHost, GameType.SURVIVAL);
    }

    /**
     * Overload c/ modo de juego para los que se unen (DA-rediseño de pantalla d/ hosteo).
     *
     * <p>§ <b>Qué hace exactamente</b>: {@code publishServer(gameMode, …} sólo fija
     * {@code publishedGameType} — es el modo q/ <b>reciben los que se unen</b> (el análogo
     * al picker d/ «Abrir a LAN» d/ vanilla). <b>⊥ toca el modo del host</b>: su partida
     * sigue como estaba. Verificado en fuentes d/ {@code IntegratedServer.publishServer}.
     *
     * @param gameType modo q/ recibirán los jugadores q/ se unan
     */
    public Optional<HostHandle> startAutohost(final int port, final boolean allowCheats,
                                              final String bindHost, final GameType gameType) {
        return startAutohost(port, toMode(allowCheats), bindHost, gameType);
    }

    private static AzoreaHostPermissions.Mode toMode(final boolean allowCheats) {
        return allowCheats ? AzoreaHostPermissions.Mode.ALL
                : AzoreaHostPermissions.Mode.HOST_ONLY;
    }

    /**
     * Overload canónico — los <b>3</b> estados d/ Trampas (nada / sólo host / todos).
     * Ver {@link AzoreaHostPermissions} pa/ por qué hace falta tocar la op-list.
     */
    public Optional<HostHandle> startAutohost(final int port,
                                              final AzoreaHostPermissions.Mode mode,
                                              final String bindHost, final GameType gameType) {
        if (port < 1 || port > 65535) {
            LOGGER.error("Puerto fuera de rango: {}", port);
            return Optional.empty();
        }
        if (currentHandle.get() != null) {
            LOGGER.warn("Ya hay un autohost activo. Llama stopAutohost() primero.");
            return Optional.empty();
        }

        final Minecraft mc = Minecraft.getInstance();
        final IntegratedServer integrated = mc.getSingleplayerServer();
        if (integrated == null) {
            LOGGER.warn("No hay IntegratedServer activo. El usuario debe abrir un singleplayer world primero.");
            return Optional.empty();
        }
        if (integrated.isPublished()) {
            LOGGER.warn("IntegratedServer ya está publicado en puerto {}. Cierra el SP world primero.",
                    integrated.getPort());
            return Optional.empty();
        }
        if (mc.player == null) {
            LOGGER.warn("No hay player activo; no se puede iniciar autohost.");
            return Optional.empty();
        }

        // § § Trampas (3 estados) — ANTES d/ publishServer, a propósito: éste hace
        //   `setPermissionLevel(getProfilePermissions(...))`, o sea que REFLEJA lo q/
        //   fijemos en la op-list ⇒ si lo hiciéramos después, el host arrancaría con el
        //   nivel viejo hasta el siguiente recálculo.
        final AzoreaHostPermissions.Decision decision = AzoreaHostPermissions.resolve(mode);
        applyHostOpLevel(integrated, mc.player.getGameProfile(), decision.hostOpLevel());

        final boolean allowCheats = decision.allowCommandsForAllPlayers();
        final boolean ok;
        try {
            ok = integrated.publishServer(gameType == null ? GameType.SURVIVAL : gameType,
                    allowCheats, port);
        } catch (Exception e) {
            LOGGER.error("publishServer lanzó excepción: {}", e.getMessage(), e);
            return Optional.empty();
        }
        if (!ok) {
            LOGGER.error("publishServer devolvió false (puerto {} ocupado?)", port);
            return Optional.empty();
        }

        final String host = (bindHost == null || bindHost.isBlank()) ? "127.0.0.1" : bindHost;
        final HostHandle handle = new HostHandle(
                host + ":" + port,
                port,
                allowCheats,
                mc.player.getGameProfile().getName(),
                integrated.getWorldData().getLevelName());
        currentHandle.set(handle);
        LOGGER.info("Autohost iniciado: puerto={}, bind={}, allowCheats={}, host={}, world={}",
                port, handle.bindAddress(), allowCheats, handle.hostName(), handle.worldName());
        return Optional.of(handle);
    }

    /**
     * "Detiene" el autohost.
     *
     * § Limitación: NO cierra el puerto TCP. Vanilla no expone "stop publishing" sin
     * cerrar el SP world. Lo que SÍ hacemos: borrar el handle interno.
     * El user verá el juego como "no anunciable" hasta cerrar/abrir SP world.
     */
    public boolean stopAutohost() {
        final HostHandle handle = currentHandle.getAndSet(null);
        if (handle == null) {
            return false;
        }
        // § Restaurar la op-list del host: si la sesión puso «Trampas: nadie» (nivel 0),
        //   sin esto le quedaría el 0 al volver a su singleplayer y perdería comandos.
        restoreHostOpLevel(Minecraft.getInstance().getSingleplayerServer());
        LOGGER.info("Autohost 'detenido' (puerto TCP sigue abierto hasta cerrar SP world): {}",
                handle.bindAddress());
        return true;
    }

    // ===== § Trampas (3 estados) — op-list del host =====

    /** Estado previo d/ la op-list d/ l'host, p/ restaurarlo al parar. */
    private GameProfile savedHostProfile;
    private boolean savedHadEntry;
    private int savedLevel;

    /**
     * Fija el nivel d/ l'host en la op-list <b>antes</b> d/ {@code publishServer}.
     *
     * <p>§ <b>Por qué hace falta</b>: el host sólo es controlable por ahí — ver el javadoc
     * d/ {@link AzoreaHostPermissions}. Sin esto, «Trampas: nadie» no puede quitarle los
     * comandos a un dueño d/ mundo creado c/ cheats.
     *
     * <p>Sólo se guarda el estado previo la <b>1.ª</b> vez — si se volviera a llamar en la
     * misma sesión, lo q/ habría q/ restaurar es el valor real d/ partida, ⊘ el q/
     * acabamos d/ escribir.
     */
    private void applyHostOpLevel(final IntegratedServer server, final GameProfile host,
                                  final int level) {
        if (server == null || host == null) {
            return;
        }
        final ServerOpList ops = server.getPlayerList().getOps();
        try {
            if (savedHostProfile == null) {
                final ServerOpListEntry previous = ops.get(host);
                savedHostProfile = host;
                savedHadEntry = previous != null;
                savedLevel = previous != null ? previous.getLevel() : 0;
            }
            ops.add(new ServerOpListEntry(host, level, false));
            LOGGER.info("Trampas: op-list d/ l'host puesta a nivel {} (previo: {})",
                    level, savedHadEntry ? savedLevel : "sin entrada");
        } catch (final RuntimeException e) {
            LOGGER.warn("Trampas: no pude fijar la op-list d/ l'host: {}", e.toString());
        }
    }

    /**
     * § Best-effort: restaura la op-list d/ l'host si una sesión d/ hosteo la alteró.
     *
     * <p>Idempotente — si ya se restauró (o nunca hubo cambio) no hace nada. Se llama
     * también al <b>parar el server</b>, p/ el caso d/ q/ el host cierre el mundo
     * <i>sin</i> haber pulsado «Stop» (ver {@code AzoreaClient}).
     *
     * @param server el server q/ se está parando; null ⇒ no hay nada q/ restaurar
     */
    public void restoreHostPermissions(final net.minecraft.server.MinecraftServer server) {
        restoreHostOpLevel(server);
    }

    /** Devuelve la op-list d/ l'host a como estaba antes d/ hostear. */
    private void restoreHostOpLevel(final net.minecraft.server.MinecraftServer server) {
        final GameProfile host = savedHostProfile;
        savedHostProfile = null;
        if (host == null) {
            return;
        }
        if (server == null) {
            // § El server ya no existe ⇒ nada q/ restaurar (la partida se fue con su
            //   estado; ops.json habrá guardado lo último q/ hubo).
            return;
        }
        final ServerOpList ops = server.getPlayerList().getOps();
        try {
            if (savedHadEntry) {
                ops.add(new ServerOpListEntry(host, savedLevel, false));
            } else {
                ops.remove(host);
            }
            ops.save();
            LOGGER.info("Trampas: op-list d/ l'host restaurada ({})",
                    savedHadEntry ? "nivel " + savedLevel : "sin entrada");
        } catch (final IOException | RuntimeException e) {
            LOGGER.warn("Trampas: no pude restaurar la op-list: {}", e.toString());
        }
    }

    public Optional<HostHandle> currentHandle() {
        return Optional.ofNullable(currentHandle.get());
    }

    /** Handle inmutable del autohost activo. */
    public record HostHandle(
            String bindAddress,
            int port,
            boolean allowCheats,
            String hostName,
            String worldName
    ) {
    }

    /** Convierte un AzoreaHostService.HostConfig en un AutohostConfig. */
    public static AutohostConfig fromHostConfig(final AzoreaHostService.HostConfig hostConfig) {
        final int port = parsePortOrDefault(Objects.requireNonNullElse(hostConfig.bindAddress(), ""), DEFAULT_PORT);
        return new AutohostConfig(port, false /* allowCheats default */);
    }

    public record AutohostConfig(int port, boolean allowCheats) {
    }

    private static int parsePortOrDefault(final String bindAddress, final int defaultPort) {
        if (bindAddress == null || bindAddress.isBlank()) {
            return defaultPort;
        }
        final int colon = bindAddress.lastIndexOf(':');
        if (colon < 0 || colon == bindAddress.length() - 1) {
            return defaultPort;
        }
        try {
            final int port = Integer.parseInt(bindAddress.substring(colon + 1));
            return (port >= 1 && port <= 65535) ? port : defaultPort;
        } catch (NumberFormatException e) {
            return defaultPort;
        }
    }
}
