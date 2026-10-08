// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * § Helper de i18n — prefijo común y punto único de creación.
 *
 * <p>Todos los textos de Azorea en pantallas, tooltips, comandos y mensajes del gate
 * pasan por aquí. La cadena se lee del idioma activo de MC ({@code en_us.json},
 * {@code es_es.json}, …) — y si falta, MC muestra la clave cruda, así nos enteramos
 * de inmediato en runtime.
 *
 * <p>§ Por qué un helper y no {@code Component.translatable(...)} a secas:
 * <ul>
 *   <li>evita repetir {@code "azorea."} por toda la base — los IDEs autocompletan
 *       nombres d/ sección;</li>
 *   <li>el sufijo siempre es una key válida bajo el prefijo, así el
 *       {@link com.azorea.mod.v1211.lang.AzoreaLangTest} cubre todas;</li>
 *   <li>las firmas sobrecargadas cubren 0/1/2/3 args sin tener q/ aprenderse
 *       los varargs d/ {@code translatable}.</li>
 * </ul>
 */
public final class AzoreaLang {

    private static final String PREFIX = "azorea.";

    private AzoreaLang() {
    }

    /** Key absoluta sin prefijo, p.ej. {@code "host.title"} o {@code "common.back"}. */
    public static MutableComponent text(final String key) {
        return Component.translatable(PREFIX + key);
    }

    public static MutableComponent text(final String key, final Object arg0) {
        return Component.translatable(PREFIX + key, arg0);
    }

    public static MutableComponent text(final String key, final Object arg0, final Object arg1) {
        return Component.translatable(PREFIX + key, arg0, arg1);
    }

    public static MutableComponent text(final String key, final Object arg0, final Object arg1,
                                        final Object arg2) {
        return Component.translatable(PREFIX + key, arg0, arg1, arg2);
    }

    public static MutableComponent text(final String key, final Object arg0, final Object arg1,
                                        final Object arg2, final Object arg3) {
        return Component.translatable(PREFIX + key, arg0, arg1, arg2, arg3);
    }

    /**
     * Construye el nombre d/ key que se pasa a {@code Component.translatable}.
     * Útil para tests y para los pocos sitios donde NO se quiere construir el
     * {@link Component} aquí (ej. logging).
     */
    public static String key(final String suffix) {
        return PREFIX + suffix;
    }
}