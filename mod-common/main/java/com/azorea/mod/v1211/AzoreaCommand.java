// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211;

import com.azorea.mod.v1211.access.AzoreaAccessState;
import com.azorea.mod.v1211.access.AzoreaWorldAccess;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Comandos de Azorea. `host`/`join` son stubs de debug; `mode` (F10/A3) sí tiene efecto
 * real: cambia el <b>modo de acceso del mundo</b> y sincroniza el {@code online-mode} de MC.
 *
 * § Seguridad (AGENTS.md): args validados antes de procesar (rango, charset). `mode`
 * exige permiso de op — cambia cómo se entra a un mundo.
 */
public final class AzoreaCommand {

    private AzoreaCommand() {
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher,
                                final CommandBuildContext buildContext) {
        dispatcher.register(
                Commands.literal("azorea")
                        .then(Commands.literal("host")
                                .executes(AzoreaCommand::runHost))
                        .then(Commands.literal("join")
                                .then(Commands.argument("id", StringArgumentType.string())
                                        .executes(AzoreaCommand::runJoin)))
                        .then(Commands.literal("mode")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.literal("premium")
                                        .executes(ctx -> runMode(ctx,
                                                AzoreaAccessState.Mode.PREMIUM)))
                                .then(Commands.literal("no-premium")
                                        .executes(ctx -> runMode(ctx,
                                                AzoreaAccessState.Mode.NO_PREMIUM)))
                                .executes(AzoreaCommand::runModeShow))
                        // § § § 1.4.1/1.4.9: comando de debug p/ nametag (sin amigo).
                        //   § 1.4.10: el comando /azorea nametag NO es experimental —
                        //   siempre disponible para que el user pueda debuggear el
                        //   renderer cuando lo arregle. Lo experimental es el
                        //   AzoreaNametagRenderer (over real players), q/ ahora se
                        //   controla con ui.nametag_overlay = false por default.
                        .then(Commands.literal("nametag")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.literal("kill")
                                        .executes(AzoreaCommand::runNametagKill))
                                .then(Commands.literal("config")
                                        .then(Commands.argument("latency",
                                                IntegerArgumentType.integer(0, 60000))
                                                .executes(AzoreaCommand::runNametagConfig)))
                                .then(Commands.literal("spawn")
                                        .then(Commands.argument("name", StringArgumentType.string())
                                                .then(Commands.argument("latency",
                                                        IntegerArgumentType.integer(0, 60000))
                                                        .executes(AzoreaCommand::runNametagSpawn)
                                                        .then(Commands.literal("sneaking")
                                                                .executes(
                                                                        AzoreaCommand::runNametagSpawnSneaking))))))
        );
    }

    private static int runHost(final CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(
                () -> AzoreaLang.text("command.host_stub"),
                true);
        return 1;
    }

    /**
     * § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § §
     *   § § 1.4.1: pending request p/ el cliente (singleplayer). El server-side
     *   command deja la acción pendiente en un campo estático; el ClientTickEvent
     *   del cliente la recoge y la ejecuta. Esto evita el flujo packet+
     *   NetworkDirection sólo p/ una entity client-only.
     * § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § § §
     */
    private static volatile PendingNametagRequest pending = null;

    public enum NametagOp { SPAWN, KILL, CONFIG }
    public static final class PendingNametagRequest {
        public final NametagOp op;
        public final String name;
        public final int latency;
        public final boolean sneaking;
        public PendingNametagRequest(final NametagOp op, final String name,
                                     final int latency, final boolean sneaking) {
            this.op = op; this.name = name; this.latency = latency; this.sneaking = sneaking;
        }
    }

    private static int runNametagSpawn(final CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        final String name = StringArgumentType.getString(ctx, "name");
        final int latency = IntegerArgumentType.getInteger(ctx, "latency");
        pending = new PendingNametagRequest(NametagOp.SPAWN, name, latency, false);
        // § § § FIX 1.4.5: sendSuccess() requiere un Component ya construido
        //   (no un Supplier<Component>) si el resultado va al chat del
        //   server en singleplayer — el Supplier lazy se evalúa mal y los
        //   {0}/{1} salen literales. Usamos sendSuccess(() -> Component, ...)
        //   con un Component que ya tiene values dentro (no translatable).
        ctx.getSource().sendSuccess(
                () -> Component.literal("[Azorea] §a nametag spawned — name=\""
                        + name + "\" latency=" + latency + "ms sneaking=false"),
                true);
        return 1;
    }

    private static int runNametagSpawnSneaking(final CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        final String name = StringArgumentType.getString(ctx, "name");
        final int latency = IntegerArgumentType.getInteger(ctx, "latency");
        pending = new PendingNametagRequest(NametagOp.SPAWN, name, latency, true);
        ctx.getSource().sendSuccess(
                () -> Component.literal("[Azorea] §a nametag spawned — name=\""
                        + name + "\" latency=" + latency + "ms sneaking=true"),
                true);
        return 1;
    }

    private static int runNametagKill(final CommandContext<CommandSourceStack> ctx) {
        pending = new PendingNametagRequest(NametagOp.KILL, null, 0, false);
        ctx.getSource().sendSuccess(
                () -> AzoreaLang.text("command.nametag_killed"),
                true);
        return 1;
    }

    private static int runNametagConfig(final CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        final int latency = IntegerArgumentType.getInteger(ctx, "latency");
        pending = new PendingNametagRequest(NametagOp.CONFIG, null, latency, false);
        ctx.getSource().sendSuccess(
                () -> AzoreaLang.text("command.nametag_configured", latency, false),
                true);
        return 1;
    }

    /**
     * § § § Llamado por AzoreaClient.onClientTick c/ la pending request, si la hay.
     * La consume y la ejecuta. Devuelve la request o null.
     */
    public static PendingNametagRequest consumePending() {
        final PendingNametagRequest p = pending;
        pending = null;
        return p;
    }

    private static int runJoin(final CommandContext<CommandSourceStack> ctx) {
        final String id = StringArgumentType.getString(ctx, "id");

        // § Seguridad: validar id antes de procesar (charset + longitud).
        if (!isValidPeerId(id)) {
            ctx.getSource().sendFailure(
                    AzoreaLang.text("command.invalid_id"));
            return 0;
        }

        ctx.getSource().sendSuccess(
                () -> AzoreaLang.text("command.join_stub", id),
                true);
        return 1;
    }

    /**
     * § F10/A3: cambia el modo de acceso <b>del mundo cargado</b>.
     *
     * <p>Se persiste en {@code <mundo>/azorea/access.json} ⇒ sobrevive a reinicios y es
     * editable a mano si algo se tuerce (la «configuración manual sólo si hace falta»).
     * Aplicar el cambio <b>no</b> expulsa a nadie que ya esté dentro — sólo afecta a
     * quienes entren a partir de ahora.
     */
    private static int runMode(final CommandContext<CommandSourceStack> ctx,
                               final AzoreaAccessState.Mode mode) {
        final AzoreaWorldAccess world = AzoreaAccessState.world();
        if (world == null) {
            ctx.getSource().sendFailure(AzoreaLang.text("command.mode_no_world"));
            return 0;
        }
        final MinecraftServer server = ctx.getSource().getServer();
        AzoreaMod.get().applyAccessMode(server, world, mode);

        final boolean premium = mode == AzoreaAccessState.Mode.PREMIUM;
        ctx.getSource().sendSuccess(() -> AzoreaLang.text("command.mode_changed",
                mode, premium, premium
                        ? AzoreaLang.text("command.gate_inactive")
                        : AzoreaLang.text("command.gate_active")), true);
        return 1;
    }

    /** § F10/A3: consulta sin argumentos — {@code /azorea mode}. */
    private static int runModeShow(final CommandContext<CommandSourceStack> ctx) {
        final AzoreaWorldAccess world = AzoreaAccessState.world();
        final AzoreaAccessState.Mode mode =
                world != null ? world.mode() : AzoreaAccessState.mode();
        final boolean premium = mode == AzoreaAccessState.Mode.PREMIUM;
        ctx.getSource().sendSuccess(() -> AzoreaLang.text("command.mode_show",
                mode, premium,
                premium
                        ? AzoreaLang.text("command.gate_inactive")
                        : AzoreaLang.text("command.gate_active"),
                world == null ? AzoreaLang.text("command.mode_no_world_short")
                        : AzoreaLang.text("")), true);
        return 1;
    }

    private static boolean isValidPeerId(final String id) {
        if (id == null || id.length() < 4 || id.length() > 64) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            final char c = id.charAt(i);
            final boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
