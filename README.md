# Azorea

**A real, automated and easy to use peer-to-peer multiplayer and hosting mod for Minecraft 1.21.1 with no account, free forever.**

Azorea opens your singleplayer world to a friend across the internet and connects the two machines **directly**. Your chunks travel straight to your friend's game, not through a server either of you has to trust, rent or sign into. It is the same save you already play, and the same person you already know.

[![GitHub release](https://img.shields.io/github/v/release/CerebroCanibalus/Azorea-Mod?label=release)](https://github.com/CerebroCanibalus/Azorea-Mod/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)
[![Modrinth](https://img.shields.io/badge/Modrinth-coming%20soon-lightgrey)](#where-to-get-it)
[![CurseForge](https://img.shields.io/badge/CurseForge-coming%20soon-lightgrey)](#where-to-get-it)

*¿Prefieres español? → [README.es.md](README.es.md)*

## Why you'd use it

Most ways to play Minecraft with a friend put a server in the middle. Your game goes out to a machine someone else runs a paid host or a service that wants an account and from there to your friend. That server costs money, sees your traffic, adds a hop of latency, and can disappear the day its operator decides to close it.

Azorea takes the other road. It connects the two machines **directly**, and keeps as little as possible between them:

- **No account.** Identity is a generated from your disk.
- **No relay in the middle.** A direct connection is faster than one bounced through a third party, and your traffic goes from you to your friend and nowhere else.
- **No dedicated server.** There is nothing we want to run in the middle of your game, so there is nothing on our side that can go down, change hands or start charging.
- **Your world stays yours.** You host the save you already play, nothing is uploaded to anyone and you have full configuration over your world and host.
- **Plug n Play.** The mod handles as much as it is able to, in order to connect you to your friends, in rare cases where configuration is needed, you are given a proper guide.
- **It works with non-premium accounts too.** Besides the usual Mojang-verified mode, a world can run in a mode where Azorea proves identity itself, so a friend without a working Minecraft login can still join and your skins and items stay correct.

## How does it work?

- **First it hosts your existing world over the internet**.
- **Finds the network path on its own**: a port-forward you already have, then UPnP, NAT-PMP, PCP, and finally a public IPv6 address.
- **Punches a direct connection** when no port is open the two machines still meet, without a relay, using TCP hole punching.
- **Signs your invitations.** The string you send carries the host's addresses and a signature over them, so you can paste it anywhere and no one in the middle can quietly redirect it.
- **Discovers games on your LAN** with nothing to configure, install it on both machines and they see each other.
- **Controls who gets in, per world**, in two modes premium, checked against Mojang, or no-premium, checked by Azorea, with an identity list the host owns and can edit.
- **Ships no telemetry, no accounts, and no third-party cryptography.** The whole security model runs on primitives built into the JDK.

## Where to get it

- **Modrinth** — coming soon <!-- TODO: https://modrinth.com/mod/azorea -->
- **CurseForge** — coming soon <!-- TODO: https://www.curseforge.com/minecraft/mc-mods/azorea -->
- **GitHub Releases** — [latest jar](https://github.com/CerebroCanibalus/Azorea-Mod/releases)

You need Minecraft 1.21.1 and NeoForge 21.1.250 or newer. Drop the jar in your `mods/` folder. You know the rest.

## Playing with someone

Open a world and press **B**, or click the small **Host** icon in the top-right corner of the pause menu or the title screen. The key can be rebound like any other.

**Host Game** opens the session settings — game mode, difficulty, PvP, flight, cheats, MOTD, and the access mode covered below. Set it up the way you want the session to play, then press **Start**. When the game is up, **Copy Invite** gives you one string. Send it however you like: Discord, a message, a piece of paper. Your friend presses **B**, chooses **Join by Invite**, pastes it, and connects.

The invitation carries everything needed to reach you, so there is no lobby to search and no third party involved. Whoever has the string has the way in.

## Network LIMITATIONS!

For two remote machines to meet directly, one of them has to be reachable, and finding that path is where most "P2P" software quietly gives up or lies. Azorea looks for an opening on the host's side, in order:

1. **A port-forward you already have** on TCP 25565.
2. **UPnP**, if the router lets the mod open the port on its own.
3. **NAT-PMP and PCP**, the older automatic schemes, tried as well.
4. **A public IPv6 address**, which needs no mapping at all, it simply goes into the invite.

Whichever one answers becomes the address in the invitation. The joining side reaches back, and from that moment on it is Minecraft's own direct TCP connection.

If none of them answer, you may be behind carrier-grade NAT with no IPv6, for instance, Azorea will not invent a route. There is no relay built in (Though the mod has support for it, in case you want that), so the last option is a manual port-forward or a third-party relay outside the mod.

## No-premium worlds

**No-premium** replaces the Mojang check with one Azorea runs itself. Before a player spawns, they prove they hold the Ed25519 key behind their `azorea_id`, and the host's world keeps track of who is who in `identities.json`. It helps when someone cannot sign in with Mojang, or when you would rather keep the account system out of the picture entirely.

Either way the host owns the register. Remove an entry if you need a player to register again. 

## On the same network

Behind a single router, Azorea finds its neighbours by itself. Install it on both machines and **Browse Games** lists the local sessions, with nothing to configure and no addresses to type.

Browsing over the internet is possible too, but it needs a tracker: you point the mod at one in `config/azorea.toml`. Anyone can run one — the repository ships a standalone `tracker-server/` — but we do not operate one on your behalf. May be expanded in the future!

## Technical look

Azorea is a small mod, and these are the technical decisions that lead to it.

**Identity.** Most multiplayer starts by asking a server "who is this?". With no server to ask, identity has to be something you can prove on your own. Your `azorea_id` is derived from your two keypairs — Ed25519 for signing, X25519 for key agreement — together with a hash of your hardware. Nothing assigns or registers it; it simply falls out of the keys. Anyone who holds your ID and public keys can check that all three agree, and that you hold the private half, without asking a third party. Because there is no register, no one can squat an ID and no authority can revoke one. The trade-off is that the identity lives in a local file, so losing that file means losing the identity, improvements are planned.

**Invitations.** The string your friend pastes is is a encrypted and signed address that holds the host's endpoints, the host's Azorea ID, and an Ed25519 signature over the whole thing. When your friend's game reads it, it checks two facts: that the signature matches the host's key, and that the claimed ID really derives from the keys in the bundle. Together those stop a third party who sees the invite from quietly rewriting it to point at their own machine, because the signature covers the endpoints and the ID is pinned to the keys. Invitations sent through a tracker are additionally encrypted to the recipient's X25519 key with ChaCha20-Poly1305, so only the intended friend can read the addresses inside.

**A punch that keeps TCP.** When there is no open port to connect to, Azorea can open one by simultaneous open, both machines send from a chosen port at the same instant, and each NAT lets the traffic through as it sees the other's outbound packet. Many P2P tools drop to UDP at this point and rebuild reliability by hand. Azorea stays on TCP through a hole-punched socket as a real connection, and the operating system does the retransmitting. A small local proxy then bridges that socket to Minecraft. Nothing in the game itself is actually patched.

**Primitives from the JDK.** The entire security model — Ed25519 signatures, X25519 key agreement, ChaCha20-Poly1305 for encrypted invitations, SHA-256 for the identity hash — runs on algorithms built into the JDK. There is no external cryptography library to audit, pin or update. Make sure to leave any security concerns or suggestions to keep improving the network security of users.

The full security model, its assumptions and its limitations can be read in [SECURITY.md](SECURITY.md).

## Building from source

You need **JDK 21** and Git; Gradle arrives with the wrapper.

```bash
git clone https://github.com/CerebroCanibalus/Azorea-Mod
cd azorea

./gradlew :v1_21_1:build         # the mod      → v1_21_1/build/libs/
./gradlew :tracker-server:build  # the tracker  → tracker-server/build/libs/
```

To work on it in a dev environment:

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

It builds on Windows, macOS and Linux. Each run directory is isolated, so you can test two identities on one machine.

## Contributing

Bug reports and pull requests are always (NEVER!!!!!! MY CODE IS PERFECT!!!!!!!!) welcome. [CONTRIBUTING.md](CONTRIBUTING.md) is short and worth reading first. The essentials:

- **No central infrastructure.** Don't add code that hardcodes a URL to a service we would have to run. This is a design line, not a preference.
- **No telemetry and no phone-home.**
- **GPL-3.0.** Opening a pull request licenses your work the same way.
- **Java 21**, NeoForge 21.1, ModDevGradle.
- **Test what's subtle.** The security-sensitive parts — identity, invitations, the access gate — are all covered; follow their example.

## License

GPL-3.0. See [LICENSE](LICENSE).
