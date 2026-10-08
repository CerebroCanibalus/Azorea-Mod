// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

import com.azorea.mod.AzoreaConstants;
import com.azorea.mod.v1211.AzoreaCommand;
import com.azorea.mod.v1211.AzoreaMod;
import com.azorea.mod.v1211.client.friends.AzoreaFriendsService;
import com.azorea.mod.v1211.client.screen.AzoreaHostConfigScreen;
import com.azorea.mod.v1211.client.screen.AzoreaMainScreen;
import com.azorea.mod.v1211.identity.AzoreaIdentityService;
import com.azorea.mod.tracker.AzoreaInviteInbox;
import com.azorea.mod.tracker.AzoreaInviteService;
import com.azorea.mod.tracker.AzoreaServices;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Cliente Azorea — keybinds + entry points UI (ver AGENTS.md § DA-6 + F3.0 + F4.5).
 *
 * § Cliente-only: clases con Minecraft referenciadas deben vivir en paquete {@code client/}.
 * ModDevGradle strip estos del server build vía {@code @OnlyIn(Dist.CLIENT)} implícito por paquete.
 *
 * § F4.5: inicializa AzoreaFriendsService en ClientSetupEvent (cuando Minecraft está listo).
 */
@EventBusSubscriber(modid = AzoreaConstants.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class AzoreaClient {

    /** Keybind: abre menú principal Azorea (deprecado en F4 — ahora via pause menu). */
    public static final KeyMapping OPEN_MENU = new KeyMapping(
            "key.azorea.open_menu",
            net.neoforged.neoforge.client.settings.KeyConflictContext.UNIVERSAL,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            "key.categories.azorea"
    );

    private AzoreaClient() {
    }

    /**
     * § F4.5 + F5.1: inicializa friends service + identity service cuando MC está listo.
     * FMLClientSetupEvent se dispara en mod bus cliente tras FMLCommonSetup.
     */
    @SubscribeEvent
    public static void onClientSetup(final FMLClientSetupEvent event) {
        final Minecraft mc = Minecraft.getInstance();
        // Autohost singleton (F3.4 + F6.1).
        try {
            AzoreaMod.get().setAutohostService(new AzoreaAutohostService());
        } catch (Exception e) {
            // ignore
        }
        // Friends service.
        try {
            final AzoreaFriendsService friendsSvc = new AzoreaFriendsService(mc.gameDirectory);
            AzoreaMod.get().setFriendsService(friendsSvc);
        } catch (Exception e) {
            // ignore — friends es opcional
        }

        // § DA-7 / F7.5: LAN discovery automático via UDP multicast.
        // § F9 FIX (bug raíz): aquí SOLO se arranca. La identidad se inyecta más
        // abajo, DESPUÉS de crear el identity service. Antes se leía aquí
        // identityService() que aún era null → setIdentity() no se llamaba nunca →
        // myAzoreaId quedaba null → broadcastHello() hacía `if (myAzoreaId == null)
        // return;` → LAN discovery no emitía NUNCA, no se descubría a ningún peer,
        // y por tanto tampoco existía peer.trackerUrl() (bug#4) ni direct connect.
        try {
            com.azorea.mod.v1211.tracker.lan.AzoreaLanDiscovery.getOrCreate();
        } catch (Exception e) {
            // ignore — LAN discovery es opcional
        }

        // § F8.x: UPnP scan async al cargar el mod (para port-forwarding automático).
        // El scan corre en background (~2s); cuando el user inicie host, el gateway
        // ya estará disponible y openPort() funcionará.
        try {
            com.azorea.mod.v1211.client.AzoreaUpnpService.get().startScan();
        } catch (Exception e) {
            // ignore — UPnP es opcional
        }
        // Identity service (F5.1).
        try {
            final AzoreaIdentityService identitySvc = new AzoreaIdentityService(mc.gameDirectory.toPath());
            // Display name por defecto = MC username (puede ser null en offline).
            final String defaultName;
            if (mc.getUser() != null && mc.getUser().getName() != null
                    && !mc.getUser().getName().isBlank()) {
                defaultName = mc.getUser().getName();
            } else {
                defaultName = "Player";
            }
            identitySvc.getOrCreate(defaultName);
            AzoreaMod.get().setIdentityService(identitySvc);

            // § F9 FIX (bug raíz): inyectar la identidad en LAN discovery AHORA
            // que existe. Estaba antes, con identityService()==null, y por eso
            // broadcastHello() salía siempre sin emitir (ver comentario arriba).
            final var lan = com.azorea.mod.v1211.tracker.lan.AzoreaLanDiscovery.get();
            final var autoTracker = com.azorea.mod.v1211.tracker.embedded.AzoreaAutoTracker.get();
            if (lan != null && identitySvc.getIdentity() != null) {
                lan.setIdentity(
                        identitySvc.getIdentity().azoreaId(),
                        identitySvc.getIdentity().displayName(),
                        autoTracker != null ? autoTracker.localUrl() : "");
            }
        } catch (Exception e) {
            // ignore — identity es opcional en F5.1 (no se usa todavía en runtime)
        }
        // § F7.2: Invite inbox — poll cada 10s, descifra con la private key del identity.
        try {
            final AzoreaServices services = AzoreaMod.get().services();
            final AzoreaInviteService inviteSvc = services != null ? services.invite() : null;
            final AzoreaIdentityService identitySvc = AzoreaMod.get().identityService();
            if (inviteSvc != null && identitySvc != null) {
                final AzoreaInviteInbox inbox = new AzoreaInviteInbox(inviteSvc, identitySvc);
                inbox.addListener(inv -> {
                    // § Notificación: chat + sonido al recibir invite.
                    if (mc.player != null && mc.gui != null) {
                        mc.gui.getChat().addMessage(Component.literal(
                                "§a[Azorea] Invite de §e" + inv.raw().fromIdentity().displayName()
                                        + "§a (§f" + inv.info().host() + ":" + inv.info().port() + "§a). Click en Azorea Menu > Invites."));
                    }
                });
                inbox.start(10);  // poll cada 10s
                AzoreaMod.get().setInviteInbox(inbox);
            }
        } catch (Exception e) {
            // ignore — invites son opcionales
        }
    }

    /**
     * § Debug d/ UI — abre una pantalla sola al arrancar, sin navegar c/ clicks.
     *
     * <p>Inerte si no se define (mismo patrón q/ {@code -Dazorea.net.verbose}), así q/ no
     * toca el camino normal. Valores: {@code host} | {@code main}.
     * Se lee property <b>o</b> variable d'entorno ⇒ se puede lanzar s/ tocar build.gradle:
     * <pre>
     *   $env:AZOREA_DEBUG_SCREEN='host'; ./gradlew :v1_21_1:runClient
     * </pre>
     *
     * <p>§ Por qué existe: probar una pantalla d/ diseño require cargar un mundo y navegar
     * hasta ella; con esto la init() corre sola y un crash d/ layout sale en el log.
     */
    private static final String DEBUG_SCREEN =
            System.getProperty("azorea.debugScreen", System.getenv("AZOREA_DEBUG_SCREEN"));
    private static boolean debugScreenOpened;

    /**
     * § Trampas (3 estados): restaura la op-list d/ l'host al <b>parar el server</b>.
     *
     * <p>Existe aparte d/ {@code stopAutohost()} por un caso concreto: si el host cierra
     * el mundo <b>sin pulsar Stop</b>, la sesión pudo haberle dejado nivel 0 («Trampas:
     * nadie») y quedaría sin comandos en su singleplayer. Es <i>best-effort</i> — nunca
     * puede impedir q/ el server pare.
     */
    @SubscribeEvent
    public static void onServerStopping(final net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        try {
            final var autohost = AzoreaMod.get().autohostService();
            if (autohost != null) {
                autohost.restoreHostPermissions(event.getServer());
            }
        } catch (final Exception ignored) {
            // § Una restauración fallida no debe tumbar la parada del server.
        }
    }

    /**
     * Listener de tick: si el keybind fue pulsado, abre el menú principal.
     */
    @SubscribeEvent
    public static void onClientTick(final ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();

        // § Con mundo cargado (quickPlay) no hay TitleScreen ⇒ abrimos igual, con pantalla nula.
        final boolean ready = mc.screen instanceof TitleScreen
                || (mc.level != null && mc.screen == null);
        if (DEBUG_SCREEN != null && !debugScreenOpened && ready) {
            debugScreenOpened = true;
            if ("host".equals(DEBUG_SCREEN)) {
                // § S/ mundo ⇒ server==null: ejercita los fallbacks d/ la pantalla
                //   (selector d/ acceso atenuado, defaults genéricos).
                //   C/ mundo ⇒ selector ACTIVO + defaults reales d/ la partida.
                mc.setScreen(new AzoreaHostConfigScreen(null));
            } else if ("main".equals(DEBUG_SCREEN)) {
                mc.setScreen(new AzoreaMainScreen(null));
            }
            return;
        }

        if (mc.player == null) {
            return;
        }
        if (OPEN_MENU.consumeClick()) {
            mc.setScreen(new AzoreaMainScreen());
        }

        // § § § 1.4.1: consumir pending request del comando /azorea nametag.
        //   Singleplayer: el command corre en el server thread pero MC lo serializa
        //   con el client thread en el mismo tick, así q/ el check aquí es seguro.
        final AzoreaCommand.PendingNametagRequest req = AzoreaCommand.consumePending();
        if (req != null) {
            handleNametagRequest(mc, req);
        }
    }

    /**
     * § § § Maneja la pending request del comando /azorea nametag. Singleplayer
     * only — en multiplayer dedicated el command no debería estar disponible,
     * pero por si acaso filtramos también.
     */
    private static void handleNametagRequest(final Minecraft mc,
                                              final AzoreaCommand.PendingNametagRequest req) {
        // § § § Filtro de seguridad: en multiplayer el cliente no puede spawnear
        //   entities de debug (sería visible para otros jugadores y no tiene sentido).
        //   En singleplayer mc.hasSingleplayerServer() es true.
        if (mc.getSingleplayerServer() == null) {
            mc.gui.getChat().addMessage(
                    com.azorea.mod.v1211.AzoreaLang.text("command.nametag_spawn_failed",
                            "singleplayer only"));
            return;
        }
        final net.minecraft.world.level.Level level = mc.level;
        if (level == null) {
            mc.gui.getChat().addMessage(
                    com.azorea.mod.v1211.AzoreaLang.text("command.nametag_spawn_failed",
                            "no world loaded"));
            return;
        }
        final net.minecraft.world.entity.EntityType<com.azorea.mod.v1211.client.debug
                .AzoreaDebugNametagEntity> type =
                com.azorea.mod.v1211.client.debug.AzoreaDebugNametagEntityType.get();
        if (type == null) {
            mc.gui.getChat().addMessage(
                    com.azorea.mod.v1211.AzoreaLang.text("command.nametag_spawn_failed",
                            "entity type not registered"));
            org.slf4j.LoggerFactory.getLogger(AzoreaClient.class)
                    .warn("[azorea/debug] EntityType es null — DeferredRegister no resolvió");
            return;
        }
        // § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § §
        //   § FIX 1.4.5: el addFreshEntity=false NO es por UUID duplicado
        //   (1.4.4 lo arregló y nada cambió). mc.level es un ClientLevel
        //   en singleplayer; el addEntity en ClientLevel es void y no añade
        //   la entity al mundo visible. Hay q/ usar el ServerLevel real,
        //   q/ en singleplayer vive en mc.getSingleplayerServer().getLevel(...).
        //   § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § §
        org.slf4j.LoggerFactory.getLogger(AzoreaClient.class)
                .info("[azorea/debug] handleNametagRequest op={} name={} latency={}ms sneaking={} clientLevel={}",
                        req.op, req.name, req.latency, req.sneaking, level.getClass().getSimpleName());
        switch (req.op) {
            case SPAWN -> {
                final var existing = findExisting(level);
                if (existing != null) {
                    existing.azorea$setData(req.name, req.latency, req.sneaking);
                    mc.gui.getChat().addMessage(
                            com.azorea.mod.v1211.AzoreaLang.text("command.nametag_configured",
                                    req.latency, req.sneaking));
                } else {
                    final var e = com.azorea.mod.v1211.client.debug.AzoreaDebugNametagEntity
                            .spawnInFrontOf(mc.player, type);
                    if (e == null) {
                        mc.gui.getChat().addMessage(
                                com.azorea.mod.v1211.AzoreaLang.text("command.nametag_spawn_failed",
                                        "spawn returned null"));
                        return;
                    }
                    e.azorea$setData(req.name, req.latency, req.sneaking);
                    // § § § FIX 1.4.5: añadir al ServerLevel del SP world, NO
                    //   al ClientLevel. En singleplayer, ClientLevel.addEntity
                    //   es void y no añade nada (de ahí addFreshEntity=false
                    //   en 1.4.2/3/4). El cliente la verá porque el flow d/
                    //   paquetes sincroniza el spawn.
                    final net.minecraft.client.server.IntegratedServer server =
                            mc.getSingleplayerServer();
                    final net.minecraft.server.level.ServerLevel serverLevel =
                            server != null ? server.getLevel(
                                    mc.level.dimension()) : null;
                    final boolean added;
                    if (serverLevel != null) {
                        added = serverLevel.addFreshEntity(e);
                    } else {
                        // § § § Fallback al client level (caso multiplayer dedicated
                        //   server donde no aplica este debug anyway).
                        added = level.addFreshEntity(e);
                    }
                    org.slf4j.LoggerFactory.getLogger(AzoreaClient.class)
                            .info("[azorea/debug] spawn '{}' @ {} → addFreshEntity={} serverLevel={}",
                                    req.name, e.position(), added,
                                    serverLevel != null ? serverLevel.getClass().getSimpleName() : "null");
                }
            }
            case KILL -> {
                final var existing = findExisting(level);
                if (existing != null) {
                    // § § § FIX 1.4.5: discard en el ServerLevel real.
                    existing.discard();
                } else {
                    mc.gui.getChat().addMessage(
                            com.azorea.mod.v1211.AzoreaLang.text("command.nametag_none"));
                }
            }
            case CONFIG -> {
                final var existing = findExisting(level);
                if (existing != null) {
                    existing.azorea$setLatency(req.latency);
                    mc.gui.getChat().addMessage(
                            com.azorea.mod.v1211.AzoreaLang.text("command.nametag_configured",
                                    req.latency, existing.isSneaking()));
                } else {
                    mc.gui.getChat().addMessage(
                            com.azorea.mod.v1211.AzoreaLang.text("command.nametag_none"));
                }
            }
        }
    }

    private static com.azorea.mod.v1211.client.debug.AzoreaDebugNametagEntity findExisting(
            final net.minecraft.world.level.Level level) {
        // § § § FIX 1.4.5: buscar en el ServerLevel real, NO en el ClientLevel.
        //   La entity vive en el server (donde la añadimos) — el cliente sólo
        //   la ve por el flow d/ paquetes. Por eso en 1.4.2/3/4 el kill no
        //   encontraba nada: buscábamos en mc.level y la entity nunca estuvo ahí.
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        final net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
        final net.minecraft.server.level.ServerLevel serverLevel = server != null
                ? server.getLevel(level.dimension()) : null;
        if (serverLevel == null) return null;
        final var et = com.azorea.mod.v1211.client.debug.AzoreaDebugNametagEntityType.get();
        if (et == null) return null;
        final var aabb = new net.minecraft.world.phys.AABB(
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        final var list = serverLevel.getEntities(et, aabb, e -> true);
        return list.isEmpty() ? null
                : (com.azorea.mod.v1211.client.debug.AzoreaDebugNametagEntity) list.get(0);
    }

    /**
     * Suscripción MOD bus para registrar keybinds (separado del GAME bus).
     */
    @EventBusSubscriber(modid = AzoreaConstants.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class KeybindRegistration {
        private KeybindRegistration() {}

        @SubscribeEvent
        public static void registerKeyMappings(final RegisterKeyMappingsEvent event) {
            event.register(AzoreaClient.OPEN_MENU);
        }
    }
}
