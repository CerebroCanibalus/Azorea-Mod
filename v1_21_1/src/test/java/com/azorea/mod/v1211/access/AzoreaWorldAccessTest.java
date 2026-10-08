// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.access;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests para {@link AzoreaWorldAccess} (ver AGENTS.md § F10, decisión 2026-10-04).
 *
 * <p>Cubren los 5 puntos que fijó el General:
 * <ul>
 *   <li>(c) <b>todo</b> mundo, antiguo o nuevo, registra <b>automáticamente</b>;</li>
 *   <li>(d) <b>no es whitelist</b>: una ID desconocida entra; si la borras, <b>se re-registra</b>;</li>
 *   <li>la excepción manual ({@code blocked}) es lo único que <b>sí</b> bloquea;</li>
 *   <li>detecta <b>duplicados aparentes</b> por nombre, pero <b>no los rechaza</b>.</li>
 * </ul>
 *
 * <p>Sin MC ni red ⇒ siempre corren.
 */
class AzoreaWorldAccessTest {

    private static final String ID_A = "AZ-AAAAAA-AAAAAA-AAAAAA-AAAAAA";
    private static final String ID_B = "AZ-BBBBBB-BBBBBB-BBBBBB-BBBBBB";

    private static final String KEY_A1 = "ed25519-public-key-A1";
    private static final String KEY_A2 = "ed25519-public-key-A2";
    private static final String KEY_B = "ed25519-public-key-B";

    @TempDir
    Path temp;

    private Path dir() {
        return temp.resolve("azorea");
    }

    private AzoreaWorldAccess world() {
        return new AzoreaWorldAccess(dir());
    }

    // ===== (c)+(d): auto-registro, sin whitelist =====

    @Test
    @DisplayName("(c) mundo sin ficheros ⇒ arranca vacío SIN error (mundos antiguos, automático)")
    void loadSinFicherosNoFalla() {
        final AzoreaWorldAccess access = world().load();

        assertEquals(0, access.size());
        assertFalse(access.isBlocked(ID_A));
        assertNull(access.identity(ID_A));
        // No se creó nada todavía: el I/O sólo ocurre al registrar/guardar.
        assertFalse(Files.exists(dir().resolve(AzoreaWorldAccess.IDENTITIES_FILE)));
    }

    @Test
    @DisplayName("(d) ID desconocida ⇒ se REGISTRA y entra; nunca se rechaza por lista previa")
    void idDesconocidaSeRegistra() {
        final AzoreaWorldAccess.RegisterResult r =
                world().load().register(ID_A, "Die_Beria", KEY_A1, "x25519", "hw", 1000L);

        assertEquals(AzoreaWorldAccess.Status.NEW, r.status());
        assertTrue(r.accepted(), "no es whitelist: una ID nueva debe aceptarse");
        assertNotNull(r.identity());
        assertEquals(ID_A, r.identity().azoreaId);
        assertEquals(1000L, r.identity().firstSeenEpochSec);
        assertNull(r.previousOwner(), "nadie tenía ese nombre");
    }

    @Test
    @DisplayName("(d) borras el registro ⇒ se vuelve a registrar SOLO (⊘ lockout)")
    void borrarElRegistroNoBloquea() {
        final AzoreaWorldAccess access = world().load();
        access.register(ID_A, "Die_Beria", KEY_A1, "x", "h", 1000L);
        assertEquals(1, access.size());

        access.forget(ID_A);
        assertEquals(0, access.size());

        final AzoreaWorldAccess.RegisterResult again =
                access.register(ID_A, "Die_Beria", KEY_A1, "x", "h", 2000L);

        assertEquals(AzoreaWorldAccess.Status.NEW, again.status());
        assertTrue(again.accepted(), "borrar tu identidad NO debe impedirte entrar");
        assertEquals(1, access.size());
    }

    // ===== Registro / round-trip =====

