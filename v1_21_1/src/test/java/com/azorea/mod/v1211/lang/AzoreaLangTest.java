// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.lang;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * § Garantiza que las dos localizaciones (en + es) tienen las <b>mismas claves</b>.
 *
 * <p>Las pantallas de Azorea usan {@code Component.translatable("azorea.…")} y leen
 * del idioma q/ MC tenga activo, con <b>fallback al inglés</b> si falta la clave
 * ({@link net.minecraft.client.resources.language.I18n} lo hace por defecto).
 *
 * <p>Este test recorre los dos .json y falla si:
 * <ul>
 *   <li>alguna clave está sólo en uno de los dos;</li>
 *   <li>los placeholders ({@code {0}}, {@code {1}}, …) no coinciden entre ambas versiones;</li>
 *   <li>los valores tienen forma inválida (ej. literal vacío, codes raros).</li>
 * </ul>
 *
 * <p>Para añadir una clave: copia el mismo string en ambos .json <i>antes</i> de usarla
 * en el código. Si lo haces al revés, este test falla — y eso es bueno: significa q/
 * alguien se saltó la traducción.
 */
class AzoreaLangTest {

    private static final String EN_US = "/assets/azorea/lang/en_us.json";
    private static final String ES_ES = "/assets/azorea/lang/es_es.json";

    private static JsonObject en;
    private static JsonObject es;

    @BeforeAll
    static void load() throws Exception {
        en = readJson(EN_US);
        es = readJson(ES_ES);
    }

    @Test
    @DisplayName("los dos .json existen y son JSON válido")
    void bothFilesExist() {
        assertNotNull(en, "en_us.json no encontrado en classpath");
        assertNotNull(es, "es_es.json no encontrado en classpath");
    }

    @Test
    @DisplayName("en_us y es_es tienen exactamente las mismas claves")
    void sameKeys() {
        final Set<String> enKeys = en.keySet();
        final Set<String> esKeys = es.keySet();

        final Set<String> onlyInEn = new TreeSet<>(enKeys);
        onlyInEn.removeAll(esKeys);
        final Set<String> onlyInEs = new TreeSet<>(esKeys);
        onlyInEs.removeAll(enKeys);

        assertTrue(onlyInEn.isEmpty(),
                "claves sólo en en_us (sin equivalente en es_es): " + onlyInEn);
        assertTrue(onlyInEs.isEmpty(),
                "claves sólo en es_es (sin equivalente en en_us): " + onlyInEs);
        assertEquals(enKeys.size(), esKeys.size(),
                "en_us tiene " + enKeys.size() + " claves, es_es tiene " + esKeys.size());
    }

    @Test
    @DisplayName("los placeholders {0}/{1}/… coinciden entre ambas lenguas")
    void placeholdersMatch() {
        final Set<String> mismatches = new TreeSet<>();
        for (final String key : en.keySet()) {
            final String enVal = en.get(key).getAsString();
            final String esVal = es.get(key).getAsString();
            if (placeholders(enVal).equals(placeholders(esVal))) {
                continue;
            }
            mismatches.add(key + " :: en=" + placeholders(enVal)
                    + " es=" + placeholders(esVal));
        }
        assertTrue(mismatches.isEmpty(),
                "placeholders distintos entre en y es:\n  " + String.join("\n  ", mismatches));
    }

    @Test
    @DisplayName("ningún valor está vacío")
    void noEmptyValues() {
        final Set<String> empty = new TreeSet<>();
        for (final String key : en.keySet()) {
            if (en.get(key).getAsString().isBlank()) {
                empty.add("en :: " + key);
            }
            if (es.get(key).getAsString().isBlank()) {
                empty.add("es :: " + key);
            }
        }
        assertTrue(empty.isEmpty(), "valores vacíos:\n  " + String.join("\n  ", empty));
    }

    @Test
    @DisplayName("todas las claves empiezan por 'azorea.'")
    void namespacePrefix() {
        final Set<String> bad = new TreeSet<>();
        for (final String key : en.keySet()) {
            if (!key.startsWith("azorea.")) {
                bad.add(key);
            }
        }
        assertTrue(bad.isEmpty(),
                "claves sin el prefijo 'azorea.':\n  " + String.join("\n  ", bad));
    }

    // ===== Utilidades =====

    private static JsonObject readJson(final String path) throws Exception {
        final InputStream in = AzoreaLangTest.class.getResourceAsStream(path);
        assertNotNull(in, "no se pudo abrir: " + path);
        try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    /** Devuelve el conjunto ordenado d/ placeholders numéricos q/ aparecen en un valor. */
    private static Set<String> placeholders(final String value) {
        final Set<String> out = new HashSet<>();
        int i = 0;
        while ((i = value.indexOf('{', i)) != -1) {
            final int j = value.indexOf('}', i + 1);
            if (j < 0) {
                break;
            }
            out.add(value.substring(i + 1, j));
            i = j + 1;
        }
        return out;
    }
}