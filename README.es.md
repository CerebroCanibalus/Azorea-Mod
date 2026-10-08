# Azorea

**Un mod de multijugador y hosteo peer-to-peer real, automatizado y fácil de usar para Minecraft 1.21.1, sin cuenta y gratis para siempre.**

Azorea abre tu mundo de un jugador a un amigo por internet y conecta las dos máquinas **en directo**. Tus chunks viajan derechitos al juego de tu amigo, no por un servidor que alguno de los dos tenga que confiar, alquilar o en el que tenga que entrar. Es el mismo mundo que ya juegas, y la misma persona que ya conoces.

[![GitHub release](https://img.shields.io/github/v/release/CerebroCanibalus/Azorea-Mod?label=release)](https://github.com/CerebroCanibalus/Azorea-Mod/releases)
[![Licencia: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)
[![Modrinth](https://img.shields.io/badge/Modrinth-pr%C3%B3ximamente-lightgrey)](#d%C3%B3nde-conseguirlo)
[![CurseForge](https://img.shields.io/badge/CurseForge-pr%C3%B3ximamente-lightgrey)](#d%C3%B3nde-conseguirlo)

*Prefer English? → [README.md](README.md)*

## Demo

Dos jugadores, en ciudades distintas, una conexión directa:

[▶ Ver la demo](assets/demo.mp4)

## Por qué te interesaría

Casi todas las formas de jugar a Minecraft con un amigo meten un servidor en medio. Tu partida sale a una máquina que lleva otro —un host de pago, o un servicio que pide una cuenta— y de ahí va a tu amigo. Ese servidor cuesta dinero, ve tu tráfico, añade un salto de latencia y puede desaparecer el día que a su dueño le apetezca cerrarlo.

Azorea va por el otro camino. Conecta las dos máquinas **en directo** y deja lo mínimo posible entre ellas:

- **Sin cuenta.** La identidad se genera en tu propio disco.
- **Sin relay en medio.** Una conexión directa es más rápida que una rebotada por un tercero, y tu tráfico va de ti a tu amigo y a ningún otro sitio.
- **Sin servidor dedicado.** No queremos correr nada en medio de tu partida, así que de nuestro lado no hay nada que se pueda caer, cambiar de manos ni empezar a cobrar.
- **Tu mundo sigue siendo tuyo.** Hosteas el guardado que ya juegas; no se sube nada a ninguna parte y tienes control total sobre tu mundo y tu hosteo.
- **Plug and play.** El mod hace todo lo que puede por conectarte con tus amigos; en los raros casos en que hace falta configurar algo, te da una guía en condiciones.
- **También funciona con cuentas no premium.** Además del modo de siempre verificado por Mojang, un mundo puede funcionar en un modo en el que Azorea demuestra la identidad por su cuenta, para que un amigo sin login de Minecraft funcional pueda entrar igual, y vuestras skins y objetos se mantengan correctos.

## ¿Cómo funciona?

- **Primero hostea tu mundo existente por internet.**
- **Encuentra el camino de red solo**: un reenvío de puerto que ya tengas, luego UPnP, NAT-PMP, PCP y por último una dirección IPv6 pública.
- **Perfora una conexión directa** cuando no hay ningún puerto abierto: las dos máquinas se encuentran igual, sin relay, por apertura simultánea de TCP.
- **Firma tus invitaciones.** La cadena que mandas lleva las direcciones del host y una firma sobre ellas, así que puedes pegarla donde quieras y nadie en medio puede redirigirla a escondidas.
- **Descubre partidas en tu LAN** sin configurar nada: lo instalas en las dos máquinas y se ven.
- **Controla quién entra, por mundo**, en dos modos: premium, comprobado contra Mojang, o no-premium, comprobado por Azorea, con una lista de identidades que es del host y que él edita.
- **No lleva telemetría, ni cuentas, ni criptografía de terceros.** Todo el modelo de seguridad va sobre primitivas del JDK.

## Dónde conseguirlo

- **Modrinth** — próximamente <!-- TODO: https://modrinth.com/mod/azorea -->
- **CurseForge** — próximamente <!-- TODO: https://www.curseforge.com/minecraft/mc-mods/azorea -->
- **GitHub Releases** — [último jar](https://github.com/CerebroCanibalus/Azorea-Mod/releases)

Necesitas Minecraft 1.21.1 y NeoForge 21.1.250 o superior. Suelta el jar en tu carpeta `mods/`. Lo demás ya te lo sabes.

## Jugar con alguien

Abre un mundo y pulsa **B**, o haz clic en el pequeño **icono de Host** de la esquina superior derecha, en el menú de pausa o en la pantalla de título. La tecla se cambia como cualquier otra.

**Host Game** abre los ajustes de la sesión: modo de juego, dificultad, PvP, vuelo, trampas, MOTD y el modo de acceso del que hablo más abajo. Lo dejas como quieres que se juegue y le das a **Start**. Cuando la partida está en pie, **Copy Invite** te da una sola cadena. Mándala como prefieras: por Discord, por un mensaje, en un papel. Tu amigo pulsa **B**, elige **Join by Invite**, la pega y conecta.

La invitación lleva todo lo necesario para llegar a ti, así que no hay lobby que buscar ni tercero de por medio. Quien tiene la cadena tiene la entrada.

## ¡LÍMITES de red!

Para que dos máquinas lejanas se encuentren en directo, una tiene que ser alcanzable, y encontrar ese camino es justo donde casi todo el software «P2P» se rinde en silencio o miente. Azorea busca una abertura en el lado del host, por orden:

1. **Un reenvío de puerto que ya tengas** en TCP 25565.
2. **UPnP**, si el router deja que el mod abra el puerto por su cuenta.
3. **NAT-PMP y PCP**, los esquemas automáticos más viejos, también se prueban.
4. **Una dirección IPv6 pública**, que no necesita mapeo — va tal cual en la invitación.

La que conteste es la dirección que entra en la invitación. El otro lado contesta y, desde ese momento, es la conexión TCP directa de Minecraft y nada más.

Si no contesta ninguna —estás detrás de CGNAT y sin IPv6, por ejemplo— Azorea no se inventa una ruta. No hay relay de fábrica (aunque el mod tiene soporte para ello, por si lo quieres), así que la última opción es un reenvío de puerto manual o un relay de terceros fuera del mod.

## Mundos no-premium

**No-premium** sustituye la comprobación de Mojang por una que hace Azorea por su cuenta. Antes de aparecer, el jugador demuestra que tiene la clave Ed25519 detrás de su `azorea_id`, y el mundo del host lleva la cuenta de quién es quién en `identities.json`. Viene bien cuando alguien no puede entrar con Mojang, o cuando prefieres dejar el sistema de cuentas fuera del asunto.

En los dos casos el registro es del host. Quita una entrada si necesitas que un jugador vuelva a registrarse.

## En la misma red

Detrás de un solo router, Azorea encuentra a los vecinos él mismo. Lo instalas en las dos máquinas y **Browse Games** enseña las partidas locales, sin configurar nada y sin escribir direcciones.

Buscar por internet también se puede, pero hace falta un tracker: apuntas el mod a uno en `config/azorea.toml`. Cualquiera puede montarlo —el repositorio trae un `tracker-server/` suelto—, pero nosotros no te llevamos ninguno. ¡Puede ampliarse en el futuro!

## Un vistazo técnico

Azorea es un mod pequeño, y estas son las decisiones técnicas que lo llevan a serlo.

**Identidad.** Casi todo el multijugador empieza preguntándole a un servidor «¿quién es este?». Sin servidor al que preguntar, la identidad tiene que ser algo que puedas demostrar por tu cuenta. Tu `azorea_id` sale de tus dos pares de claves —Ed25519 para firmar, X25519 para el acuerdo de claves— junto con un hash de tu hardware. Nada lo asigna ni lo registra; cae de las claves. Quien tenga tu ID y tus claves públicas puede comprobar que los tres concuerdan, y que tú tienes la mitad privada, sin preguntarle a nadie. Como no hay registro, nadie puede ocupar tu ID antes que tú y ninguna autoridad puede revocarlo. El precio es que la identidad vive en un fichero local, así que perderlo es perderla; hay mejoras planeadas.

**Invitaciones.** La cadena que pega tu amigo es una dirección cifrada y firmada que lleva los endpoints del host, su Azorea ID y una firma Ed25519 sobre todo ello. Cuando el juego de tu amigo la lee, comprueba dos cosas: que la firma cuadra con la clave del host, y que el ID declarado sale de verdad de las claves del paquete. Entre las dos, un tercero que vea la invitación no puede reescribirla en silencio para apuntarte a su máquina, porque la firma cubre los endpoints y el ID está atado a las claves. Las invitaciones que van por un tracker, además, se cifran con la clave X25519 del destinatario usando ChaCha20-Poly1305, así que sólo el amigo al que van dirigidas puede leer las direcciones de dentro.

**Un punch que se queda en TCP.** Cuando no hay un puerto abierto al que conectarse, Azorea puede abrir uno por apertura simultánea: las dos máquinas envían desde un puerto elegido en el mismo instante, y cada NAT deja pasar el tráfico al ver el paquete de salida del otro. Muchas herramientas P2P bajan a UDP aquí y rehacen la fiabilidad a mano. Azorea se queda en TCP —un socket perforado es una conexión de verdad, y la retransmisión la lleva el sistema operativo— y un pequeño proxy local lo enlaza luego con Minecraft. Al juego en sí no se le toca nada.

**Primitivas del JDK.** Todo el modelo de seguridad —las firmas Ed25519, el acuerdo de claves X25519, el ChaCha20-Poly1305 de las invitaciones cifradas, el SHA-256 del hash de identidad— va sobre algoritmos que vienen dentro del JDK. No hay librería de criptografía externa que auditar, fijar ni actualizar. Si tienes cualquier duda o sugerencia de seguridad, cuéntala para seguir mejorando la seguridad de red de los usuarios.

El modelo de seguridad completo, sus supuestos y sus límites se pueden leer en [SECURITY.md](SECURITY.md).

## Compilar desde el código

Necesitas **JDK 21** y Git; Gradle llega con el wrapper.

```bash
git clone https://github.com/CerebroCanibalus/Azorea-Mod
cd azorea

./gradlew :v1_21_1:build         # el mod      → v1_21_1/build/libs/
./gradlew :tracker-server:build  # el tracker  → tracker-server/build/libs/
```

Para trabajar en él en un entorno de desarrollo:

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

Compila en Windows, macOS y Linux. Cada carpeta de ejecución va aislada, así que puedes probar dos identidades en la misma máquina.

## Contribuir

Los informes de fallos y las pull requests son siempre (¡¡¡¡¡NUNCA!!!!!! ¡¡¡¡¡MI CÓDIGO ES PERFECTO!!!!!!!!) bienvenidos. [CONTRIBUTING.md](CONTRIBUTING.md) es corto y merece la pena leerlo antes. Lo esencial:

- **Nada de infraestructura central.** No metas código que clave una URL a un servicio que tengamos que mantener. Es una línea de diseño, no un capricho.
- **Sin telemetría y sin llamadas a casa.**
- **GPL-3.0.** Al abrir una pull request licencias tu trabajo igual.
- **Java 21**, NeoForge 21.1, ModDevGradle.
- **Tests para todo lo sutil.** La identidad, las invitaciones y la puerta de acceso están cubiertas; sigue su ejemplo.

## Licencia

GPL-3.0. Mira [LICENSE](LICENSE).