    @Test
    @DisplayName("misma id + misma clave + mismo nombre ⇒ UPDATED, no duplica la fila")
    void mismaIdentidadRefresca() {
        final AzoreaWorldAccess access = world().load();
        access.register(ID_A, "Die_Beria", KEY_A1, "x", "h", 1000L);
        final AzoreaWorldAccess.RegisterResult second =
                access.register(ID_A, "Die_Beria", KEY_A1, "x", "h", 2000L);

        assertEquals(AzoreaWorldAccess.Status.UPDATED, second.status());
        assertTrue(second.accepted());
        assertEquals(1, access.size(), "no debe duplicar la fila");
        assertEquals(1000L, second.identity().firstSeenEpochSec, "firstSeen no cambia");
        assertEquals(2000L, second.identity().lastSeenEpochSec, "lastSeen sí se refresca");
        assertEquals(List.of("Die_Beria"), second.identity().names);
    }

    @Test
    @DisplayName("id existencia que cambia de nombre ⇒ se añade al histórico, sin flag")
    void cambioDeNombrePropioNoEsDuplicado() {
        final AzoreaWorldAccess access = world().load();
        access.register(ID_A, "Die_Beria", KEY_A1, "x", "h", 1000L);
        final AzoreaWorldAccess.RegisterResult renamed =
                access.register(ID_A, "DieBeriaNuevo", KEY_A1, "x", "h", 2000L);

        assertEquals(AzoreaWorldAccess.Status.UPDATED, renamed.status());
        assertTrue(renamed.identity().names.contains("Die_Beria"));
        assertTrue(renamed.identity().names.contains("DieBeriaNuevo"));
        assertTrue(renamed.identity().flags.isEmpty(), "renombrarse no es sospechoso");
    }

    @Test
    @DisplayName("guardar ⇒ cargar devuelve lo mismo (round-trip)")
    void roundTripPersistencia() {
        world().load().register(ID_A, "Die_Beria", KEY_A1, "x25519", "hw", 1000L);
        final AzoreaWorldAccess reloaded = world().load();

        assertEquals(1, reloaded.size());
        final AzoreaWorldAccess.Identity id = reloaded.identity(ID_A);
        assertNotNull(id);
        assertEquals("Die_Beria", id.displayName);
        assertEquals(KEY_A1, id.ed25519Pub);
        assertEquals("x25519", id.x25519Pub);
        assertEquals("hw", id.hwCommit);
        assertEquals(List.of("Die_Beria"), id.names);
        assertEquals(1000L, id.firstSeenEpochSec);
    }

    @Test
    @DisplayName("identities.json corrupto ⇒ se empieza vacío y el juego NO se cae")
    void ficheroCorrompidoNoRevienta() throws Exception {
        Files.createDirectories(dir());
        Files.writeString(dir().resolve(AzoreaWorldAccess.IDENTITIES_FILE),
                "{ esto no es json ,,,,", StandardCharsets.UTF_8);

        final AzoreaWorldAccess access = world().load();

        assertEquals(0, access.size(), "arranca vacío en vez de lanzar");
        // Y sigue pudiendo registrar — el mundo queda operativo.
        assertTrue(access.register(ID_A, "Die_Beria", KEY_A1, "x", "h", 1L).accepted());
    }

    // ===== Detección de duplicados aparentes (sin rechazar) =====

    @Test
    @DisplayName("nombre ya de otra ID ⇒ NAME_TAKEN + flag + dueño previo, PERO se registra")
    void nombreAjenoregistraIgual() {
        final AzoreaWorldAccess access = world().load();
        access.register(ID_A, "Die_Beria", KEY_A1, "x", "h", 1000L);

        final AzoreaWorldAccess.RegisterResult impostor =
                access.register(ID_B, "Die_Beria", KEY_B, "x", "h", 2000L);

        assertEquals(AzoreaWorldAccess.Status.NAME_TAKEN, impostor.status());
        assertTrue(impostor.accepted(), "NO es whitelist: debe registrarse igualmente");
        assertEquals(ID_A, impostor.previousOwner(), "hay que poder ver de quién copió el nombre");
        assertTrue(impostor.identity().flags.contains(AzoreaWorldAccess.FLAG_NAME_REASSIGNED));
        assertEquals(2, access.size(), "ambas IDs quedan registradas");
    }

