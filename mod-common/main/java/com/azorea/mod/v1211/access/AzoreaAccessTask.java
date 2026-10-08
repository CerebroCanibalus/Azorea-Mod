// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.access;

import com.azorea.mod.AzoreaConstants;
import com.azorea.mod.net.IdentityChallengePayload;
import com.azorea.mod.net.IdentityProofPayload;
import com.azorea.mod.v1211.AzoreaLang;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ConfigurationTask;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Configuration task del gate de identidad (F10, § A2).
 *
 * <p><b>§ El mecanismo</b> (doc NeoForge 1.21.1, «Stalling the login process»): al registrarse
 * la task mandamos el reto y <b>no</b> llamamos a {@code finishCurrentTask()} ⇒ <i>«the
 * server will wait forever, and the client will never join»</i>. El jugador queda congelado
 * en CONFIGURATION, <b>antes de spawnear</b>. Sólo dos salidas:
 *
 * <pre>
 *   firma OK  + registro aceptado  → finishCurrentTask()  → entra
 *   firma mal / bloqueado / timeout → disconnect()         → nunca spawnó
 * </pre>
 *
 * <p><b>§ Timeout de 15 s</b> (decisión del General). Obligatorio: sin él, un cliente que
 * tenga el mod pero no responda (o un cliente vanilla, que no entiende el payload) deja el
 * servidor esperando <b>para siempre</b>.
 *
 * <p><b>§ Hilos.</b> El handler de configuration corre en MAIN por defecto (doc NeoForge), y
 * el watchdog corre desde {@code ServerTickEvent.Post} ⇒ también MAIN. El mapa es
 * {@code ConcurrentHashMap} por prudencia, pero toda mutación ocurre en el hilo principal.
 *
 * <p><b>§ Nota de ciclo de vida</b>: una entrada que quede huérfana tras parar el server
 * expira sola con el deadline (15 s) — una referencia residual, nunca un hilo.
 */
