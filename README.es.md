# Azorea

*Multijugador directo para Minecraft 1.21.1, sin un servidor que mantengamos.*

Azorea es un mod de NeoForge que junta dos mundos de un jugador por internet. Tú abres el mundo que ya juegas, tu amigo pega una sola cadena y —cuando la red se deja— las dos máquinas se hablan en directo, sin nada nuestro en medio.

No hay cuenta que crear ni servidor nuestro encendido. Eso último condiciona todo lo demás: como de nuestro lado no hay infraestructura, tampoco hay nada que se pueda caer, cambiar de manos ni empezar a cobrar por lo que antes hacía gratis.

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

## Por qué existe Azorea

Si alguna vez has querido jugar a Minecraft con un solo amigo, te has encontrado con las mismas tres opciones, y ninguna encaja en una tarde suelta. Alquilas un servidor, que cuesta dinero y necesita a alguien que lo lleve. Usas un mod o un servicio de hosteo, que funciona pero casi siempre pide una cuenta y manda tu partida por infraestructura que controla otro. O te conformas y juegas solo.

Azorea quiere ser una cuarta opción: dos personas, una invitación y una conexión que es de ellos y de nadie más. Está montado sobre una única regla —no operar nada— y casi toda la ingeniería interesante sale de esa regla. Un mod sin servidor detrás tiene que hacer por su cuenta lo que si no haría un servidor: identificar a los jugadores, repartir las invitaciones y encontrar un camino entre dos redes domésticas que nunca estuvieron pensadas para hablarse.

Esa decisión tiene un precio, y el proyecto prefiere nombrarlo antes que esconderlo. Cuando dos redes no se alcanzan de verdad, no aparece una ruta de la nada. Dónde cae esa frontera lo cuento aquí abajo.

## Jugar con alguien

Abre un mundo y pulsa **B**, o haz clic en el pequeño **icono de Host** de la esquina superior derecha, en el menú de pausa o en la pantalla de título. La tecla se cambia como cualquier otra.

**Host Game** abre los ajustes de la sesión: modo de juego, dificultad, PvP, vuelo, trampas, MOTD y el modo de acceso del que hablo más abajo. Lo dejas como quieres que se juegue y le das a **Start**. Cuando la partida está en pie, **Copy Invite** te da una sola cadena. Mándala como prefieras: por Discord, por un mensaje, en un papel. Tu amigo pulsa **B**, elige **Join by Invite**, la pega y conecta.

La invitación lleva todo lo necesario para llegar a ti, así que no hay lobby que buscar ni tercero de por medio. Quien tiene la cadena tiene la entrada.

## Lo que tu red permite

Para que dos máquinas lejanas se encuentren en directo, una tiene que ser alcanzable, y encontrar ese camino es justo donde casi todo el software «P2P» se rinde en silencio o miente. Azorea busca una abertura en el lado del host, por orden:

1. **Un reenvío de puerto que ya tengas** en TCP 25565.
2. **UPnP**, si el router deja que el mod abra el puerto por su cuenta.
3. **NAT-PMP y PCP**, los esquemas automáticos más viejos, también se prueban.
4. **Una dirección IPv6 pública**, que no necesita mapeo — va tal cual en la invitación.

La que conteste es la dirección que entra en la invitación. El otro lado contesta y, desde ese momento, es la conexión TCP directa de Minecraft y nada más.

Si no contesta ninguna —estás detrás de CGNAT y sin IPv6, por ejemplo— Azorea no se inventa una ruta. Todavía no hay relay, así que el consejo sincero es un reenvío de puerto, IPv6 o un relay de terceros fuera del mod. Ese es el precio de no operar infraestructura, y la pantalla de hosteo lo dice claro en vez de dar vueltas a un círculo de carga sobre una conexión que nunca va a ocurrir.

## Mundos premium y no-premium

**Premium**, lo de fábrica, funciona como un servidor con online-mode: el mundo comprueba a cada jugador contra Mojang. Tu amigo necesita un login de Minecraft normal y que funcione.

**No-premium** sustituye esa comprobación por una que hace Azorea por su cuenta. Antes de aparecer, el jugador demuestra que tiene la clave Ed25519 detrás de su `azorea_id`, y el mundo del host lleva la cuenta de quién es quién en `identities.json`. Viene bien cuando alguien no puede entrar con Mojang, o cuando prefieres dejar el sistema de cuentas fuera del asunto.

En los dos casos el registro es del host. Quitas una entrada y ese jugador vuelve a registrarse; no hay baneos clavados ni bloqueos para siempre.

## En la misma red

Detrás de un solo router, Azorea encuentra a los vecinos él mismo. Lo instalas en las dos máquinas y **Browse Games** enseña las partidas locales, sin configurar nada y sin escribir direcciones.

Buscar por internet también se puede, pero hace falta un tracker: apuntas el mod a uno en `config/azorea.toml`. Cualquiera puede montarlo —el repositorio trae un `tracker-server/` suelto—, pero nosotros no te llevamos ninguno.