    @Test
    @DisplayName("id existente adopta el nombre de otra ⇒ también flagged (impostor histórico)")
    void idExistenteAdoptaNombreAjeno() {
        final AzoreaWorldAccess access = world().load();
        access.register(ID_A, "Die_Beria", KEY_A1, "x", "h", 1000L);
        access.register(ID_B, "OtroNombre", KEY_B, "x", "h", 1000L);

        final AzoreaWorldAccess.RegisterResult r =
                access.register(ID_B, "Die_Beria", KEY_B, "x", "h", 2000L);

        assertEquals(AzoreaWorldAccess.Status.NAME_TAKEN, r.status());
        assertTrue(r.accepted());
        assertEquals(ID_A, r.previousOwner());
        assertTrue(r.identity().flags.contains(AzoreaWorldAccess.FLAG_NAME_REASSIGNED));
    }

    @Test
    @DisplayName("mismo nombre, distinto mayúsculas ⇒ sigue detectando (nombres case-insensitive)")
    void nombresCaseInsensitive() {
        final AzoreaWorldAccess access = world().load();
        access.register(ID_A, "Die_Beria", KEY_A1, "x", "h", 1000L);

        final AzoreaWorldAccess.RegisterResult r =
                access.register(ID_B, "die_beria", KEY_B, "x", "h", 2000L);

        assertEquals(AzoreaWorldAccess.Status.NAME_TAKEN, r.status());
        assertEquals(ID_A, r.previousOwner());
    }

    @Test
    @DisplayName("misma ID con OTRA clave pública ⇒ KEY_MISMATCH + flag (DA-8: la clave deriva la ID)")
    void mismaIdOtraClave() {
        final AzoreaWorldAccess access = world().load();
        access.register(ID_A, "Die_Beria", KEY_A1, "x", "h", 1000L);

        final AzoreaWorldAccess.RegisterResult hostile =
                access.register(ID_A, "Die_Beria", KEY_A2, "x", "h", 2000L);

        assertEquals(AzoreaWorldAccess.Status.KEY_MISMATCH, hostile.status());
        assertFalse(hostile.accepted(), "ése SÍ se rechaza: reclama una ID con otra clave");
        assertTrue(hostile.identity().flags.contains(AzoreaWorldAccess.FLAG_KEY_CHANGED));
        assertEquals(KEY_A1, hostile.identity().ed25519Pub, "la clave original no se pisa");
    }

    // ===== La excepción manual: el botón de ECHAR =====

    @Test
    @DisplayName("block ⇒ BLOCKED, no se registra, y NO se auto-re-registra (≠ al registro)")
    void blockNoSeAutoRegistra() {
        final AzoreaWorldAccess access = world().load();
        access.register(ID_A, "Malo", KEY_A1, "x", "h", 1000L);

        assertTrue(access.block(ID_A, "rompió todo", 2000L));
        assertEquals(0, access.size(), "la excepción manual sí retira");
        assertTrue(access.isBlocked(ID_A));

        final AzoreaWorldAccess.RegisterResult again =
                access.register(ID_A, "Malo", KEY_A1, "x", "h", 3000L);

        assertEquals(AzoreaWorldAccess.Status.BLOCKED, again.status());
        assertFalse(again.accepted());
        assertNull(again.identity());
        assertEquals(0, access.size(), "aquí SÍ es exclusivo: para eso existe blocked");
    }

    @Test
    @DisplayName("unblock ⇒ la identidad vuelve a auto-registrarse en su siguiente entrada")
    void unblockDevuelveElAcceso() {
        final AzoreaWorldAccess access = world().load();
        access.block(ID_A, "un error", 1000L);
        assertTrue(access.isBlocked(ID_A));

        assertTrue(access.unblock(ID_A));
        assertFalse(access.isBlocked(ID_A));

        final AzoreaWorldAccess.RegisterResult r =
                access.register(ID_A, "Die_Beria", KEY_A1, "x", "h", 2000L);

        assertEquals(AzoreaWorldAccess.Status.NEW, r.status());
        assertTrue(r.accepted());
    }

