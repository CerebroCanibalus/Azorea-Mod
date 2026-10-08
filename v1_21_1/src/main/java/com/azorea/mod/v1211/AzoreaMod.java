// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211;

import com.azorea.mod.AzoreaConstants;
import com.azorea.mod.net.AzoreaNetwork;
import com.azorea.mod.net.IdentityChallengePayload;
import com.azorea.mod.net.IdentityProofPayload;
import com.azorea.mod.net.PingPayload;
import com.azorea.mod.net.PongPayload;
import com.azorea.mod.tracker.AzoreaServices;
import com.azorea.mod.v1211.access.AzoreaAccessGate;
import com.azorea.mod.v1211.access.AzoreaAccessState;
import com.azorea.mod.v1211.access.AzoreaAccessTask;
import com.azorea.mod.v1211.access.AzoreaWorldAccess;
import com.azorea.mod.v1211.tracker.embedded.AzoreaAutoTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Punto de entrada del mod Azorea para Minecraft 1.21.1 + NeoForge 21.1.x.
 *
 * § Reglas (AGENTS.md § Convenciones):
 * - Lifecycle/registros/payloads/datagen → mod bus.
 * - Gameplay/comandos → NeoForge.EVENT_BUS (game bus).
 * - Config events → mod bus (ModConfigEvent.Loading/Reloading).
 *
 * § F2.6 (DA-5 + DA-6 + config TOML):
 * - Config registrada en constructor.
 * - Servicios instanciados en {@link FMLCommonSetupEvent} (cuando config ya está cargada).
 * - Hot-reload vía {@link ModConfigEvent.Reloading}.
 */
