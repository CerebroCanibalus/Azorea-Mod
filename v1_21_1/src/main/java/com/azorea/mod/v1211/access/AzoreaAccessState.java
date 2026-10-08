// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.access;

/**
 * Estado del <b>modo de acceso</b> del mundo activo (decisión del General 2026-10-04, § e:
 * el selector vive <b>en el mundo</b>, no en config).
 *
 * <p><b>§ Por qué un holder estático.</b> El evento {@code RegisterConfigurationTasksEvent}
 * llega desde el mod bus sin pasar por ninguna instancia nuestra, y hay que decidir ahí mismo
 * si registramos la task de identidad o no. Guardar aquí el mundo activo + el modo permite que
 * esa decisión sea inmediata y sin buscar el servidor.
 *
 * <p><b>§ Seguridad por defecto.</b> El modo arranca en {@link Mode#PREMIUM} y el mundo en
 * {@code null} ⇒ {@link #gateEnabled()} devuelve {@code false} ⇒ <b>no se registra ninguna
 * task</b> ⇒ el comportamiento es <b>idéntico al actual</b> hasta que A3 inicialice esto.
 * Así A2 puede desplegarse sin cambiar nada todavía.
 *
 * <p><b>§ Hilos.</b> Campos {@code volatile}: se escriben al cargar/publicar el mundo (hilo
 * principal) y se leen desde el evento de conexión. Sólo lectura/escritura de referencia.
 */
public final class AzoreaAccessState {

    public enum Mode {
        /** Mojang verifica la sesión (lo de siempre, {@code online-mode=true}). */
        PREMIUM,
        /**
         * Gate por {@code azorea_id} Azorea (F10). El host que elige éste es quien asume
         * que su mundo lo identifica por Azorea y no por cuenta premium.
         */
        NO_PREMIUM
    }

    private static volatile Mode mode = Mode.PREMIUM;
    private static volatile AzoreaWorldAccess world;

    private AzoreaAccessState() {
    }

    public static Mode mode() {
        return mode;
    }

    public static void setMode(final Mode newMode) {
        mode = newMode == null ? Mode.PREMIUM : newMode;
    }

    /** Registro de identidades del mundo activo. */
    public static AzoreaWorldAccess world() {
        return world;
    }

    public static void setWorld(final AzoreaWorldAccess newWorld) {
        world = newWorld;
    }

    /**
     * ¿Hay que exigir identidad Azorea a quien entre?
     *
     * <p>Exige <b>ambas</b> cosas: el modo no-premium <b>y</b> un mundo cargado. Si falta
     * cualquiera de las dos, <b>no hay gate</b> y todo funciona como hasta ahora.
     */
    public static boolean gateEnabled() {
        return mode == Mode.NO_PREMIUM && world != null;
    }

    /** Sólo para tests: vuelve al estado de fábrica (premium, sin mundo). */
    public static void reset() {
        mode = Mode.PREMIUM;
        world = null;
    }
}
