// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211.access;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Registro de identidades <b>por mundo</b> (ver AGENTS.md § F10, decisión del General 2026-10-04).
 *
 * <p><b>§ Qué es — y qué NO es.</b> Es un <b>evitador de impostores</b>, no una whitelist:
 * <ul>
 *   <li>Una {@code azoreaId} desconocida <b>se registra y entra</b> — nunca se rechaza
 *       por no estar en una lista previa. ⇒ <b>sin lockout</b>, <b>sin migración de mundos
 *       antiguos</b>, y el host <b>no puede bloquearse a sí mismo</b>.</li>
 *   <li>Lo que impide es <b>hacerse pasar por otro</b>: la clave Ed25519 deriva la
 *       {@code azoreaId} (DA-8), así que no puedes firmar por un tercero. Y si usas un
 *       <b>nombre</b> que ya pertenece a otra identidad, queda <em>flagged</em> como
 *       duplicado aparente — <b>se registra igualmente</b>, pero se ve.</li>
 *   <li>Si borras tu identidad, <b>no pasa nada</b>: el mod la vuelve a registrar.</li>
 * </ul>
 *
 * <p><b>§ La excepción manual</b> ({@code blocked.json}) es el único caso donde la
 * configuración interviene: es el botón de <b>ECHAR</b>. A diferencia del registro,
 * <b>no</b> se auto-re-registra — porque su propósito es precisamente que un ID no vuelva.
 *
 * <p><b>§ Dónde vive.</b> {@code <mundo>/azorea/{identities,blocked}.json} — <b>dentro del
 * mundo</b>, no en config. El requisito explícito fue <i>«preservar su mundo es lo más
 * importante»</i>: si viviera en {@code config/azorea.toml} y se pierde la config, el host
 * se quedaría sin acceso a su propio mundo. Además es JSON legible, editable a mano.
 *
 * <p><b>§ Hilos.</b> Toda mutación va en el hilo principal del server. El handler de
 * payloads de configuration corre en {@code MAIN} por defecto (doc NeoForge 1.21.1), así
 * que {@link #register} no necesita sincronización. El I/O es un JSON pequeño ⇒ barato.
 *
 * <p><b>§ mundos antiguos.</b> Si los ficheros no existen, se arranca vacío sin error —
 * de ahí que un mundo creado antes de F10 funcione <b>automáticamente</b>.
 */
public final class AzoreaWorldAccess {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzoreaWorldAccess.class);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Directorio de Azorea dentro del mundo. */
    public static final String DIR_NAME = "azorea";

    public static final String IDENTITIES_FILE = "identities.json";
    public static final String BLOCKED_FILE = "blocked.json";
    /** Modo de acceso del mundo: {@code premium} (Mojang) o {@code no-premium} (Azorea ID). */
    public static final String ACCESS_FILE = "access.json";

    /** Versión del esquema; subir si cambia la forma de los ficheros. */
    private static final int SCHEMA_VERSION = 1;

    // ===== Flags de duplicado aparente =====

    /** El nombre ya estaba ligado a otra {@code azoreaId}. */
    public static final String FLAG_NAME_REASSIGNED = "nombre_reasignado";

    /** La misma {@code azoreaId} apareció con otra clave pública. */
    public static final String FLAG_KEY_CHANGED = "clave_cambiada";

    // ===== Resultado de register() =====

    public enum Status {
        /** Identidad nueva: registrada y aceptada. */
        NEW,
        /** Misma identidad (id + clave + nombre): sólo se refresca {@code lastSeen}. */
        UPDATED,
        /** Registrada, pero su nombre ya pertenecía a otra identidad ⇒ duplicado aparente. */
        NAME_TAKEN,
        /** Misma {@code azoreaId} con otra clave — hostil o identidad regenerada fuera de banda. */
        KEY_MISMATCH,
        /** ID en {@code blocked.json}: no se registra ni entra. El «caso necesario». */
        BLOCKED
    }

    /**
     * @param status        ver {@link Status}
     * @param identity      la identidad registrada ({@code null} sólo si {@link Status#BLOCKED})
     * @param previousOwner {@code azoreaId} a la que pertenecía previamente el nombre
     *                      ({@code null} si nadie la tenía)
     */
    public record RegisterResult(Status status, Identity identity, String previousOwner) {

        public boolean accepted() {
            return status != Status.BLOCKED && status != Status.KEY_MISMATCH;
        }
    }

    // ===== El registro =====

    /**
     * Una identidad observada en este mundo. Campos mutables a propósito: es una fila de
     * registro que {@link #register} actualiza ({@code lastSeen}, {@code names}, {@code flags}).
     */
    public static final class Identity {
        public String azoreaId;
        public String displayName;
        public String ed25519Pub;
        public String x25519Pub;
        public String hwCommit;
        /** Todos los nombres de MC vistos para esta identidad (deduplicados, case-insensitive). */
        public List<String> names = new ArrayList<>();
        public long firstSeenEpochSec;
        public long lastSeenEpochSec;
        public List<String> flags = new ArrayList<>();
    }

    private static final class IdentityStorage {
        int version = SCHEMA_VERSION;
        List<Identity> identities = new ArrayList<>();
    }

    private static final class BlockedEntry {
        String azoreaId;
        String note;
        long sinceEpochSec;
    }

    private static final class BlockedStorage {
        int version = SCHEMA_VERSION;
        List<BlockedEntry> blocked = new ArrayList<>();
    }

    /** Persistencia del modo. Vive <b>en el mundo</b> (decisión (e): el selector va ahí). */
    private static final class AccessStorage {
        int version = SCHEMA_VERSION;
        String mode;
    }

    private final Path azoreaDir;
    private final Map<String, Identity> byId = new LinkedHashMap<>();
    private final Map<String, BlockedEntry> blocked = new LinkedHashMap<>();
    private AzoreaAccessState.Mode mode = AzoreaAccessState.Mode.PREMIUM;

    /**
     * @param azoreaDir directorio {@code <mundo>/azorea}. Ver {@link #azoreaDir(Path)}.
     */
    public AzoreaWorldAccess(final Path azoreaDir) {
        this.azoreaDir = Objects.requireNonNull(azoreaDir, "azoreaDir");
    }

    /** {@code <mundo>}/azorea — para producción, desde la ruta del mundo. */
    public static Path azoreaDir(final Path worldRoot) {
        return worldRoot.resolve(DIR_NAME);
    }

    public Path directory() {
        return azoreaDir;
    }

    // ===== Ciclo de vida =====

    /**
     * Carga ambos ficheros. Si no existen (mundo antiguo o recién creado) arranca vacío
     * <b>sin error</b> — es lo que hace que el registro sea automático en todo mundo.
     *
     * @return {@code this}, para encadenar
     */
    public AzoreaWorldAccess load() {
        byId.clear();
        blocked.clear();
        // Mientras no diga lo contrario, un mundo es premium (lo de siempre).
        mode = AzoreaAccessState.Mode.PREMIUM;

        final Path idFile = azoreaDir.resolve(IDENTITIES_FILE);
        if (Files.isRegularFile(idFile)) {
            try (Reader r = Files.newBufferedReader(idFile, StandardCharsets.UTF_8)) {
                final IdentityStorage dto = GSON.fromJson(r, IdentityStorage.class);
                if (dto != null && dto.identities != null) {
                    for (final Identity identity : dto.identities) {
                        if (identity != null && !isBlank(identity.azoreaId)) {
                            // Defensive: normaliza lists nulas que puedan venir de un JSON a mano.
                            if (identity.names == null) identity.names = new ArrayList<>();
                            if (identity.flags == null) identity.flags = new ArrayList<>();
                            byId.put(identity.azoreaId, identity);
                        }
                    }
                }
                LOGGER.info("World access: {} identidades desde {}", byId.size(), idFile);
            } catch (final Exception e) {
                // § Preservar el mundo > insistir: un identities.json corrupto NO debe
                //   impedir hostear. Se arranca vacío y el registro lo rellena solo.
                LOGGER.error("World access: leyendo {} — se empieza vacío: {}",
                        idFile, e.getMessage(), e);
                byId.clear();
            }
        }

        final Path blkFile = azoreaDir.resolve(BLOCKED_FILE);
        if (Files.isRegularFile(blkFile)) {
            try (Reader r = Files.newBufferedReader(blkFile, StandardCharsets.UTF_8)) {
                final BlockedStorage dto = GSON.fromJson(r, BlockedStorage.class);
                if (dto != null && dto.blocked != null) {
                    for (final BlockedEntry entry : dto.blocked) {
                        if (entry != null && !isBlank(entry.azoreaId)) {
                            blocked.put(entry.azoreaId, entry);
                        }
                    }
                }
                if (!blocked.isEmpty()) {
                    LOGGER.info("World access: {} IDs bloqueadas (excepción manual)", blocked.size());
                }
            } catch (final Exception e) {
                LOGGER.error("World access: leyendo {} — bloqueos ignorados: {}",
                        blkFile, e.getMessage(), e);
                blocked.clear();
            }
        }

        final Path accessFile = azoreaDir.resolve(ACCESS_FILE);
        if (Files.isRegularFile(accessFile)) {
            try (Reader r = Files.newBufferedReader(accessFile, StandardCharsets.UTF_8)) {
                final AccessStorage dto = GSON.fromJson(r, AccessStorage.class);
                if (dto != null && dto.mode != null) {
                    mode = fromStorage(dto.mode);
                }
            } catch (final Exception e) {
                LOGGER.error("World access: leyendo {} — se queda en premium: {}",
                        accessFile, e.getMessage(), e);
                mode = AzoreaAccessState.Mode.PREMIUM;
            }
        }
        return this;
    }

    /**
     * Modo de acceso de este mundo. Arranca {@code premium} en todo mundo nuevo o antiguo
     * que no diga lo contrario — es el estado compatible con lo que ya existe.
     */
    public AzoreaAccessState.Mode mode() {
        return mode;
    }

    /**
     * Cambia el modo y lo persiste <b>en el mundo</b>.
     *
     * @return {@code true} si el modo cambió de verdad
     */
    public boolean setMode(final AzoreaAccessState.Mode newMode) {
        final AzoreaAccessState.Mode effective =
                newMode == null ? AzoreaAccessState.Mode.PREMIUM : newMode;
        if (mode == effective) {
            return false;
        }
        mode = effective;
        save();
        LOGGER.info("World access: modo de acceso → {}", mode);
        return true;
    }

    private static AzoreaAccessState.Mode fromStorage(final String raw) {
        if ("no-premium".equalsIgnoreCase(raw)) {
            return AzoreaAccessState.Mode.NO_PREMIUM;
        }
        // Cualquier otra cosa (premium, desconocida, sucia) ⇒ premium: fallo seguro.
        return AzoreaAccessState.Mode.PREMIUM;
    }

    private static String toStorage(final AzoreaAccessState.Mode m) {
        return m == AzoreaAccessState.Mode.NO_PREMIUM ? "no-premium" : "premium";
    }

    /** Guarda ambos ficheros. Un fallo de I/O se loguea pero nunca revienta el juego. */
    public void save() {
        try {
            Files.createDirectories(azoreaDir);

            final IdentityStorage ids = new IdentityStorage();
            ids.identities.addAll(byId.values());
            write(azoreaDir.resolve(IDENTITIES_FILE), ids);

            // § BUG hallado por el test «block persiste al recargar»: si blocked queda
            //   VACÍO y sólo "no se escribe", el blocked.json anterior SOBREVIVE ⇒ tras
            //   unblock() + recarga la ID sigue bloqueada. Hay que BORRAR el fichero.
            final Path blkFile = azoreaDir.resolve(BLOCKED_FILE);
            if (!blocked.isEmpty()) {
                final BlockedStorage blk = new BlockedStorage();
                blk.blocked.addAll(blocked.values());
                write(blkFile, blk);
            } else if (Files.exists(blkFile)) {
                Files.delete(blkFile);
            }

            // El modo SIEMPRE queda escrito, incluso cuando es el de fábrica: que el estado
            // del mundo sea explícito y no haya que adivinarlo.
            final AccessStorage access = new AccessStorage();
            access.mode = toStorage(mode);
            write(azoreaDir.resolve(ACCESS_FILE), access);
        } catch (final Exception e) {
            LOGGER.error("World access: guardando en {}: {}", azoreaDir, e.getMessage(), e);
        }
    }

    private static void write(final Path file, final Object dto) throws IOException {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(dto, w);
        }
    }

    // ===== Registro =====

    /**
     * Registra (o refresca) una identidad en este mundo.
     *
     * <p>Auto-registro universal: una identidad desconocida <b>entra</b>. Sólo se rechaza
     * si está en {@code blocked.json} ({@link Status#BLOCKED}) o si reclama una
     * {@code azoreaId} existente con <b>otra clave</b> ({@link Status#KEY_MISMATCH}).
     *
     * @param azoreaId       identidad auto-certificante (DA-8)
     * @param displayName    nombre de MC declarado
     * @param ed25519Pub     clave Ed25519 pública (base64) — con ella se firma
     * @param x25519Pub      clave X25519 pública (base64)
     * @param hwCommit       huella de hardware (hex)
     * @param nowEpochSec    instante actual en epoch seconds
     */
    public RegisterResult register(final String azoreaId, final String displayName,
                                   final String ed25519Pub, final String x25519Pub,
                                   final String hwCommit, final long nowEpochSec) {
        if (isBlank(azoreaId)) {
            throw new IllegalArgumentException("azoreaId en blanco");
        }

        // (0) Excepción manual — el único rechazo de verdad.
        if (blocked.containsKey(azoreaId)) {
            return new RegisterResult(Status.BLOCKED, null, null);
        }

        final Identity existing = byId.get(azoreaId);
        if (existing != null) {
            // (1) Misma id, otra clave ⇒ hostil o identidad regenerada fuera de banda.
            //     DA-8 dice que la clave deriva la id, así que esto NO debería ocurrir:
            //     si ocurre, se marca y NO se acepta.
            if (!Objects.equals(existing.ed25519Pub, ed25519Pub)) {
                addFlag(existing, FLAG_KEY_CHANGED);
                existing.lastSeenEpochSec = nowEpochSec;
                save();
                LOGGER.warn("World access: id {} apareció con otra clave pública ⇒ flagged [{}]",
                        azoreaId, FLAG_KEY_CHANGED);
                return new RegisterResult(Status.KEY_MISMATCH, existing, null);
            }

            // (2) Es la misma identidad: refrescar. PERO si el nombre que trae ahora ya
            //     pertenece a OTRA identidad, eso también es duplicado aparente — un
            //     impostor suele adoptar el nombre histórico de la víctima.
            existing.lastSeenEpochSec = nowEpochSec;
            final boolean nameAdded = addName(existing, displayName);

            if (nameAdded) {
                final String owner = findOwnerByName(displayName, azoreaId);
                if (owner != null) {
                    addFlag(existing, FLAG_NAME_REASSIGNED);
                    save();
                    LOGGER.warn("World access: {} ya existía y ahora usa el nombre '{}' "
                                    + "que pertenecía a {} ⇒ duplicado aparente [{}]",
                            azoreaId, displayName, owner, FLAG_NAME_REASSIGNED);
                    return new RegisterResult(Status.NAME_TAKEN, existing, owner);
                }
            }
            save();
            return new RegisterResult(Status.UPDATED, existing, null);
        }

        // (3) Identidad nueva. ¿El nombre ya era de alguien? ⇒ duplicado aparente.
        //     Se REGISTRA igualmente (no es whitelist) pero queda flagged y con dueño previo.
        final Identity fresh = new Identity();
        fresh.azoreaId = azoreaId;
        fresh.displayName = displayName;
        fresh.ed25519Pub = ed25519Pub;
        fresh.x25519Pub = x25519Pub;
        fresh.hwCommit = hwCommit;
        fresh.firstSeenEpochSec = nowEpochSec;
        fresh.lastSeenEpochSec = nowEpochSec;
        addName(fresh, displayName);

        final String previousOwner = findOwnerByName(displayName, azoreaId);
        if (previousOwner != null) {
            addFlag(fresh, FLAG_NAME_REASSIGNED);
            LOGGER.warn("World access: el nombre '{}' ya pertenecía a {} y ahora lo usa {} "
                            + "⇒ duplicado aparente [{}] (se registra, no se bloquea)",
                    displayName, previousOwner, azoreaId, FLAG_NAME_REASSIGNED);
        }

        byId.put(azoreaId, fresh);
        save();
        return new RegisterResult(previousOwner != null ? Status.NAME_TAKEN : Status.NEW,
                fresh, previousOwner);
    }

    /** Borra el registro de una identidad. Sólo afecta al historial: volverá a registrarse. */
    public void forget(final String azoreaId) {
        if (byId.remove(azoreaId) != null) {
            save();
        }
    }

    // ===== Excepción manual (el «caso necesario») =====

    /**
     * Bloquea una identidad. A diferencia del registro, <b>no</b> se auto-re-registra:
     * éste es el botón de ECHAR.
     *
     * @return {@code true} si era nueva
     */
    public boolean block(final String azoreaId, final String note, final long nowEpochSec) {
        if (isBlank(azoreaId) || blocked.containsKey(azoreaId)) {
            return false;
        }
        final BlockedEntry entry = new BlockedEntry();
        entry.azoreaId = azoreaId;
        entry.note = note;
        entry.sinceEpochSec = nowEpochSec;
        blocked.put(azoreaId, entry);
        byId.remove(azoreaId);
        save();
        return true;
    }

    /** Desbloquea. Tras esto la identidad volverá a auto-registrarse en su siguiente entrada. */
    public boolean unblock(final String azoreaId) {
        if (blocked.remove(azoreaId) == null) {
            return false;
        }
        save();
        return true;
    }

    public boolean isBlocked(final String azoreaId) {
        return !isBlank(azoreaId) && blocked.containsKey(azoreaId);
    }

    // ===== Consultas =====

    public Identity identity(final String azoreaId) {
        return azoreaId == null ? null : byId.get(azoreaId);
    }

    public Collection<Identity> identities() {
        return List.copyOf(byId.values());
    }

    public Collection<String> blockedIds() {
        return List.copyOf(blocked.keySet());
    }

    public int size() {
        return byId.size();
    }

    /** ¿A quién le pertenece ya este nombre en este mundo? ({@code null} si a nadie). */
    private String findOwnerByName(final String displayName, final String excludeId) {
        if (isBlank(displayName)) {
            return null;
        }
        final String needle = displayName.toLowerCase(Locale.ROOT);
        for (final Identity identity : byId.values()) {
            if (identity.azoreaId.equals(excludeId)) {
                continue;
            }
            if (identity.displayName != null
                    && identity.displayName.toLowerCase(Locale.ROOT).equals(needle)) {
                return identity.azoreaId;
            }
            for (final String seen : identity.names) {
                if (seen != null && seen.toLowerCase(Locale.ROOT).equals(needle)) {
                    return identity.azoreaId;
                }
            }
        }
        return null;
    }

    /** @return {@code true} si el nombre es nuevo para esta identidad */
    private static boolean addName(final Identity identity, final String displayName) {
        if (isBlank(displayName)) {
            return false;
        }
        final String needle = displayName.toLowerCase(Locale.ROOT);
        for (final String seen : identity.names) {
            if (seen != null && seen.toLowerCase(Locale.ROOT).equals(needle)) {
                return false;
            }
        }
        identity.names.add(displayName);
        // El nombre vigente va también en displayName para las consultas rápidas.
        identity.displayName = displayName;
        return true;
    }

    private static void addFlag(final Identity identity, final String flag) {
        if (!identity.flags.contains(flag)) {
            identity.flags.add(flag);
        }
    }

    private static boolean isBlank(final String s) {
        return s == null || s.isBlank();
    }
}
