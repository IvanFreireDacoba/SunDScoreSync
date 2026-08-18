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
- Bidireccional desde la versión 1.1.0: además de juego → BD, también
  aplica al scoreboard real cualquier fila que llegue marcada como
  externa (ver "Escribir desde fuera del juego" más abajo).
- Todas las escrituras/lecturas van en un hilo aparte dedicado a la base
  de datos, con reconexión automática si se pierde la conexión; la
  aplicación de un cambio externo al scoreboard real salta de vuelta al
  hilo del servidor justo antes de tocarlo (es la única parte que tiene
  que serlo).

## Escribir desde fuera del juego (BD → juego)

La tabla tiene una columna `source ENUM('game','web')`. El mod solo
escribe `source='game'` (lo que ya hacía antes de la 1.1.0). Cualquier
sistema externo — tu web, un script, lo que sea con acceso a la base de
datos — puede hacer que un valor "migre" al juego real insertando o
actualizando una fila con `source='web'`:

```sql
INSERT INTO player_scoreboards (instance, player_name, objective, score, source)
VALUES ('mi_servidor', 'NombreJugador', 'mi_objetivo', 42, 'web')
ON DUPLICATE KEY UPDATE score = VALUES(score), source = 'web';
```

En la siguiente pasada de sync (como mucho `syncIntervalTicks`, y hasta un
ciclo más por el salto asíncrono BD↔servidor) el mod:

- Crea el `objective` en el juego si todavía no existe (criterio `DUMMY`,
  el mismo que usa cualquier scoreboard "libre" creado por comando).
- Aplica el valor al jugador indicado, **exista o no esté conectado ahora
  mismo** — no hace falta que el jugador esté online.
- En la siguiente pasada de push (juego → BD), ese mismo valor se
  reafirma solo como `source='game'` — no hace falta marcar nada como
  "ya aplicado" a mano, el propio ciclo cierra el proceso.

No hace falta ningún trigger de MySQL/MariaDB ni lógica extra en la base
de datos: es el propio mod, en su ciclo periódico normal, el que revisa
si hay filas `source='web'` pendientes antes de hacer su push habitual.

**Migrar una tabla que ya existe en producción**: la columna `source` se
añade sola al arrancar el mod (`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`,
seguro de ejecutar aunque la tabla ya tenga datos) — no hace falta tocar
nada a mano.

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

`v1.1.0-mc1.20.1` y `v1.1.0-mc1.21.1` (añaden la sincronización BD → juego),
`v1.0.0-mc1.20.1` y `v1.0.0-mc1.21.1` (solo juego → BD), cada una con su
jar ya compilado.

## Contribuciones

Se aceptan sugerencias y peticiones de funciones — abre un
[issue](https://github.com/IvanFreireDacoba/SunDScoreSync/issues).

También se aceptan **pull requests**, siempre que vengan **en una rama
nueva** (nunca directamente contra `main`).

## Licencia

Licencia propia de SunD Studios ([`LICENSE`](LICENSE)), inspirada en el
principio ShareAlike de Creative Commons: cualquier derivado debe
distribuirse con esta misma licencia y siempre de forma gratuita — nadie
puede cobrar por el mod en sí (descarga, copia o instalación). Se puede
usar libremente en servidores de pago (rangos, cosméticos, suscripción...)
siempre que no se cobre por distribuir el mod en sí. Dar crédito se
agradece pero no es obligatorio; lo que sí está prohibido es apropiarse de
la autoría.
