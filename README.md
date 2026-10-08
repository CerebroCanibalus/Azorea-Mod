# Azorea

> **P2P multiplayer for Minecraft 1.21.1. Free, private, no infra required.**

Azorea is a NeoForge mod that lets Minecraft players host and join games directly over the internet — without relying on any central server. When it works, it's direct P2P: your chunks travel straight to your friend's machine.

## What it does

- **Self-hosted games**: open your singleplayer world to a friend over the internet with a couple of clicks.
- **Identity-based**: every player has a self-certifying `azorea_id` derived from their Ed25519 + X25519 keys. No accounts. No central registry.
- **Invite-based join**: hosts share a signed invite bundle out-of-band (chat, Discord, email). The bundle contains the host's public endpoints + a signed token.
- **Embedded tracker** for LAN discovery — works out of the box on the same router without configuring anything.
- **No-premium gate**: when the host marks a world as "no-premium", joining players must prove possession of the Ed25519 key whose hash matches their claimed `azorea_id` — *before spawning*. Impersonation is cryptographically impossible.

## Install

1. Install **NeoForge 21.1.250+** for Minecraft 1.21.1.
2. Download `azorea-v1_21_1-X.Y.Z.jar` from [Releases](https://github.com/CerebroCanibalus/azorea/releases).
3. Drop the jar into `~/.minecraft/mods/`.
4. Launch Minecraft. Azorea appears:
   - In the **pause menu** (button **B** → Azorea menu)
   - In the **title screen** (icon **H** — Host config)

### Host a game

1. Open a singleplayer world.
2. Press **B → Host Game**.
4. Configure (game mode, difficulty, PvP, flight, MOTD...).
5. Click **Start**.
6. Click **Copy Invite** and send it to your friend via any channel.

### Join a game

1. Press **B → Join by Invite**.
2. Paste the invite.
3. If the network allows, you connect directly.

## Network requirements

**To host over the internet**:
- Open TCP port **25565** (or your configured port) on your NAT/router via **manual port-forward**.
- UPnP works if your router allows it (most modern routers do).
- **CGNAT** (carrier-grade NAT) will block this regardless of port-forward. The in-game "Network" panel tells you when your public IP is not reachable.

**To join**: no inbound ports needed. If your friend has port-forward working, you connect directly.

## Design decisions (honest)

Azorea is built on one core decision: **no infrastructure that we maintain**. The mod is the whole system — there's no Azorea-hosted relay, no Azorea-hosted rendezvous, no Azorea-hosted tracker. If you and your friend both have port-forward (or aren't behind CGNAT), you connect directly. If not, you don't.

What this means in practice:
- **No relay** ⇒ users behind CGNAT cannot host unless their ISP removes them from the pool, or they set up a third-party relay (not built-in).
- **Browse Games** works on LAN out of the box. To browse games over the internet, the *user* adds a tracker URL to `config/azorea.toml` — anyone can run a tracker (`tracker-server/` is shipped standalone), but Azorea doesn't operate one for you.

This is a deliberate trade-off documented as `DA-10` in the project's design notes: the alternative (operating a relay) requires ongoing infrastructure that this project does not want to commit to.

## Security

### Identity (`azorea-id`)

`azorea_id` is computed deterministically from your Ed25519 + X25519 public keys:

```
azorea_id = base32(SHA-256("azorea-id/v2" || ed25519_pub || x25519_pub || hw_commit)[:15])
```

The result is human-readable: `AZ-XXXXXX-XXXXXX-XXXXXX-XXXXXX`.

### Announcements are signed

Every announce carries:
- `signingKey`: Ed25519 public key
- `hwCommit`: hardware commit
- `signature`: Ed25519 signature over a deterministic canonical (without the sign field, to avoid cyclic dependency)

Trackers verify before accepting. Unsigned or tampered announces return `400`.

### No-premium gate

In worlds marked `no-premium`, the host runs an Ed25519 challenge during the Minecraft configuration phase (before spawn):
1. Host sends a random challenge.
2. Joiner signs with the Ed25519 key whose hash matches their claimed `azorea_id`.
3. Host verifies signature + auto-cert (derivation check) before allowing spawn.

The host is the authority for identities in their world. There is **no TOFU, no lockout**. Removing an identity from `<world>/azorea/identities.json` re-opens registration.

### Cryptographic primitives

| | |
|---|---|
| Ed25519 | signing — JDK built-in |
| X25519 | ECDH for invite `encrypted_blob` — JDK built-in |
| ChaCha20-Poly1305 | AEAD for invite `encrypted_blob` — JDK built-in |
| SHA-256 | hashing — JDK built-in |
| CSPRNG | `java.security.SecureRandom` for all key generation |

No external crypto dependencies.

### What Azorea does NOT use

- **No TLS** on the Minecraft protocol (Vanilla limitation — Minecraft itself is plaintext over TCP). Friends' IPs are visible to each other by design (P2P).
- **No CORS / no web frontend**: the tracker is an HTTP API, not a browser-facing endpoint. CORS is not applicable.
- **No user accounts / no central DB**: identity is purely local.

## Compatibility

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.250+ |
| Java | 21 |
| Loader range | `[21.1,)` |

## Building from source

```bash
./gradlew :v1_21_1:build
```

The jar lands in `v1_21_1/build/libs/`.

## Reporting vulnerabilities

Please file security issues via [GitHub Security Advisories](https://github.com/CerebroCanibalus/azorea/security/advisories/new) (private until patched). Do not file public issues for unpatched vulnerabilities.

## License

GPL-3.0. See [LICENSE](LICENSE).