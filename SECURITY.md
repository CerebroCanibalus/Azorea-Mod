# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.4.x   | :white_check_mark: |
| < 1.4.0 | :x:                |

## Reporting a Vulnerability

Please file security issues via **GitHub Security Advisories** (private until patched):

> [https://github.com/CerebroCanibalus/Azorea-Mod/security/advisories/new](https://github.com/CerebroCanibalus/Azorea-Mod/security/advisories/new)

**Do not file public issues for unpatched vulnerabilities.** Public disclosure before a fix is ready gives attackers a head start.

We aim to:
- Acknowledge new reports within 7 days
- Ship a fix for critical issues within 30 days
- Credit reporters in the release notes (unless they prefer to stay anonymous)

## Security Model

See [README.md](README.md) for the full model. Key points:

- **Identity (DA-8)** is self-certifying: `azorea_id = base32(SHA-256("azorea-id/v2" || ed25519_pub || x25519_pub || hw_commit)[:15])`. It is computed deterministically from your keys, never assigned by a server.
- **Announcements are Ed25519-signed (DA-9)**. Every `POST /announce` to a tracker carries `signingKey`, `hwCommit` and `signature`. Trackers verify before storing.
- **No-premium gate (F10)** runs an Ed25519 challenge-response **before** spawn — during the Minecraft configuration phase. The host verifies the joiner possesses the private key whose hash matches their claimed `azorea_id`.
- **Invites (DA-12, F8.x)** are Ed25519-signed bundles over a canonical byte payload that covers the host's public endpoints. A man-in-the-middle cannot redirect the joiner to a different machine.
- **Host is the authority**: in non-premium worlds, the host's `<world>/azorea/identities.json` is the source of truth. There is no TOFU, no lockout. Removing an identity re-opens registration.

## Cryptographic Stack

All from the JDK (no external crypto dependencies).

| Use                                | Algorithm                |
| ---------------------------------- | ------------------------ |
| Sign announce + invite + gate       | Ed25519 (JDK builtin)    |
| ECDH for invite `encrypted_blob`   | X25519 (JDK builtin)     |
| AEAD for invite `encrypted_blob`   | ChaCha20-Poly1305 (JDK builtin) |
| Hashing                            | SHA-256 (JDK builtin)    |
| Random                             | `java.security.SecureRandom` |

## Known Limitations

These are **by design** or **out of scope**. Documented to avoid surprises.

- **Vanilla MC protocol is plaintext over TCP.** This is a Minecraft limitation, not an Azorea choice. Friends' IPs are visible to each other (P2P).
- **No central server / relay.** Azorea does not operate any infra. Users behind CGNAT cannot host unless their ISP removes them from the pool, or they set up a third-party relay (not built into Azorea).
- **HTTP tracker traffic is plaintext.** The tracker is server-to-server only (mod-to-mod, never browser-facing). CORS does not apply. If you operate a tracker publicly, put it behind a reverse proxy that terminates TLS — and if that proxy rewrites `X-Forwarded-For`, only then start the tracker with `-Dtracker.trust_forwarded_for=true`.
- **LAN discovery is plaintext over UDP multicast.** Assumes LAN is trusted. Anyone on the same subnet sees your `azorea_id`, display name, tracker URL and bind address.
- **No identity backup.** Losing `identity.json` means losing your `azorea_id` and all friends. Export/import UX is on the roadmap.
- **`X-Forwarded-For` is ignored by default.** The standalone tracker uses the real TCP socket address for per-IP rate limiting unless you explicitly opt in with `-Dtracker.trust_forwarded_for=true`. Turning it on without a trusted proxy in front lets an attacker spoof IPs and bypass the limit.
- **Tracker request bodies are capped.** The standalone tracker rejects bodies over 8 KB (`/announce`, `/presence`, `/invite`) or 64 KB (`/punch/announce`) with a `400`. The cap is enforced from `Content-Length` first, then by the actual byte count.

## Audits

- **2026-10-07**: first audit against OWASP Top 10:2025 + Network Security guidance. No critical issues found. Two medium findings — the tracker trusting `X-Forwarded-For`, and unbounded request bodies — were fixed in 1.4.11 and are documented under **Known Limitations** above.

## Repository

- **Repo**: `https://github.com/CerebroCanibalus/Azorea-Mod` (will become public on first release)
- **Default branch**: `main`
- **License**: GPL-3.0 (`LICENSE` at repo root)
- **Contributing**: see `CONTRIBUTING.md`
- **Issue templates**: `.github/ISSUE_TEMPLATE/` (`bug_report.md`, `feature_request.md`)

## Bug Bounty

There is no formal bug bounty program at this time. Responsible disclosure is appreciated and credited.