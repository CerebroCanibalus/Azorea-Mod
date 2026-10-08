// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
package com.azorea.mod.v1211;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Azorea mod configuration (NeoForge ModConfigSpec).
 *
 * <p>Written to {@code config/azorea.toml} on first run. Sections are grouped
 * by concern: general toggles, identity, tracker/discovery, host defaults,
 * security, UI overlays, network optimization.
 *
 * <p>Convention:
 * <ul>
 *   <li>Booleans default to the safe/off value when in doubt.</li>
 *   <li>Numeric ranges are bounded to prevent abuse (DOS, OOM, etc.).</li>
 *   <li>Strings are validated (URL scheme, length, charset) at parse time.</li>
 *   <li>Each option carries a {@code .comment(...)} — that text ends up in
 *       the generated {@code azorea.toml} as inline docs. Keep them in sync
 *       with this file's javadoc.</li>
 * </ul>
 *
 * <p>Security defaults:
 * <ul>
 *   <li>{@code log_host_tokens} = false. NEVER log tokens (leak risk).</li>
 *   <li>{@code friends.allow_friend_request_from_non_friends} = false (default
 *       — friend requests are an explicit opt-in).</li>
 * </ul>
 */
public final class AzoreaConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ===== [general] =====
    public static final ModConfigSpec.BooleanValue GENERAL_DEBUG;
    public static final ModConfigSpec.BooleanValue GENERAL_VERBOSE_NETLOG;

    // ===== [identity] =====
    public static final ModConfigSpec.BooleanValue IDENTITY_AUTO_REG;
    public static final ModConfigSpec.BooleanValue IDENTITY_BOOTSTRAP_HOST;
    public static final ModConfigSpec.IntValue IDENTITY_HW_COMMIT_VERSION;

    // ===== [tracker] =====
    public static final ModConfigSpec.ConfigValue<List<? extends String>> TRACKER_URLS;
    public static final ModConfigSpec.IntValue TRACKER_DEFAULT_TTL_SECONDS;
    public static final ModConfigSpec.IntValue TRACKER_CONNECT_TIMEOUT_SECONDS;
    public static final ModConfigSpec.IntValue TRACKER_REQUEST_TIMEOUT_SECONDS;
    public static final ModConfigSpec.IntValue TRACKER_REFRESH_DIVISOR;
    public static final ModConfigSpec.IntValue TRACKER_LIST_MAX_RESULTS;
    public static final ModConfigSpec.BooleanValue TRACKER_EMBEDDED_ENABLED;
    public static final ModConfigSpec.IntValue TRACKER_EMBEDDED_PORT;
    public static final ModConfigSpec.IntValue TRACKER_EMBEDDED_PORT_MAX;
    public static final ModConfigSpec.BooleanValue TRACKER_EMBEDDED_RELAY_ENABLED;
    public static final ModConfigSpec.IntValue TRACKER_EMBEDDED_RELAY_PORT;

    // ===== [discovery] =====
    public static final ModConfigSpec.BooleanValue DISCOVERY_LAN_ENABLED;
    public static final ModConfigSpec.IntValue DISCOVERY_LAN_PORT;
    public static final ModConfigSpec.IntValue DISCOVERY_LAN_INTERVAL_SECONDS;
    public static final ModConfigSpec.IntValue DISCOVERY_LAN_TIMEOUT_SECONDS;
    public static final ModConfigSpec.BooleanValue DISCOVERY_PING_ON_CLICK;
    public static final ModConfigSpec.IntValue DISCOVERY_PING_TIMEOUT_MS;

    // ===== [host] =====
    public static final ModConfigSpec.IntValue HOST_DEFAULT_MAX_PLAYERS;
    public static final ModConfigSpec.IntValue HOST_DEFAULT_PORT;
    public static final ModConfigSpec.BooleanValue HOST_DEFAULT_PVP;
    public static final ModConfigSpec.BooleanValue HOST_DEFAULT_FLIGHT;
    public static final ModConfigSpec.IntValue HOST_DEFAULT_OP_LEVEL;

    // ===== [security] =====
    public static final ModConfigSpec.BooleanValue SECURITY_LOG_HOST_TOKENS;
    public static final ModConfigSpec.BooleanValue SECURITY_LOG_PUBLIC_IP;
    public static final ModConfigSpec.BooleanValue SECURITY_FRIEND_REQUESTS_FROM_NON_FRIENDS;
    public static final ModConfigSpec.IntValue SECURITY_INVITE_TTL_SECONDS;
    public static final ModConfigSpec.BooleanValue SECURITY_BLOCK_DUPLICATE_ID;
    public static final ModConfigSpec.IntValue SECURITY_MAX_INVITES_PER_DAY;

    // ===== [ui] =====
    public static final ModConfigSpec.BooleanValue UI_NAMETAG_OVERLAY;
    public static final ModConfigSpec.BooleanValue UI_NAMETAG_HIDE_ON_SNEAK;
    public static final ModConfigSpec.IntValue UI_NAMETAG_PING_GOOD_MS;
    public static final ModConfigSpec.IntValue UI_NAMETAG_PING_OK_MS;

    // ===== [net] =====
    public static final ModConfigSpec.BooleanValue NET_ENABLED;
    public static final ModConfigSpec.IntValue NET_COMPRESSION_LEVEL;

    static {
        // ============================================================
        // [general] — global toggles
        // ============================================================
        BUILDER.comment("Global toggles that don't fit anywhere else").push("general");

        GENERAL_DEBUG = BUILDER
                .comment("If true, enables extra debug logging (stack traces, verbose",
                        "AzoreaNetLog output, etc.). Default off to keep INFO logs clean.")
                .define("debug", false);

        GENERAL_VERBOSE_NETLOG = BUILDER
                .comment("Verbose AzoreaNetLog output. Spammy — only enable when debugging",
                        "connection issues. Set via -Dazorea.net.verbose=true too.")
                .define("verbose_netlog", false);

        BUILDER.pop();

        // ============================================================
        // [identity] — auto-registration of Azorea IDs in worlds
        // ============================================================
        BUILDER.comment("Azorea identity (Ed25519 + X25519 keys stored in <world>/azorea/)").push("identity");

        IDENTITY_AUTO_REG = BUILDER
                .comment("If true (default), new players joining a no-premium world are",
                        "auto-registered in <world>/azorea/identities.json. The General's",
                        "decision (2026-10-04): 'registro automático universal, manual sólo",
                        "por excepción'. If false, the operator must run /azorea friend add.",
                        "Ignored in premium mode (Microsoft auth is the source of truth).")
                .define("auto_register_in_no_premium_worlds", true);

        IDENTITY_BOOTSTRAP_HOST = BUILDER
                .comment("If true (default), when the host enters a no-premium world and the",
                        "access state is fresh, the mod adds the host's own Azorea ID to the",
                        "world's identities. This is what makes the first connection possible",
                        "without an explicit 'azorea friend add self' step.",
                        "Set false on shared/pack servers where you don't want the host",
                        "to bootstrap their own identity automatically.")
                .define("bootstrap_host_on_first_world_load", true);

        IDENTITY_HW_COMMIT_VERSION = BUILDER
                .comment("Version tag baked into the hw_commit field of azorea_id.",
                        "Bump it if you change the canonicalization algorithm — old IDs will",
                        "stop being valid and friends will see them as 'key mismatch'.",
                        "Default 1 — do not change unless you know what you're doing.")
                .defineInRange("hw_commit_version", 1, 1, 100);

        BUILDER.pop();

        // ============================================================
        // [tracker] — tracker endpoints, embedded tracker, timing
        // ============================================================
        BUILDER.comment("Tracker discovery, announce, refresh, embedded HTTP server").push("tracker");

        TRACKER_URLS = BUILDER
                .comment("List of base URLs of Azorea trackers (http/https). The mod announces",
                        "its hosted games to these and queries them to discover others.",
                        "Empty = mode no-op (mod loads but does no discovery).",
                        "DA-7 (SEAMLESS): if empty, the mod starts an embedded tracker on",
                        "localhost so singleplayer still works without any external infra.")
                .defineListAllowEmpty("urls", List.of(),
                        obj -> obj instanceof String s
                                && (s.startsWith("http://") || s.startsWith("https://")));

        TRACKER_DEFAULT_TTL_SECONDS = BUILDER
                .comment("Default announce TTL on trackers (seconds). Refresh fires every",
                        "ttl/refresh_divisor. Range: 60 (1min) to 3600 (1h).")
                .defineInRange("default_ttl_seconds", 300, 60, 3600);

        TRACKER_CONNECT_TIMEOUT_SECONDS = BUILDER
                .comment("TCP connect timeout to tracker (seconds). Hard cap to avoid",
                        "the UI hanging on a slow tracker.")
                .defineInRange("connect_timeout_seconds", 5, 1, 60);

        TRACKER_REQUEST_TIMEOUT_SECONDS = BUILDER
                .comment("HTTP request total timeout (seconds).")
                .defineInRange("request_timeout_seconds", 10, 1, 120);

        TRACKER_REFRESH_DIVISOR = BUILDER
                .comment("Refresh cadence = ttl / this. 2 = halfway through TTL (default).",
                        "3 = third (less load, more stale entries).")
                .defineInRange("refresh_divisor", 2, 1, 10);

        TRACKER_LIST_MAX_RESULTS = BUILDER
                .comment("Maximum games returned by a single /list request. Caps UI cost.")
                .defineInRange("list_max_results", 100, 1, 1000);

        TRACKER_EMBEDDED_ENABLED = BUILDER
                .comment("DA-7: start an embedded HTTP tracker on the local machine so the",
                        "mod works without any external infra. Bind on 127.0.0.1 only.",
                        "Disable if you run a real tracker on the same machine and don't",
                        "want two competing processes on the same port range.")
                .define("embedded_enabled", true);

        TRACKER_EMBEDDED_PORT = BUILDER
                .comment("Base port for the embedded tracker. Actual port is searched in",
                        "[port, port+embedded_port_max) until something is free.")
                .defineInRange("embedded_port", 8765, 1024, 65535);

        TRACKER_EMBEDDED_PORT_MAX = BUILDER
                .comment("How many ports above embedded_port to try before giving up.")
                .defineInRange("embedded_port_max", 100, 1, 1000);

        TRACKER_EMBEDDED_RELAY_ENABLED = BUILDER
                .comment("DA-7/F8.x: also start the embedded TCP relay (port+1) so direct",
                        "connections can fall back to it. Disable if relay isn't needed.")
                .define("embedded_relay_enabled", true);

        TRACKER_EMBEDDED_RELAY_PORT = BUILDER
                .comment("Embedded relay port. Defaults to embedded_port+1.")
                .defineInRange("embedded_relay_port", 8766, 1024, 65535);

        BUILDER.pop();

        // ============================================================
        // [discovery] — LAN mDNS/multicast + ping-on-click behavior
        // ============================================================
        BUILDER.comment("Discovery of hosts on the LAN and on remote trackers").push("discovery");

        DISCOVERY_LAN_ENABLED = BUILDER
                .comment("Broadcast Azorea's presence to the LAN (multicast 239.255.42.42:4446)",
                        "so peers on the same network see the host in 'Browse Games'.",
                        "DA-7/F7.5: enabled by default — this is what makes the host",
                        "appear on a friend's Browse without any external tracker.")
                .define("lan_enabled", true);

        DISCOVERY_LAN_PORT = BUILDER
                .comment("LAN multicast port.")
                .defineInRange("lan_port", 4446, 1024, 65535);

        DISCOVERY_LAN_INTERVAL_SECONDS = BUILDER
                .comment("How often to broadcast 'AZ_HELLO' on the LAN.")
                .defineInRange("lan_interval_seconds", 5, 1, 60);

        DISCOVERY_LAN_TIMEOUT_SECONDS = BUILDER
                .comment("Drop a LAN-discovered peer entry if no 'AZ_HELLO' received in this long.")
                .defineInRange("lan_timeout_seconds", 15, 5, 120);

        DISCOVERY_PING_ON_CLICK = BUILDER
                .comment("1.4.1: in Browse Games, click on a row to TCP-ping the host and show",
                        "the live latency in the detail screen. Disable to skip the ping")
                .define("ping_on_click", true);

        DISCOVERY_PING_TIMEOUT_MS = BUILDER
                .comment("TCP probe timeout when pinging a discovered host (milliseconds).")
                .defineInRange("ping_timeout_ms", 1500, 200, 10000);

        BUILDER.pop();

        // ============================================================
        // [host] — defaults for the Host Game screen
        // ============================================================
        BUILDER.comment("Defaults for the Host Game screen (overridden by world autodetect)").push("host");

        HOST_DEFAULT_MAX_PLAYERS = BUILDER
                .comment("Default max players in the Host Game screen.")
                .defineInRange("default_max_players", 8, 1, 1000);

        HOST_DEFAULT_PORT = BUILDER
                .comment("Default LAN TCP port. 25565 = standard Minecraft server port.")
                .defineInRange("default_port", 25565, 1, 65535);

        HOST_DEFAULT_PVP = BUILDER
                .comment("Default 'PvP allowed' toggle. World autodetect overwrites this on",
                        "every open of the Host screen — this is just the initial value.")
                .define("default_pvp", true);

        HOST_DEFAULT_FLIGHT = BUILDER
                .comment("Default 'Flight allowed' toggle. World autodetect overwrites this.")
                .define("default_flight", false);

        HOST_DEFAULT_OP_LEVEL = BUILDER
                .comment("Default op level for the host in the new world (0..4).")
                .defineInRange("default_op_level", 4, 0, 4);

        BUILDER.pop();

        // ============================================================
        // [security] — logging of secrets, friend requests, invites
        // ============================================================
        BUILDER.comment("Security-related defaults. Default to the most restrictive value.").push("security");

        SECURITY_LOG_HOST_TOKENS = BUILDER
                .comment("If true, host_tokens are logged at DEBUG. DANGEROUS — tokens in",
                        "logs shared on Discord/forums = impersonation possible. Default false.")
                .define("log_host_tokens", false);

        SECURITY_LOG_PUBLIC_IP = BUILDER
                .comment("If true, the STUN-derived public IP is logged. Default false to keep",
                        "IP addresses out of log files unless explicitly enabled.")
                .define("log_public_ip", false);

        SECURITY_FRIEND_REQUESTS_FROM_NON_FRIENDS = BUILDER
                .comment("If false (default), only people in your friends list can send you",
                        "invites via the tracker. The General's decision: gate by default.")
                .define("allow_friend_requests_from_non_friends", false);

        SECURITY_INVITE_TTL_SECONDS = BUILDER
                .comment("How long an invite bundle stays valid (seconds). After this, the",
                        "Ed25519 signature is treated as expired and rejected.")
                .defineInRange("invite_ttl_seconds", 3600, 60, 86400);

        SECURITY_BLOCK_DUPLICATE_ID = BUILDER
                .comment("If true (default), two players connecting with the same azorea_id but",
                        "different public keys are blocked (one is impostor). DA-8/9.")
                .define("block_duplicate_id_with_different_key", true);

        SECURITY_MAX_INVITES_PER_DAY = BUILDER
                .comment("Max invites one Azorea ID can issue per 24h (DoS protection).",
                        "0 = no cap. 5 is a sane default for a personal mod.")
                .defineInRange("max_invites_per_day", 5, 0, 100);

        BUILDER.pop();

        // ============================================================
        // [ui] — overlays (nametag, future nametags, etc.)
        // ============================================================
        BUILDER.comment("UI overlays rendered above players").push("ui");

        UI_NAMETAG_OVERLAY = BUILDER
                .comment("If true, remote players render with Azorea's overlay: skin head +",
                        "name + colored ping dot. If false (default), vanilla nametag only.",
                        "§ 1.4.10: NOW OPT-IN, NOT DEFAULT. The overlay is EXPERIMENTAL —",
                        "bugs known: head quad sometimes mis-sized or invisible from",
                        "certain angles (audited in 1.4.5-1.4.8 logs), text rendering can",
                        "show artifacts. Default off so fresh installs use vanilla until",
                        "the rendering is verified stable. Flip to true to test the rework.")
                .define("nametag_overlay", false);

        UI_NAMETAG_HIDE_ON_SNEAK = BUILDER
                .comment("If true (default), the whole overlay disappears when the player",
                        "crouches. Quake/competitive style. Disable to keep the overlay",
                        "always visible.")
                .define("nametag_hide_on_sneak", true);

        UI_NAMETAG_PING_GOOD_MS = BUILDER
                .comment("Latency threshold for 'good' (green dot). Vanilla F3 uses 80ms.")
                .defineInRange("nametag_ping_good_ms", 80, 1, 1000);

        UI_NAMETAG_PING_OK_MS = BUILDER
                .comment("Latency threshold for 'ok' (yellow dot). Vanilla F3 uses 400ms.",
                        "Above this → 'bad' (red dot).")
                .defineInRange("nametag_ping_ok_ms", 400, 1, 5000);

        BUILDER.pop();

        // ============================================================
        // [net] — AzoreaNet: custom compression codec (DA-14)
        // ============================================================
        BUILDER.comment("AzoreaNet — custom network optimization layer").push("net");

        NET_ENABLED = BUILDER
                .comment("If true (default), Azorea replaces vanilla's CompressionEncoder with",
                        "a no-context-reset variant and exposes a metric in AzoreaNetLog/NET.",
                        "Disable to validate against vanilla.")
                .define("enabled", true);

        NET_COMPRESSION_LEVEL = BUILDER
                .comment("Deflate level 0-9. Default 6 = vanilla equivalent. MEASURED 2026-10-05:",
                        "level 9 gives 15-17% ratio (vs 14.8% at level 6, no real gain) but",
                        "costs 8.6x more CPU per MB (266 vs 31 ms/MB). 9 does not pay off;",
                        "see AGENTS.md DA-14. 0 = uncompressed (debug only).")
                .defineInRange("compression_level", 6, 0, 9);

        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private AzoreaConfig() {
    }

    // ============================================================
    // Typed getters — keep this list in sync with the static block.
    // ============================================================

    // ===== [general] =====
    public static boolean isDebug() { return GENERAL_DEBUG.get(); }
    public static boolean isVerboseNetlog() { return GENERAL_VERBOSE_NETLOG.get(); }

    // ===== [identity] =====
    public static boolean autoRegisterInNoPremiumWorlds() { return IDENTITY_AUTO_REG.get(); }
    public static boolean bootstrapHostOnFirstWorldLoad() { return IDENTITY_BOOTSTRAP_HOST.get(); }
    public static int hwCommitVersion() { return IDENTITY_HW_COMMIT_VERSION.get(); }

    // ===== [tracker] =====
    public static List<String> getTrackerUrls() {
        return TRACKER_URLS.get().stream()
                .map(Object::toString)
                .filter(s -> s != null && !s.isBlank())
                .toList();
    }
    public static int getDefaultTtlSeconds() { return TRACKER_DEFAULT_TTL_SECONDS.get(); }
    public static int getConnectTimeoutSeconds() { return TRACKER_CONNECT_TIMEOUT_SECONDS.get(); }
    public static int getRequestTimeoutSeconds() { return TRACKER_REQUEST_TIMEOUT_SECONDS.get(); }
    public static int getRefreshDivisor() { return TRACKER_REFRESH_DIVISOR.get(); }
    public static int getListMaxResults() { return TRACKER_LIST_MAX_RESULTS.get(); }
    public static boolean isEmbeddedTrackerEnabled() { return TRACKER_EMBEDDED_ENABLED.get(); }
    public static int getEmbeddedTrackerPort() { return TRACKER_EMBEDDED_PORT.get(); }
    public static int getEmbeddedTrackerPortMax() { return TRACKER_EMBEDDED_PORT_MAX.get(); }
    public static boolean isEmbeddedRelayEnabled() { return TRACKER_EMBEDDED_RELAY_ENABLED.get(); }
    public static int getEmbeddedRelayPort() { return TRACKER_EMBEDDED_RELAY_PORT.get(); }

    // ===== [discovery] =====
    public static boolean isLanDiscoveryEnabled() { return DISCOVERY_LAN_ENABLED.get(); }
    public static int getLanDiscoveryPort() { return DISCOVERY_LAN_PORT.get(); }
    public static int getLanDiscoveryIntervalSeconds() { return DISCOVERY_LAN_INTERVAL_SECONDS.get(); }
    public static int getLanDiscoveryTimeoutSeconds() { return DISCOVERY_LAN_TIMEOUT_SECONDS.get(); }
    public static boolean isPingOnClickEnabled() { return DISCOVERY_PING_ON_CLICK.get(); }
    public static int getPingTimeoutMs() { return DISCOVERY_PING_TIMEOUT_MS.get(); }

    // ===== [host] =====
    public static int getHostDefaultMaxPlayers() { return HOST_DEFAULT_MAX_PLAYERS.get(); }
    public static int getHostDefaultPort() { return HOST_DEFAULT_PORT.get(); }
    public static boolean getHostDefaultPvp() { return HOST_DEFAULT_PVP.get(); }
    public static boolean getHostDefaultFlight() { return HOST_DEFAULT_FLIGHT.get(); }
    public static int getHostDefaultOpLevel() { return HOST_DEFAULT_OP_LEVEL.get(); }

    // ===== [security] =====
    public static boolean shouldLogHostTokens() { return SECURITY_LOG_HOST_TOKENS.get(); }
    public static boolean shouldLogPublicIp() { return SECURITY_LOG_PUBLIC_IP.get(); }
    public static boolean allowFriendRequestsFromNonFriends() {
        return SECURITY_FRIEND_REQUESTS_FROM_NON_FRIENDS.get();
    }
    public static int getInviteTtlSeconds() { return SECURITY_INVITE_TTL_SECONDS.get(); }
    public static boolean blockDuplicateIdWithDifferentKey() {
        return SECURITY_BLOCK_DUPLICATE_ID.get();
    }
    public static int getMaxInvitesPerDay() { return SECURITY_MAX_INVITES_PER_DAY.get(); }

    // ===== [ui] =====
    public static boolean isNametagOverlayEnabled() { return UI_NAMETAG_OVERLAY.get(); }
    public static boolean isNametagHiddenOnSneak() { return UI_NAMETAG_HIDE_ON_SNEAK.get(); }
    public static int getNametagPingGoodMs() { return UI_NAMETAG_PING_GOOD_MS.get(); }
    public static int getNametagPingOkMs() { return UI_NAMETAG_PING_OK_MS.get(); }

    // ===== [net] =====
    public static boolean isNetOptEnabled() { return NET_ENABLED.get(); }
    public static int getCompressionLevel() { return NET_COMPRESSION_LEVEL.get(); }
}
