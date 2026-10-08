// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.net;

import com.azorea.mod.v1211.AzoreaLang;
import com.azorea.mod.v1211.AzoreaMod;
import com.azorea.mod.v1211.access.AzoreaAccessGate;
import com.azorea.mod.v1211.identity.AzoreaIdentity;
import com.azorea.mod.v1211.identity.AzoreaIdentityService;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * Handlers de los payloads smoke test. Se registran en {@code RegisterPayloadHandlersEvent}
 * desde el subproy de versión (AzoreaMod).
 *
 * § Seguridad: handlers son no-ops informativos; no mutan estado compartido ni procesan input externo.
 */
public final class AzoreaNetwork {

    private static final Logger LOGGER = LogUtils.getLogger();

    private AzoreaNetwork() {
    }

    public static void handlePingOnServer(final PingPayload payload, final IPayloadContext context) {
        LOGGER.info("[azorea/server] ping received: nonce={} timestamp={}",
                payload.nonce(), payload.timestamp());
        // Responder con pong (eco del nonce). context.reply() se ejecuta en el hilo del server.
        context.reply(new PongPayload(payload.nonce(), System.currentTimeMillis()));
    }

    public static void handlePongOnClient(final PongPayload payload, final IPayloadContext context) {
        LOGGER.info("[azorea/client] pong received: nonce={} timestamp={}",
                payload.nonce(), payload.timestamp());
    }

    /**
     * § F10/A2 — cliente: responde al reto de identidad con un proof firmado.
     *
     * <p><b>§ Por qué existe</b>: el registro faltaba — sólo estaba
     * {@code configurationToServer(IdentityProofPayload)}; el <b>reto</b> no estaba
     * registrado como {@code configurationToClient} ⇒ el server lanzaba
     * {@code UnsupportedOperationException: Payload azorea:access_challenge may not be
     * sent to the client!}, nadie respondía y el watchdog de 15 s echaba al jugador con
     * «¿Tienes el mod Azorea instalado?». <b>F10 nunca había funcionado end-to-end</b>
     * (estaba «bloqueado por disponibilidad») — se detectó al probarlo en runtime.
     *
     * <p><b>§ Qué firma</b>: {@code "azorea-gate/v1" ∥ 0x00 ∥ reto} con la Ed25519 local
     * (DA-8/DA-9). El server verifica 2 pasos: que la ID derive de las claves publicadas,
     * y que la firma valide contra el reto <b>de esta conexión</b> ⇒ anti-replay.
     *
     * <p><b>§ Sobre {@code displayName}</b>: se manda el de la identidad local y <b>no</b>
     * el de MC en vivo. Es deliberado — usar {@code Minecraft.getInstance()} aquí metería
     * código de cliente en una clase que <b>también carga el server dedicado</b> (riesgo de
     * {@code NoClassDefFoundError}), y el nombre es <b>advisory por diseño</b>: el gate
     * nunca decide acceso con él, sólo flaggea duplicados aparentes (y {@code NAME_TAKEN}
     * sólo loguea, no rechaza). Puede desincronizarse si el jugador renombra su cuenta.
     *
     * <p>Nunca lanza: cualquier fallo desconecta con motivo legible — un payload roto del
     * cliente no puede tumbar la conexión.
     */
    public static void handleChallengeOnClient(final IdentityChallengePayload payload,
                                               final IPayloadContext context) {
        final byte[] challenge = AzoreaAccessGate.decode(payload.challengeB64());
        if (challenge == null || challenge.length != AzoreaAccessGate.CHALLENGE_BYTES) {
            context.disconnect(AzoreaLang.text("access.kicked_challenge_invalid"));
            return;
        }

        final AzoreaIdentityService service = AzoreaMod.get().identityService();
        final AzoreaIdentity identity = service == null ? null : service.getIdentity();
        if (identity == null) {
            context.disconnect(AzoreaLang.text("access.kicked_no_identity"));
            return;
        }

        final byte[] hwCommit = service.hwCommit();
        if (hwCommit == null || hwCommit.length == 0) {
            context.disconnect(AzoreaLang.text("access.kicked_no_hw_commit"));
            return;
        }

        final byte[] signature;
        try {
            signature = service.sign(AzoreaAccessGate.canonic(challenge));
        } catch (final GeneralSecurityException e) {
            LOGGER.warn("[azorea/client] no pude firmar el reto: {}", e.toString());
            context.disconnect(AzoreaLang.text("access.kicked_sign_failed"));
            return;
        }

        context.reply(new IdentityProofPayload(
                AzoreaAccessGate.VERSION,
                identity.azoreaId(),
                identity.displayName(),
                b64(service.signingPublicKey()),
                b64(identity.x25519PublicKey()),
                b64(hwCommit),
                b64(signature)));
    }

    /** Base64 estándar; {@code null} si la entrada es null (el gate lo rechaza con motivo). */
    private static String b64(final byte[] raw) {
        return raw == null ? null : Base64.getEncoder().encodeToString(raw);
    }
}
