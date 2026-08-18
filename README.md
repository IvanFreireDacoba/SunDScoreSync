# SunD Scoreboard Sync

Mod de Fabric, **solo servidor**, que sincroniza los scoreboards de los
jugadores con una base de datos MySQL/MariaDB, de forma asíncrona (nunca
bloquea el hilo principal del servidor).

Pensado originalmente para los servidores de SunD Studios, pero sin nada
específico de ese proyecto: funciona en cualquier servidor Fabric con
acceso a una base de datos MySQL/MariaDB propia.

No requiere nada en el cliente — solo se instala en el servidor.

## Qué hace

- Cada `syncIntervalTicks` (configurable) recorre los objetivos de
  scoreboard activos y guarda una fila por jugador/objetivo en una tabla
  `player_scoreboards`, creándola sola si no existe.
- Solo escritura: hoy no relee valores desde la base de datos hacia el
  scoreboard del juego.
- Todas las escrituras van en un hilo aparte dedicado a la base de datos,
  con reconexión automática si se pierde la conexión.

## Estructura

Código compartido en `common/` (sin dependencias de Minecraft: lectura de
config y acceso a la base de datos) y un subproyecto Gradle independiente
por versión soportada de Minecraft:

```
common/     Config y capa de base de datos, compartidos tal cual.
1.20.1/     Build de Gradle/Loom para Minecraft 1.20.1.
1.21.1/     Build de Gradle/Loom para Minecraft 1.21.1.
```

La única clase que no se comparte es el entrypoint (`SundScoreboardSync`,
uno por versión): Mojang reescribió la API de scoreboard entre 1.20.1 y
1.21.x (`String` → `ScoreHolder`, `Score#getScore()` → `ScoreAccess`
separado de `Scoreboard`), así que cada versión tiene su propia
implementación con el mismo resultado final.

## Compilar

Requiere JDK 21+ (el runtime del propio servidor puede ser otro, esto es
solo para compilar).

```bash
cd 1.20.1 && ./gradlew build   # jar en 1.20.1/build/libs/
cd ../1.21.1 && ./gradlew build   # jar en 1.21.1/build/libs/
```

## Instalación

1. Descarga el jar de la versión de Minecraft que uses desde
   [Releases](https://github.com/IvanFreireDacoba/SunDScoreSync/releases)
   y colócalo en `mods/`, junto a Fabric API.
2. Copia también `database-utils-1.0.0.jar` (incluido en `libs/` de cada
   subproyecto de este repo) a `mods/` — es una dependencia necesaria.
3. Arranca el servidor una vez para que se genere
   `config/sundscoreboardsync.properties` con valores vacíos.
4. Rellena esa config con los datos de tu propia base de datos:

   ```properties
   dbHost=localhost
   dbPort=3306
   dbName=mi_base_de_datos
   dbUser=mi_usuario
   dbPassword=mi_contraseña
   instanceName=mi_servidor
   syncIntervalTicks=600
   ```

   `instanceName` es solo una etiqueta para distinguir servidores si varios
   comparten la misma base de datos; `syncIntervalTicks` son ticks de
   servidor (20 = 1 segundo).
5. Reinicia el servidor.

## Releases

`v1.0.0-mc1.20.1` y `v1.0.0-mc1.21.1`, cada una con su jar ya compilado.

## Contribuciones

Se aceptan sugerencias y peticiones de funciones — abre un
[issue](https://github.com/IvanFreireDacoba/SunDScoreSync/issues).

También se aceptan **pull requests**, siempre que vengan **en una rama
nueva** (nunca directamente contra `main`).

## Licencia

MIT.