    @Test
    @DisplayName("block persiste al recargar; unblock también")
    void bloqueosPersisten() {
        world().load().block(ID_A, "nota", 1000L);

        final AzoreaWorldAccess reloaded = world().load();
        assertTrue(reloaded.isBlocked(ID_A));
        assertEquals(List.of(ID_A), List.copyOf(reloaded.blockedIds()));

        reloaded.unblock(ID_A);
        assertFalse(world().load().isBlocked(ID_A));
    }

    @Test
    @DisplayName("block de una ID ya bloqueada o en blanco ⇒ false, sin tocar nada")
    void blockIdempotente() {
        final AzoreaWorldAccess access = world().load();
        assertFalse(access.block(null, "n", 1L));
        assertFalse(access.block("   ", "n", 1L));
        assertTrue(access.block(ID_A, "n", 1L));
        assertFalse(access.block(ID_A, "otra razón", 2L), "ya estaba bloqueada");
        assertFalse(access.unblock(ID_B), "nunca estuvo bloqueada");
    }

    @Test
    @DisplayName("azoreaId en blanco al registrar ⇒ IllegalArgumentException (input validado)")
    void registerValidaInput() {
        final AzoreaWorldAccess access = world().load();
        assertThrows(IllegalArgumentException.class,
                () -> access.register(null, "x", KEY_A1, "x", "h", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> access.register("  ", "x", KEY_A1, "x", "h", 1L));
    }

    // ===== (e) el selector vive EN EL MUNDO =====

    @Test
    @DisplayName("mundo nuevo o antiguo ⇒ PREMIUM por defecto (compatible con lo ya existente)")
    void modoPorDefectoEsPremium() {
        assertEquals(AzoreaAccessState.Mode.PREMIUM, world().load().mode());
    }

    @Test
    @DisplayName("setMode(no-premium) ⇒ persiste al recargar: el modo es propiedad del MUNDO")
    void modoPersiste() {
        final AzoreaWorldAccess w = world().load();

        assertTrue(w.setMode(AzoreaAccessState.Mode.NO_PREMIUM));
        assertEquals(AzoreaAccessState.Mode.NO_PREMIUM, world().load().mode());

        assertTrue(w.setMode(AzoreaAccessState.Mode.PREMIUM));
        assertEquals(AzoreaAccessState.Mode.PREMIUM, world().load().mode());
    }

    @Test
    @DisplayName("setMode al mismo modo ⇒ false y sin I/O (no se reescribe por deporte)")
    void modoIdempotente() {
        final AzoreaWorldAccess w = world().load();

        assertFalse(w.setMode(AzoreaAccessState.Mode.PREMIUM), "ya estaba en premium");
        assertTrue(w.setMode(AzoreaAccessState.Mode.NO_PREMIUM));
        assertFalse(w.setMode(AzoreaAccessState.Mode.NO_PREMIUM), "ya estaba en no-premium");
    }

    @Test
    @DisplayName("access.json corrupto o con valor desconocido ⇒ PREMIUM (fallo seguro)")
    void modoCorruptoFallaASeguro() throws Exception {
        Files.createDirectories(dir());
        final Path accessFile = dir().resolve(AzoreaWorldAccess.ACCESS_FILE);

        Files.writeString(accessFile, "%%%basura que no es json%%%", StandardCharsets.UTF_8);
        assertEquals(AzoreaAccessState.Mode.PREMIUM, world().load().mode(),
                "un fichero ilegible no debe activar el gate");

        Files.writeString(accessFile, "{\"version\":1,\"mode\":\"cosa-rara\"}",
                StandardCharsets.UTF_8);
        assertEquals(AzoreaAccessState.Mode.PREMIUM, world().load().mode(),
                "un modo que no entendemos es premium, no no-premium");
    }

    @Test
    @DisplayName("azoreaDir(<mundo>) ⇒ <mundo>/azorea (la ruta de producción)")
    void rutaDeProduccion() {
        assertEquals(Path.of("mundo", "azorea"), AzoreaWorldAccess.azoreaDir(Path.of("mundo")));
    }
}