## ¿Funciona ya?

Sí por el camino previsto, y no para cualquier red. Sin rodeos:

| Situación | |
|---|---|
| La misma LAN | ✅ |
| Remoto, el host con reenvío de puerto o IPv6 público | ✅ |
| Remoto, el host tras CGNAT y sin IPv6 | ❌ todavía sin relay |
| Buscar partidas por internet | necesita tu propio tracker |
| Comprobación de identidad en mundos no-premium | ✅ |

Las dos primeras filas son lo que hemos probado de punta a punta, con unos miles de kilómetros de por medio y hasta una docena de jugadores. La tercera es un límite real del diseño actual, no un descuido.

## Un vistazo técnico

Azorea es un mod pequeño, pero unas cuantas decisiones suyas merecen contarse, porque son las que le permiten funcionar sin un servidor propio.

**Identidad sin cuentas.** Casi todo el multijugador empieza preguntándole a un servidor «¿quién es este?». Sin servidor al que preguntar, la identidad tiene que ser algo que puedas demostrar por tu cuenta. Tu `azorea_id` sale de tus dos pares de claves —Ed25519 para firmar, X25519 para el acuerdo de claves— junto con un hash de tu hardware. Nada lo asigna ni lo registra; cae de las claves. Quien tenga tu ID y tus claves públicas puede comprobar que los tres concuerdan, y que tú tienes la mitad privada, sin preguntarle a nadie. Como no hay registro, nadie puede ocupar tu ID antes que tú y ninguna autoridad puede revocarlo. El precio es que la identidad vive en un fichero local, así que perderlo es perderla — un respaldo está en la lista de pendientes.

**Invitaciones que llevan su propia prueba.** La cadena que pega tu amigo no es sólo una dirección; es una declaración firmada. Lleva los endpoints del host, su identidad y una firma Ed25519 sobre todo ello. Cuando el juego de tu amigo la lee, comprueba dos cosas: que la firma cuadra con la clave del host, y que el ID declarado sale de verdad de las claves del paquete. Entre las dos, un tercero que vea la invitación no puede reescribirla en silencio para apuntarte a su máquina, porque la firma cubre los endpoints y el ID está atado a las claves. Las invitaciones que van por un tracker, además, se cifran con la clave X25519 del destinatario usando ChaCha20-Poly1305, así que sólo el amigo al que van dirigidas puede leer las direcciones de dentro.

**Un punch que se queda en TCP.** Cuando no hay un puerto abierto al que conectarse, Azorea puede abrir uno por apertura simultánea: las dos máquinas envían desde un puerto elegido en el mismo instante, y cada NAT deja pasar el tráfico al ver el paquete de salida del otro. Muchas herramientas P2P bajan a UDP aquí y rehacen la fiabilidad a mano. Azorea se queda en TCP —un socket perforado es una conexión de verdad, y la retransmisión la lleva el sistema operativo— y un pequeño proxy local lo enlaza luego con Minecraft. Al juego en sí no se le toca nada.

**Primitivas del JDK.** Todo el modelo de seguridad —las firmas Ed25519, el acuerdo de claves X25519, el ChaCha20-Poly1305 de las invitaciones cifradas, el SHA-256 del hash de identidad— va sobre algoritmos que vienen dentro del JDK. No hay librería de criptografía externa que auditar, fijar ni actualizar.

**Una puerta antes de que el mundo se abra.** En los mundos que no comprueban contra Mojang, el host hace su propia comprobación de identidad antes de que nadie aparezca. El mod reta al otro lado a firmar un valor aleatorio con la clave detrás de su `azorea_id`, y luego verifica tanto la firma como la derivación durante la fase de configuración de Minecraft, que termina antes de que el mundo admita a nadie. La comprobación ocurre antes de la puerta, no después.

El modelo de seguridad completo, sus supuestos y sus límites están en [SECURITY.md](SECURITY.md).

## Compilar desde el código

Necesitas **JDK 21** y Git; Gradle llega con el wrapper.

```bash
git clone https://github.com/CerebroCanibalus/azorea
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

Los informes de fallos y las pull requests son bienvenidos. [CONTRIBUTING.md](CONTRIBUTING.md) es corto y merece la pena leerlo antes. Lo esencial:

- **Nada de infraestructura central.** No metas código que clave una URL a un servicio que tengamos que mantener. Es una línea de diseño, no un capricho.
- **Sin telemetría y sin llamadas a casa.**
- **GPL-3.0.** Al abrir una pull request licencias tu trabajo igual.
- **Java 21**, NeoForge 21.1, ModDevGradle.
- **Tests para todo lo sutil.** La identidad, las invitaciones y la puerta de acceso están cubiertas; sigue su ejemplo.

## Licencia

GPL-3.0. Mira [LICENSE](LICENSE).