public final class AzoreaAccessTask implements ICustomConfigurationTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaAccessTask.class);

    /** Identidad de la task dentro del protocolo de configuration. */
    public static final ConfigurationTask.Type TYPE =
            new ConfigurationTask.Type(ResourceLocation.fromNamespaceAndPath(
                    AzoreaConstants.MOD_ID, "access_identity"));

    /** Timeout de la respuesta — ver javadoc. */
    public static final long TIMEOUT_MS = 15_000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Conexiones esperando respuesta, con su task. */
    private static final Map<Connection, AzoreaAccessTask> PENDING = new ConcurrentHashMap<>();

    private final ServerConfigurationPacketListener listener;
    private final byte[] challenge;
    private final long deadlineMs;

    private AzoreaAccessTask(final ServerConfigurationPacketListener listener) {
        this.listener = listener;
        this.challenge = AzoreaAccessGate.newChallenge(RANDOM);
        this.deadlineMs = System.currentTimeMillis() + TIMEOUT_MS;
    }

    /**
     * Crea y arma la task si el gate está activo.
     *
     * @return la task a registrar en el evento, o {@code null} si <b>no</b> hay gate
     *         (modo premium o mundo sin cargar ⇒ comportamiento idéntico al actual)
     */
    public static AzoreaAccessTask create(final ServerConfigurationPacketListener listener) {
        if (!AzoreaAccessState.gateEnabled() || listener == null) {
            return null;
        }
        final Connection connection = listener.getConnection();
        if (connection == null) {
            LOGGER.warn("Gate: sin conexión al configurar; se omite el reto de identidad");
            return null;
        }
        final AzoreaAccessTask task = new AzoreaAccessTask(listener);
        PENDING.put(connection, task);
        return task;
    }

    @Override
    public void run(final Consumer<net.minecraft.network.protocol.common.custom.CustomPacketPayload> sender) {
        sender.accept(new IdentityChallengePayload(AzoreaAccessGate.encode(challenge)));
    }

    @Override
    public ConfigurationTask.Type type() {
        return TYPE;
    }

    // ===== Entrada desde el handler de payload =====

    /**
     * Procesa la respuesta del cliente. Sólo puede venir de una conexión que tenga un reto
     * pendiente; cualquier otra cosa es un payload fuera de hora y se desconecta.
     */
    public static void onProof(final IdentityProofPayload proof, final IPayloadContext context) {
        if (context == null) {
            return;
        }
        final Connection connection = context.connection();
        final AzoreaAccessTask task = connection == null ? null : PENDING.get(connection);
        if (task == null) {
            LOGGER.warn("Gate: proof recibido sin reto pendiente ⇒ desconexión");
            context.disconnect(AzoreaLang.text("access.kicked_no_challenge"));
            return;
        }
        task.settle(proof, context, connection);
    }

    // ===== Resolución =====

    private void settle(final IdentityProofPayload proof, final IPayloadContext context,
                        final Connection connection) {
        // Primero en llegar gana: si dos proofs llegan (o llega tras el timeout), sólo
        // uno desactiva la task — evita doble finishCurrentTask.
        if (!PENDING.remove(connection, this)) {
            LOGGER.warn("Gate: respuesta duplicada o ya caducada ⇒ se ignora");
            return;
        }

        final AzoreaAccessGate.Verdict verdict =
                AzoreaAccessGate.verify(proof.toProof(), challenge);
        if (!verdict.ok()) {
            reject(context, connection, "access.kicked_identity_failed", verdict.reason());
            return;
        }

        final AzoreaWorldAccess world = AzoreaAccessState.world();
        if (world == null) {
            // No debería pasar (gateEnabled() exige mundo), pero si pasa: no dejamos colgado.
            LOGGER.error("Gate: mundo desapareció durante la verificación ⇒ se admite");
            context.finishCurrentTask(TYPE);
            return;
        }

        final AzoreaWorldAccess.RegisterResult result = world.register(
                verdict.azoreaId(), verdict.displayName(),
                verdict.ed25519PubB64(), verdict.x25519PubB64(), verdict.hwCommitB64(),
                System.currentTimeMillis() / 1000L);

        switch (result.status()) {
            case BLOCKED -> {
                reject(context, connection, "access.kicked_blocked");
                return;
            }
            case KEY_MISMATCH -> {
                reject(context, connection, "access.kicked_key_mismatch");
                return;
            }
            case NAME_TAKEN -> LOGGER.warn(
                    "Gate: {} entra como '{}' — ese nombre ya pertenecía a {} (duplicado aparente)",
                    verdict.azoreaId(), verdict.displayName(), result.previousOwner());
            case NEW -> LOGGER.info("Gate: identidad nueva registrada — {} ({})",
                    verdict.azoreaId(), verdict.displayName());
            case UPDATED -> LOGGER.debug("Gate: identidad conocida — {}", verdict.azoreaId());
        }

        // Registro aceptado ⇒ ya puede entrar.
        context.finishCurrentTask(TYPE);
    }

    private static void reject(final IPayloadContext context, final Connection connection,
                               final String whyKey, final Object... args) {
        LOGGER.warn("Gate: desconexión — {}", whyKey);
        PENDING.remove(connection);
        context.disconnect(AzoreaLang.text(whyKey, args));
    }

    // ===== Watchdog (hilo principal, desde ServerTickEvent.Post) =====

    /**
     * Desconecta a quien no haya respondido en 15 s.
     *
     * <p>Sin esto un cliente sin el mod (vanilla no entiende el payload) o con el mod
     * colgado deja el servidor esperando para siempre.
     */
    public static void tick(final long nowMs) {
        if (PENDING.isEmpty()) {
            return;
        }
        for (final Map.Entry<Connection, AzoreaAccessTask> entry
                : new ArrayList<>(PENDING.entrySet())) {
            final AzoreaAccessTask task = entry.getValue();
            if (task.deadlineMs > nowMs) {
                continue;
            }
            if (PENDING.remove(entry.getKey(), task)) {
                final Connection connection = entry.getKey();
                LOGGER.warn("Gate: sin identidad en {} ms ⇒ desconexión "
                                + "(¿cliente sin el mod o sin responder?)",
                        TIMEOUT_MS);
                connection.disconnect(AzoreaLang.text("access.kicked_timeout"));
            }
        }
    }

    /** Limpia la espera pendiente — al parar el server o al cambiar de mundo. */
    public static void clearPending() {
        PENDING.clear();
    }

    /** Nº de conexiones esperando — sólo para tests/log. */
    public static int pendingCount() {
        return PENDING.size();
    }

    /** Sólo para tests: reinicia el estado estático. */
    static void resetForTests() {
        PENDING.clear();
    }

    @Override
    public String toString() {
        return "AzoreaAccessTask{deadlineIn="
                + (deadlineMs - System.currentTimeMillis()) + "ms}";
    }
}
