# Azorea

**Tu mundo. Tu amigo. Y en medio, nada.**

Azorea es un mod de NeoForge para Minecraft 1.21.1 que junta dos mundos de un jugador por internet, en directo. Tú abres el mundo que ya juegas. Tu amigo pega una invitación. Si la red se deja, las dos máquinas se hablan y el mod se aparta.

Ni cuenta que crear, ni servidor nuestro encendido. Eso último es justo el punto: de nuestro lado no hay nada que se pueda caer.

[![GitHub release](https://img.shields.io/github/v/release/CerebroCanibalus/azorea?label=release)](https://github.com/CerebroCanibalus/azorea/releases)
[![Licencia: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)
[![Modrinth](https://img.shields.io/badge/Modrinth-pr%C3%B3ximamente-lightgrey)](#d%C3%B3nde-conseguirlo)
[![CurseForge](https://img.shields.io/badge/CurseForge-pr%C3%B3ximamente-lightgrey)](#d%C3%B3nde-conseguirlo)

*Prefer English? → [README.md](README.md)*

## Dónde conseguirlo

- **Modrinth** — próximamente <!-- TODO: https://modrinth.com/mod/azorea -->
- **CurseForge** — próximamente <!-- TODO: https://www.curseforge.com/minecraft/mc-mods/azorea -->
- **GitHub Releases** — [último jar](https://github.com/CerebroCanibalus/azorea/releases)

Necesitas Minecraft 1.21.1 y NeoForge 21.1.250 o superior. Suelta el jar en tu carpeta `mods/`. Lo demás ya te lo sabes.

## Abrir partida

Abre un mundo. Pulsa **B**. Por ahí se entra — la tecla se puede cambiar, y si prefieres el ratón hay un **icono de Host** en la esquina superior derecha del menú de pausa y de la pantalla de título.

- **Host Game** abre los ajustes: modo de juego, dificultad, PvP, vuelo, trampas, MOTD y el modo de acceso (premium o no-premium, abajo lo cuento). Lo dejas a tu gusto y le das a **Start**.
- **Copy Invite** te da una cadena. Mándala por donde quieras — Discord, WhatsApp, un mensaje. Lleva las direcciones de tu host y una firma. Tu amigo la pega y entra.

La invitación se basta sola. Sin tracker, sin lobby, sin terceros. Quien tiene la cadena, tiene la puerta.

## Entrar a una partida

Pulsa **B** → **Join by Invite**, pega, conecta. Azorea lee las direcciones de la invitación y las prueba en orden hasta que una contesta. Si estáis en la misma red, sale solo. Si no, lo que pase depende del apartado siguiente.

## Lo que tu red permite

Aquí va la parte sincera, que es donde casi todos los mods «P2P» mienten.

Para que dos máquinas lejanas se encuentren en directo, una tiene que ser alcanzable. Azorea busca una puerta en el lado del host, por orden:

1. **Un reenvío de puerto que ya tengas** en TCP 25565.
2. **UPnP** — si el router deja que el mod te abra el puerto.
3. **NAT-PMP / PCP** — esquemas automáticos más viejos, también se prueban.
4. **IPv6 público** — sin mapeo; la dirección va tal cual en la invitación.

Lo que encuentre es la dirección que entra en la invitación. El otro lado contesta y, a partir de ahí, sólo queda el TCP directo de Minecraft.

Si no hay nada de eso —estás detrás de CGNAT y sin IPv6, ponle— Azorea no se inventa una ruta. **Todavía no hay relay.** Te haría falta un reenvío manual, IPv6 o un relay de terceros fuera del mod. Antes te lo decimos a la cara que esconderlo detrás de un circulito que gira.

## Mundos premium y no-premium

**Premium** es lo de fábrica y funciona como un servidor con online-mode: el mundo comprueba a los jugadores contra Mojang. Tu amigo necesita un login de Minecraft que funcione.

**No-premium** cambia eso por una identidad que Azorea comprueba por su cuenta. Antes de aparecer, el jugador demuestra que tiene la clave Ed25519 detrás de su `azorea_id`, y el mundo del host apunta quién es quién en `identities.json`. Viene bien cuando alguien no puede entrar con Mojang o cuando prefieres dejar a la cuenta fuera del asunto.

En los dos casos manda el host. Borras una entrada y ese jugador vuelve a registrarse. Ni baneos clavados ni bloqueos.

## En la misma red

Con un solo router, Azorea encuentra a los vecinos él solo: lo instalas en las dos máquinas y **Browse Games** enseña las partidas locales. Sin configurar nada, sin escribir direcciones.

Para buscar por internet tendrías que apuntar el mod a un tracker en `config/azorea.toml`. Cualquiera puede montar uno: en el repo hay un `tracker-server/` suelto. Nosotros no te llevamos ninguno.

## ¿Funciona ya?

Sí, para el camino previsto; y no, para cualquier red. Sin rodeos:

| Situación | |
|---|---|
| La misma LAN | ✅ |
| Remoto, el host con reenvío de puerto o IPv6 público | ✅ |
| Remoto, el host tras CGNAT y sin IPv6 | ❌ todavía sin relay |
| Buscar partidas por internet | necesita tu propio tracker |
| Comprobación de identidad en mundos no-premium | ✅ |

Las dos primeras filas las hemos probado de punta a punta, con unos miles de kilómetros de por medio y hasta una docena de jugadores. La tercera es un límite de verdad, no un olvido.

## La seguridad, en un párrafo

Cada jugador es una identidad, no un nombre: el `azorea_id` sale de tus claves, así que nadie puede hacerse pasar por ti. Las invitaciones y los anuncios van firmados con Ed25519. En los mundos no-premium la prueba de identidad ocurre antes de aparecer. Toda la criptografía viene dentro del JDK: cero librerías de terceros. El modelo completo, sus límites y cómo avisar de un fallo están en [SECURITY.md](SECURITY.md).

## Compilar desde el código

Necesitas **JDK 21** y Git. Nada más — Gradle viene con el wrapper.

```bash
git clone https://github.com/CerebroCanibalus/azorea
cd azorea

./gradlew :v1_21_1:build         # el mod      → v1_21_1/build/libs/
./gradlew :tracker-server:build  # el tracker  → tracker-server/build/libs/
```

Para trastear en un entorno de desarrollo:

```bash
./gradlew :v1_21_1:runServer     # servidor dedicado
./gradlew :v1_21_1:runClient     # cliente
./gradlew :v1_21_1:runClient2    # segundo cliente, carpeta aparte
```

Tests:

```bash
./gradlew :v1_21_1:test                       # el mod
./gradlew :tracker-server:test                # el tracker
RUN_NETWORK_TESTS=1 ./gradlew :v1_21_1:test   # añade sondeos STUN de verdad
```

Va en Windows, macOS y Linux. Cada carpeta de ejecución va aislada, así que puedes probar dos identidades en el mismo PC.

## Contribuir

Los informes de fallos y las pull requests son bienvenidos. Lee antes [CONTRIBUTING.md](CONTRIBUTING.md) — es corto. Lo esencial:

- **Nada de infraestructura central.** No metas código que clave una URL a un servicio que tengamos que mantener. Es una línea de diseño, no un capricho.
- **Sin telemetría, sin llamar a casa.**
- **GPL-3.0.** Al abrir una PR licencias tu trabajo igual.
- **Java 21**, NeoForge 21.1, ModDevGradle.
- **Tests para todo lo que tenga miga.** La identidad, las invitaciones y el control de acceso a los mundos están cubiertos; sigue su ejemplo.

## Licencia

GPL-3.0. Mira [LICENSE](LICENSE).
