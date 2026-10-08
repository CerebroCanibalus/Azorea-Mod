// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.client;

/**
 * Qué jugadores tendrán acceso a comandos cuando el host publique su mundo.
 *
 * <p>§ <b>Decisión del General (2026-10-05)</b>: tres estados — <b>No</b> (nadie, el host
 * incluido) · <b>Sólo host</b> · <b>Sí</b> (todos) — y <b>vía op-list</b>, porque es la única
 * palanca q/ cubre los 3 casos <b>sin tocar la config del mundo</b>.
 *
 * <p>§ <b>Por qué la op-list</b> — el mecanismo real d/ vanilla (verificado en fuentes):
 *
 * <pre>
 *   PlayerList.isOp(p) = ops.contains(p)
 *                        || (esDueñoDelMundo(p) && worldData.isAllowCommands())
 *                        || allowCommandsForAllPlayers
 *
 *   getProfilePermissions(p) = 0                                   si !isOp(p)
 *                              ops.get(p).getLevel()                si está en la op-list
 *                              4                                   si es dueño
 *                              allowCommandsForAllPlayers ? 4 : 0  si es singleplayer
 * </pre>
 *
 * <p>De ahí salen dos verdades q/ mandan el diseño:
 * <ul>
 *   <li><b>Los que se unen quedan en 0 siempre</b> que {@code allowCommandsForAllPlayers}
 *       esté a false — no son dueños ni ops ⇒ {@code isOp} false ⇒ 0. Eso es gratis.</li>
 *   <li><b>El host no es controlable salvo por la op-list</b>: si su mundo se creó <i>con</i>
 *       cheats, {@code isOp} es true y devuelve 4 aunque mandemos «no»; si se creó
 *       <i>sin</i>, es 0 aunque mandemos «sólo host». <b>Ponerle una entrada con nivel
 *       0 ó 4 gana siempre</b> — porque esa rama se evalúa <b>antes</b> que la d/ dueño.</li>
 * </ul>
 *
 * <p>§ <b>Puro a propósito</b>: ni MC ni red ⇒ se testea entero bajo {@code compileTestJava}
 * (que <b>no</b> ve clases d/ Minecraft). La parte q/ toca {@code ServerOpList} vive en
 * {@link AzoreaAutohostService}, q/ es quien gestiona el ciclo d/ vida d/ hostear y por tanto
 * puede <b>restaurar</b> lo anterior al parar.
 *
 * <p>§ <b>Riesgo residual conocido</b>: la op-list se escribe en {@code ops.json}
 * (<i>gameDir</i>, ⊘ &lt;mundo&gt;) al guardar. Si el host cierra el mundo <b>sin parar d/
 * hostear</b> y hubo un guardado en medio, su entrada puede quedar como la dejó ⇒ se
 * recupera con {@code /op}. Por eso hay restore en dos sitios (parar + parada d/ server).
 */
public final class AzoreaHostPermissions {

    private AzoreaHostPermissions() {
    }

    /** Los tres estados del toggle «Trampas». */
    public enum Mode {
        /** Nadie tiene comandos — ni siquiera el host. */
        OFF,
        /** Sólo el host. Los que se unen quedan en 0. */
        HOST_ONLY,
        /** Todos (equivalente al «Allow cheats» d/ Abrir a LAN d/ vanilla). */
        ALL
    }

    /**
     * La decisión ya traducida a los dos mandos q/ controla vanilla.
     *
     * @param allowCommandsForAllPlayers valor d/ {@code PlayerList.setAllowCommandsForAllPlayers}
     *                                   — lo q/ decide si <b>los q/ se unen</b> son «op»
     * @param hostOpLevel                nivel q/ debe tener el host en la <b>op-list</b>
     *                                   (0 ⇒ sin comandos, 4 ⇒ op pleno)
     */
    public record Decision(boolean allowCommandsForAllPlayers, int hostOpLevel) {

        /** Conveniencia: ¿el modo deja pasar comandos a todo el mundo? */
        public boolean allowAll() {
            return allowCommandsForAllPlayers;
        }
    }

    /**
     * Traduce un modo a los mandos d/ vanilla.
     *
     * <p>§ <b>El host SIEMPRE lleva entrada en la op-list</b> (nivel 0 ó 4), aunque su
     * mundo ya le diera — o ya no le diera — permisos por ser dueño. Eso hace el
     * resultado <b>determinista</b>: no depende d/ cómo se creó el mundo ni d/ la op-list
     * previa. El estado previo se restaura al parar (ver clase).
     *
     * @param mode el toggle d/ Trampas
     * @return los dos mandos, nunca null
     */
    public static Decision resolve(final Mode mode) {
        if (mode == null) {
            return resolve(Mode.HOST_ONLY);
        }
        return switch (mode) {
            // § allowAll=true ⇒ isOp=true p/ todos ⇒ dueño⇒4, joiners⇒4. El host lleva 4
            //   explícito t/ para q/ la rama «ops.get != null» d-él d-4 aunque hubiera
            //   quedado una entrada con 0 d/ una sesión anterior.
            case ALL -> new Decision(true, 4);
            // § allowAll=false ⇒ joiners ⇒ isOp=false ⇒ 0 (gratis) · host ⇒ 4.
            case HOST_ONLY -> new Decision(false, 4);
            // § allowAll=false ⇒ joiners ⇒ 0 · host ⇒ 0 (gana la rama ops.get⇒level,
            //   ANTES q/ «es dueño ⇒ 4») — ése es el motivo d/ usar la op-list.
            case OFF -> new Decision(false, 0);
        };
    }
}