@Mod(AzoreaConstants.MOD_ID)
public final class AzoreaMod {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaMod.class);

    @Nullable
    private static AzoreaMod INSTANCE;

    private AzoreaServices services;
    @Nullable
    private com.azorea.mod.v1211.client.friends.AzoreaFriendsService friendsService;
    @Nullable
    private com.azorea.mod.v1211.identity.AzoreaIdentityService identityService;
    @Nullable
    private com.azorea.mod.v1211.client.AzoreaAutohostService autohostService;
    @Nullable
    private com.azorea.mod.tracker.AzoreaInviteInbox inviteInbox;

    public AzoreaMod(final IEventBus modBus, final ModContainer container) {
        // § F3.0: expone instancia para acceso client-side (UI en paquete client/).
        INSTANCE = this;

        // § F2.6: registrar config (archivo → config/azorea.toml).
        container.registerConfig(ModConfig.Type.COMMON,
                AzoreaConfig.SPEC, "azorea.toml");

        // Mod bus: payloads, common setup, config reload, gate de identidad (F10/A2).
        modBus.addListener(this::registerPayloads);
        modBus.addListener(this::onRegisterConfigurationTasks);
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onConfigReload);

        // § § § 1.4.1/1.4.10: registrar el EntityType d/ debug (nametag fantasma).
        //   § 1.4.10: el comando /azorea nametag es público (no experimental)
        //   para q/ el user pueda testear el renderer cuando lo arregle. El
        //   EntityType SIEMPRE se registra — el flag experimental ahora es
        //   ui.nametag_overlay (controla el AzoreaNametagRenderer del nametag
        //   real, no este debug entity).
        com.azorea.mod.v1211.client.debug.AzoreaDebugNametagEntityType.ENTITY_TYPES.register(modBus);

        // Game bus: comandos (debug only, ver AGENTS.md § DA-6) + watchdog del gate
        // + ciclo de vida del modo de acceso (F10/A3).
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
    }

    /**
     * Acceso estático a la instancia del mod (cliente/UI).
     * Throws si se accede antes d/ que el mod cargue.
     */
    public static AzoreaMod get() {
        return java.util.Objects.requireNonNull(INSTANCE, "AzoreaMod no inicializado");
    }

    /**
     * Servicios instanciados. Puede ser null si la config no se ha cargado todavía
     * (ej. acceso muy temprano en tests).
     */
    public AzoreaServices services() {
        return services;
    }

    /**
     * Servicio de friend list (F4.5). Null hasta que se inicialice en client setup.
     * Llamar solo desde cliente; en server devuelve null.
     */
    @Nullable
    public com.azorea.mod.v1211.client.friends.AzoreaFriendsService friendsService() {
        return friendsService;
    }

    /** Setter para inicializar friendsService desde AzoreaClient.onClientSetup. */
    public void setFriendsService(final com.azorea.mod.v1211.client.friends.AzoreaFriendsService svc) {
        this.friendsService = svc;
    }

    /**
     * Servicio de identidad Azorea (F5.1). Null hasta que se inicialice en client setup.
     * @return identity service o null si no inicializado (server-side, o pre-init).
     */
    @Nullable
    public com.azorea.mod.v1211.identity.AzoreaIdentityService identityService() {
        return identityService;
    }

    /** Setter para inicializar identityService desde AzoreaClient.onClientSetup. */
    public void setIdentityService(final com.azorea.mod.v1211.identity.AzoreaIdentityService svc) {
        this.identityService = svc;
        // § F9 fix (bug latente): `services` se construye en FMLCommonSetupEvent con
        // identity=null, porque la identity es client-only y ese evento va ANTES de
        // FMLClientSetupEvent. Antes de F9 esto dejaba AzoreaInviteService a null
        // (invites inoperantes) hasta que alguien recargara la config; con F9 además
        // dejaría AzoreaHostService sin clave → TODOS los anuncios sin firmar →
        // el tracker F9 los rechaza → hosting no descubrible.
        // Reconstruir aquí garantiza que los servicios vean la identity real.
        if (services != null) {
            LOGGER.info("Identity disponible; reconstruyendo AzoreaServices...");
            rebuildServices();
        }
    }

    /**
     * Servicio singleton de autohost (F3.4 + F6.1). Cliente-only.
     * Accesible desde cualquier screen que quiera gestionar el puerto TCP.
     */
    @Nullable
    public com.azorea.mod.v1211.client.AzoreaAutohostService autohostService() {
        return autohostService;
    }

    /** Setter para inicializar autohostService desde AzoreaClient.onClientSetup. */
    public void setAutohostService(final com.azorea.mod.v1211.client.AzoreaAutohostService svc) {
        this.autohostService = svc;
    }

    /**
     * Inbox de invites pendientes (F7.2). Null hasta que se inicialice.
     */
    @Nullable
    public com.azorea.mod.tracker.AzoreaInviteInbox inviteInbox() {
        return inviteInbox;
    }

    /** Setter para inicializar inviteInbox desde AzoreaClient.onClientSetup. */
    public void setInviteInbox(final com.azorea.mod.tracker.AzoreaInviteInbox inbox) {
        this.inviteInbox = inbox;
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        // § Diseño SEAMLESS: arrancar el tracker embebido antes de los servicios.
        // Esto garantiza que el mod SIEMPRE tiene al menos 1 tracker (no modo no-op).
        final AzoreaAutoTracker autoTracker = AzoreaAutoTracker.getOrCreate();
        if (autoTracker != null) {
            LOGGER.info("Tracker embebido arrancado: {}", autoTracker.localUrl());
        } else {
            LOGGER.warn("Tracker embebido NO arrancó; modo degradado.");
        }

        // § F5.2b: identity puede no estar inicializada aún (FMLClientSetupEvent); pasamos null.
        // setIdentityService() reconstruye los servicios cuando la identity esté lista.
        rebuildServices();
    }

    /**
     * § F9: (re)construye {@link AzoreaServices} con la config + identity actuales.
     *
     * <p>Cerrado el anterior (si existía). Usado por commonSetup, config reload y
     * por {@link #setIdentityService} — este último es el que garantiza que el
     * host service tenga la clave de firma.
     */
    private void rebuildServices() {
        if (services != null) {
            services.shutdown();
        }
        final AzoreaAutoTracker autoTracker = AzoreaAutoTracker.getOrCreate();
        this.services = new AzoreaServices(
                effectiveTrackerUrls(autoTracker),
                identityService,
                Duration.ofSeconds(AzoreaConfig.getConnectTimeoutSeconds()),
                Duration.ofSeconds(AzoreaConfig.getRequestTimeoutSeconds()));
        LOGGER.info("AzoreaServices (re)construido: {} tracker(s) efectivo(s), identity={}.",
                services.hasTrackers() ? effectiveTrackerUrls(autoTracker).size() : 0,
                identityService != null ? "sí" : "no");
    }

    private void onConfigReload(final ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != AzoreaConfig.SPEC) {
            return;
        }
        LOGGER.info("Config Azorea recargada; reconstruyendo servicios...");
        rebuildServices();
    }

    /**
     * URLs d/ tracker efectivas — <b>público</b> p/ el punch y la discovery.
     *
     * <p>Config c/ URLs ⇒ esas ganan; vacío ⇒ el tracker embebido. Éste era el hueco q/
     * faltaba: el host anunciaba a <code>localUrl()</code> <b>hardcodeado</b> y el joiner
     * a la URL d/ LAN discovery (que en WAN es <code>null</code>) ⇒ <b>dos buzones
     * distintos</b> y la cadena nunca arrancaba.
     *
     * <p>§ Ojo: con config vacía devuelve loopback ⇒ solo sirve p/ LAN. El punch lo
     * detecta y lo dice (ver <code>isPublicIp</code> en {@code AzoreaPunchManager}).
     */
    public static List<String> effectiveTrackerUrls() {
        return effectiveTrackerUrls(AzoreaAutoTracker.get());
    }

    /**
     * Combina las URLs del config + el tracker embebido (si no hay URLs en config).
     * Si el usuario tiene URLs en config, esas ganan (no añadimos el embedded).
     * Si no tiene ninguna, usamos solo el embedded.
     */
    private static List<String> effectiveTrackerUrls(@Nullable final AzoreaAutoTracker autoTracker) {
        // § DA-11/DA-12: curados (lista d/ fábrica d' AzoreaRendezvous) ∪ lo q/ eligió
        //   el jugador en `trackers.urls`. Hoy la lista curada está VACÍA (nadie opera
        //   nuestro protocolo — DA-10 ⊘ nosotros) ⇒ resultado idéntico al anterior,
        //   pero cuando se llene (D1) entra sola sin tocar código d/ llamadores.
        final java.util.Set<String> out = new java.util.LinkedHashSet<>();
        out.addAll(com.azorea.mod.v1211.client.AzoreaRendezvous.curated());
        out.addAll(AzoreaConfig.getTrackerUrls());
        if (!out.isEmpty()) {
            return java.util.List.copyOf(out);
        }
        if (autoTracker != null) {
            return java.util.List.of(autoTracker.localUrl());
        }
        return List.of();
    }

    private void registerPayloads(final RegisterPayloadHandlersEvent event) {
        // Versión del protocolo. Cambiar si el formato de payload cambia incompatiblemente.
        event.registrar("1")
                .playToServer(PingPayload.TYPE, PingPayload.STREAM_CODEC, AzoreaNetwork::handlePingOnServer)
                .playToClient(PongPayload.TYPE, PongPayload.STREAM_CODEC, AzoreaNetwork::handlePongOnClient)
                // § F10/A2: fase CONFIGURATION (antes del spawn). El registro sólo tiene
                //   efecto si el modo del mundo es no-premium — si no, nadie envía esto.
                // § FIX F10 (2026-10-05): FALTABA el registro del RETO (server→client).
                //   Sin él el server lanzaba "Payload azorea:access_challenge may not be
                //   sent to the client!" ⇒ nadie respondía ⇒ timeout 15 s ⇒ kick.
                .configurationToClient(IdentityChallengePayload.TYPE,
                        IdentityChallengePayload.STREAM_CODEC,
                        AzoreaNetwork::handleChallengeOnClient)
                .configurationToServer(IdentityProofPayload.TYPE, IdentityProofPayload.STREAM_CODEC,
                        AzoreaAccessTask::onProof);
    }

    /**
     * § F10/A2: registra el reto de identidad en la fase de CONFIGURATION.
     *
     * <p>Sólo si el modo del mundo activo es <b>no-premium</b> — en premium (o sin mundo
     * cargado) {@link AzoreaAccessTask#create} devuelve {@code null} y <b>no se registra
     * nada</b>: comportamiento idéntico al actual.
     *
     * <p>El jugador queda congelado hasta que firme o vence el timeout de 15 s; no llega a
     * spawnear en ningún caso.
     */
    private void onRegisterConfigurationTasks(final RegisterConfigurationTasksEvent event) {
        // § DA-14/N1: instalar AzoreaNet en esta conexión. Ocurre en CONFIGURATION, q/
        //   llega DESPUÉS d/ LOGIN ⇒ el handler 'compress' d/ vanilla ya existe y puede
        //   sustituirse. Independiente del gate F10 (s/ task no significa s/ codec).
        com.azorea.mod.v1211.net.AzoreaNet.install(event.getListener().getConnection());

        final AzoreaAccessTask task = AzoreaAccessTask.create(event.getListener());
        if (task != null) {
            event.register(task);
        }
    }

    /**
     * § F10/A2: watchdog del timeout — desconecta a quien no responda en 15 s.
     *
     * <p>Sin él, un cliente sin el mod deja el servidor esperando para siempre (la doc de
     * NeoForge lo advierte: <i>«the server will wait forever»</i>). Corre en el hilo
     * principal; con el gate inactivo la lista está vacía y sale sin coste.
     */
    private void onServerTick(final ServerTickEvent.Post event) {
        AzoreaAccessTask.tick(System.currentTimeMillis());
        // § DA-14/N1: informe periódico d/ bytes (AzoreaNetLog/NET), sólo si hubo tráfico.
        com.azorea.mod.v1211.net.AzoreaNet.onServerTick();
    }

    /**
     * § F10/A3: al arrancar el mundo se carga <b>su</b> modo de acceso y se aplica a MC.
     *
     * <p>Es el momento exacto porque:
     * <ul>
     *   <li>el modo vive en el mundo, y el mundo ya existe — nada que adivinar;</li>
     *   <li>llega <b>antes</b> de que nadie pueda conectarse (el autohost publica después),
     *       así que nadie se cuela con el modo viejo;</li>
     *   <li>es lo único que hoy impide a un jugador no premium llegar a nuestro gate:
     *       {@code IntegratedServer} arranca con {@code setUsesAuthentication(true)} y lo
     *       echa en ~55 ms (el caso de {@code Player2}).</li>
     * </ul>
     */
    private void onServerStarted(final ServerStartedEvent event) {
        final MinecraftServer server = event.getServer();
        final Path azoreaDir = server.getWorldPath(
                new LevelResource(AzoreaWorldAccess.DIR_NAME));
        final AzoreaWorldAccess world = new AzoreaWorldAccess(azoreaDir).load();

        AzoreaAccessState.setWorld(world);
        // El modo ya viene del propio mundo: esto sólo lo refleja en el holder y en MC.
        applyAccessMode(server, world, world.mode());
    }

    /**
     * Aplica un modo de acceso: lo persiste <b>en el mundo</b>, lo refleja en el holder del
     * gate y sincroniza el {@code online-mode} de MC.
     *
     * <p>Es el único sitio donde se decide — lo usan el arranque del mundo y el comando
     * de debug, para que no haya dos formas de hacer lo mismo.
     *
     * @param server el servidor cuyo {@code online-mode} hay que sincronizar
     * @param world  el registro del mundo activo (debe ser el de {@code server})
     * @param mode   modo nuevo
     */
    public void applyAccessMode(final MinecraftServer server, final AzoreaWorldAccess world,
                                final AzoreaAccessState.Mode mode) {
        if (server == null || world == null) {
            return;
        }
        world.setMode(mode);                       // persiste en <mundo>/azorea/access.json
        AzoreaAccessState.setMode(mode);           // el gate lo lee desde aquí

        final boolean premium = mode == AzoreaAccessState.Mode.PREMIUM;
        server.setUsesAuthentication(premium);

        LOGGER.info("[azorea/access] modo={} · online-mode={} · dir={}",
                mode, premium, world.directory());

        if (!premium) {
            bootstrapHost(world);
        }
    }

    /** Al parar el mundo se suelta todo: gate desactivado y esperas pendientes limpias. */
    private void onServerStopped(final ServerStoppedEvent event) {
        AzoreaAccessTask.clearPending();
        AzoreaAccessState.reset();
        LOGGER.info("[azorea/access] mundo descargado — gate inactivo");
    }

    /**
     * § F10 (b): <b>el host se auto-registra</b> al activar no-premium, para que no pueda
     * bloquearse a sí mismo en su propio mundo.
     *
     * <p>Sólo si la identidad ya está inicializada (el cliente la crea en client setup,
     * antes de abrir cualquier mundo). Si no lo está, se loguea con claridad — el modo es
     * del mundo y la vía de emergencia es editar {@code <mundo>/azorea/} a mano, que es
     * justo la «configuración manual sólo si hace falta».
     */
    private void bootstrapHost(final AzoreaWorldAccess world) {
        try {
            final var idSvc = identityService();
            if (idSvc == null) {
                LOGGER.error("[azorea/access] sin identityService ⇒ el host NO se auto-registra."
                        + " Si quedas fuera, edita <mundo>/azorea/identities.json a mano.");
                return;
            }
            final var identity = idSvc.getIdentity();
            if (identity == null) {
                LOGGER.error("[azorea/access] identidad aún no inicializada ⇒ sin auto-registro.");
                return;
            }
            final AzoreaWorldAccess.RegisterResult r = world.register(
                    identity.azoreaId(),
                    identity.displayName(),
                    AzoreaAccessGate.encode(idSvc.signingPublicKey()),
                    AzoreaAccessGate.encode(identity.x25519PublicKey()),
                    AzoreaAccessGate.encode(idSvc.hwCommit()),
                    System.currentTimeMillis() / 1000L);
            LOGGER.info("[azorea/access] bootstrap del host ⇒ {} ({})",
                    identity.azoreaId(), r.status());
        } catch (final Exception e) {
            LOGGER.error("[azorea/access] bootstrap falló: {}", e.getMessage(), e);
        }
    }

    private void registerCommands(final RegisterCommandsEvent event) {
        AzoreaCommand.register(event.getDispatcher(), event.getBuildContext());
    }
}
