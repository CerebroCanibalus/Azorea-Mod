# Azorea

**Your world. Your friend. Nothing in the middle.**

Azorea is a NeoForge mod for Minecraft 1.21.1 that puts two singleplayer worlds in touch over the internet, directly. You host the world you already play. Your friend pastes an invite. If the networks allow it, the two machines talk to each other and the mod gets out of the way.

No account to create. No server we keep running. That last part is the whole point: there is nothing on our end that can go down.

[![GitHub release](https://img.shields.io/github/v/release/CerebroCanibalus/azorea?label=release)](https://github.com/CerebroCanibalus/azorea/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)
[![Modrinth](https://img.shields.io/badge/Modrinth-coming%20soon-lightgrey)](#where-to-get-it)
[![CurseForge](https://img.shields.io/badge/CurseForge-coming%20soon-lightgrey)](#where-to-get-it)

*¿Prefieres español? → [README.es.md](README.es.md)*

## Where to get it

- **Modrinth** — coming soon <!-- TODO: https://modrinth.com/mod/azorea -->
- **CurseForge** — coming soon <!-- TODO: https://www.curseforge.com/minecraft/mc-mods/azorea -->
- **GitHub Releases** — [latest jar](https://github.com/CerebroCanibalus/azorea/releases)

You need Minecraft 1.21.1 and NeoForge 21.1.250 or newer. Drop the jar in your `mods/` folder. You know the rest.

## Hosting a game

Open a world. Press **B**. That's the way in — the key is rebindable, and a small **Host** icon sits in the top-right corner of the pause menu and the title screen if you prefer clicking.

- **Host Game** opens the settings: game mode, difficulty, PvP, flight, cheats, MOTD, and the access mode (premium or no-premium, further down). Arrange the session how you want it, then hit **Start**.
- **Copy Invite** hands you one string. Send it anywhere — Discord, WhatsApp, a text message. It carries your host's addresses and a signature. Your friend pastes it and connects.

The invite stands on its own. No tracker, no lobby, no third party. Whoever holds the string holds the door.

## Joining a game

Press **B** → **Join by Invite**, paste, connect. Azorea reads the addresses inside the invite and tries them in order until one answers. On the same network it just works. Off it, what happens depends on the next section.

## What your network allows

Here's the honest part, because it's where most "P2P" mods lie.

For two remote machines to meet directly, one of them has to be reachable. Azorea looks for a door on the host's side, in order:

1. **A port-forward you already have** on TCP 25565.
2. **UPnP** — if the router lets the mod open the port for you.
3. **NAT-PMP / PCP** — older automatic schemes, tried too.
4. **Public IPv6** — no mapping needed, the address just goes in the invite.

Whatever it finds becomes the address in the invite. The joining side reaches back and, from then on, it's Minecraft's own direct TCP.

If none of those exist — you're behind carrier-grade NAT with no IPv6, say — Azorea won't invent a route. **There is no relay built in yet.** You'd need a port-forward, IPv6, or a third-party relay outside the mod. We'd rather say that plainly than hide it behind a spinner.

## Premium and no-premium worlds

**Premium** is the default and works like an online-mode server: the world checks players against Mojang. Your friend needs a normal, working Minecraft login.

**No-premium** replaces that with an identity check Azorea runs itself. Before a player spawns, they prove they hold the Ed25519 key behind their `azorea_id`, and the host's world tracks who's who in `identities.json`. Handy when someone can't sign in with Mojang, or when you'd rather keep the account system out of it.

Either way, the host owns the list. Delete an entry and that player can register again. No bans baked in, no lockout.

## On the same network

With one router, Azorea finds neighbours by itself — install it on both machines and **Browse Games** lists the local sessions. No config, no addresses to type.

To browse over the internet you'd point the mod at a tracker in `config/azorea.toml`. Anyone can run one: there's a standalone `tracker-server/` in the repo. We don't operate one for you.

## Does it work today?

Yes for the intended path, and no for every network. Straight answer:

| Situation | |
|---|---|
| Same LAN | ✅ |
| Remote, host has a port-forward or public IPv6 | ✅ |
| Remote, host is behind CGNAT with no IPv6 | ❌ no relay yet |
| Browsing games over the internet | needs your own tracker URL |
| Identity check for no-premium worlds | ✅ |

The first two rows are what we've tested end to end, across a few thousand kilometres and with up to a dozen players. The third is a real limit, not something we forgot.

## Security, in one paragraph

Every player is an identity, not a username: `azorea_id` is derived from your keys, so nobody can claim to be you. Invites and announcements are signed with Ed25519. In no-premium worlds the identity proof happens before you spawn. All the crypto comes with the JDK — no third-party libraries. The full model, its limitations, and how to report a vulnerability live in [SECURITY.md](SECURITY.md).

## Building from source

You need **JDK 21** and Git. Nothing else — Gradle ships with the wrapper.

```bash
git clone https://github.com/CerebroCanibalus/azorea
cd azorea

./gradlew :v1_21_1:build         # the mod      → v1_21_1/build/libs/
./gradlew :tracker-server:build  # the tracker  → tracker-server/build/libs/
```

To hack on it in a dev environment:

```bash
./gradlew :v1_21_1:runServer     # dedicated server
./gradlew :v1_21_1:runClient     # client
./gradlew :v1_21_1:runClient2    # second client, isolated game dir
```

Tests:

```bash
./gradlew :v1_21_1:test                       # the mod
./gradlew :tracker-server:test                # the tracker
RUN_NETWORK_TESTS=1 ./gradlew :v1_21_1:test   # adds live STUN probes
```

Runs on Windows, macOS and Linux. Each run directory is isolated, so you can test two identities on one PC.

## Contributing

Bug reports and pull requests are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) first — it's short. The gist:

- **No central infrastructure.** Don't add code that hardcodes a URL to a service we'd have to run. That's a design line, not a preference.
- **No telemetry, no phone-home.**
- **GPL-3.0.** Opening a PR licenses your work the same way.
- **Java 21**, NeoForge 21.1, ModDevGradle.
- **Test what's clever.** The security-sensitive pieces — identity, invites, the access gate — are all covered; follow their lead.

## License

GPL-3.0. See [LICENSE](LICENSE).
