# SunD Scoreboard Sync

Mod Fabric que sincroniza scoreboards de jugadores a MySQL (Contabo, BD
`SunD_Origins`/`SunD_CobbleSpain` según servidor, la misma que ya usa
LuckPerms). Contexto completo, decisiones de diseño y estado real: ver
`datos extra/Documentacion/RESUME.md` en el repo de SunDLauncher/web.

**Estado**: en producción en SunD Origins (1.20.1) desde 2026-08-14.
Portado a 1.21.1 (CobbleSpain) y publicado como release `1.0.0` en ambas
versiones el 2026-08-17 — mismo patrón de dos builds que
[SunDAuthSystem](https://github.com/IvanFreireDacoba/SunDAuthSystem).

## Estructura

Igual patrón que `sundauth-fabric`: código compartido en `common/`, un
subproyecto Gradle independiente por versión de Minecraft.

- `common/src/main/java/.../SundScoreboardConfig.java` — lee/genera
  `config/sundscoreboardsync.properties`. Sin dependencias de Minecraft,
  compartido tal cual entre las dos versiones.
- `common/src/main/java/.../ScoreboardDatabase.java` — JDBC directo
  (`DriverManager`, no la API propia de `database-utils` — ver RESUME.md
  para el porqué), escrituras async. Tampoco depende de clases de
  Minecraft, compartido tal cual.
- `1.20.1/src/main/java/.../SundScoreboardSync.java` y
  `1.21.1/src/main/java/.../SundScoreboardSync.java` — entrypoint, bucle de
  sync cada `syncIntervalTicks`. **No están en `common`**: Mojang reescribió
  la API de scoreboard entre 1.20.1 y 1.21.x (`String` -> `ScoreHolder`,
  `Score#getScore()` -> `ScoreAccess` separado de
  `Scoreboard#listPlayerScores`), así que cada versión tiene su propia
  implementación de la misma lógica (mismo resultado final: mismas filas en
  `player_scoreboards`).

## Compilar

Cada versión es un proyecto Gradle independiente (mismo motivo que
`sundauth-fabric`: cada `./gradlew` remapea contra los mappings oficiales
de Mojang de esa versión de Minecraft exacta).

```bash
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2   # o cualquier JDK 21+, el JRE del sistema no trae javac
export PATH="$JAVA_HOME/bin:$PATH"

cd 1.20.1 && ./gradlew build   # jar en 1.20.1/build/libs/sundscoreboardsync-<version>.jar
cd ../1.21.1 && ./gradlew build   # jar en 1.21.1/build/libs/sundscoreboardsync-<version>.jar
```

## Desplegar (cambio de producción, no automático)

1. Copiar `build/libs/sundscoreboardsync-*.jar` (sin el `-sources.jar`) a
   `mods/` del servidor correspondiente (SunD Origins o CobbleSpain).
2. Confirmar que `database-utils-1.0.0.jar` sigue en `mods/` (dependencia,
   ver `libs/database-utils-1.0.0.jar` de cada subproyecto).
3. Confirmar que `config/sundscoreboardsync.properties` de ese servidor
   tiene credenciales reales (SunD Origins ya las tiene, ver RESUME.md;
   CobbleSpain lo genera vacío al primer arranque, hay que rellenarlo a
   mano igual que `config/sundauth.properties`).
4. Comprobar jugadores conectados (`logs/latest.log`) antes de reiniciar.
5. `sudo systemctl restart <servicio>` (sin sudo sin contraseña en esta
   máquina -- pedir al usuario que lo ejecute con `!`).
6. Verificar en el log: `Conectado a MySQL (...)` sin errores.

## Releases

Publicadas en este repo (privado): `v1.0.0-mc1.20.1` (SunD Origins) y
`v1.0.0-mc1.21.1` (CobbleSpain), cada una con su jar ya compilado.

## Pendiente

- Confirmar con el usuario qué datos exactos hay que persistir además de
  scoreboards sueltos (progreso de clase, puntos de habilidad, maestría
  de armas) antes de tocar el esquema de `player_scoreboards`.
- Resolver UUID también para jugadores offline (hoy solo se resuelve si
  están conectados en el momento del sync).
- CobbleSpain: nunca se ha probado contra un servidor real (el jar de
  1.21.1 está compilado y verificado, pero sin poder levantar Minecraft
  desde este entorno de trabajo, mismo límite que sundauth-fabric).
