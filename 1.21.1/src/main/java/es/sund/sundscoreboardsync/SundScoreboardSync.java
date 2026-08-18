package es.sund.sundscoreboardsync;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sincroniza scoreboards de jugadores a MySQL. Solo escribe (no hay lectura
 * de vuelta al scoreboard todavia -- si hace falta restaurar progreso desde
 * la BD al entrar un jugador, es una fase 2 separada, no asumida aqui).
 *
 * Estrategia: no existe un evento de Fabric API para "un score cambio", asi
 * que se revisa el scoreboard entero cada N ticks (configurable,
 * syncIntervalTicks) y solo se escriben a MySQL los valores que cambiaron
 * desde la ultima pasada (cache en memoria) -- evita escrituras
 * redundantes. Las escrituras en si son async (ver ScoreboardDatabase),
 * nunca bloquean el hilo del servidor.
 *
 * ESPECÍFICO DE 1.21.1, no vive en ../../common: Mojang reescribió la API de
 * scoreboard entre 1.20.1 y 1.21.x. Diferencias reales frente a
 * ../../1.20.1/.../SundScoreboardSync.java (confirmadas con javap contra el
 * jar merged real de este mismo build, no adivinadas):
 * - Ya no hay {@code Scoreboard#getTrackedPlayers(): Collection<String>} +
 *   {@code hasPlayerScore(String, Objective)} + {@code getOrCreatePlayerScore
 *   (String, Objective): Score#getScore()}. El reemplazo directo,
 *   {@code getOrCreatePlayerScore(ScoreHolder, Objective): ScoreAccess}, no
 *   permite iterar "solo los objectives que ya tiene ese jugador" sin
 *   resolver primero un ScoreHolder por nombre.
 * - En vez de eso, {@code Scoreboard#listPlayerScores(Objective):
 *   Collection<PlayerScoreEntry>} da directamente pares (nombre, valor) por
 *   objective -- mismo recorrido lógico (por objective, por jugador con
 *   score en él) que la versión 1.20.1, sin necesitar ScoreHolder para leer.
 */
public final class SundScoreboardSync implements DedicatedServerModInitializer {
    public static final String MOD_ID = "sundscoreboardsync";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private SundScoreboardConfig config;
    private ScoreboardDatabase database;
    private final Map<String, Integer> lastSynced = new HashMap<>();
    private int tickCounter = 0;

    @Override
    public void onInitializeServer() {
        config = SundScoreboardConfig.loadOrCreate();

        if (!config.configured) {
            LOGGER.warn("sundscoreboardsync.properties sin rellenar (dbHost/dbName/dbUser vacios) -- el mod no hara nada hasta configurarlo.");
            return;
        }

        database = new ScoreboardDatabase(config);
        try {
            database.connectAndEnsureSchema();
            LOGGER.info("Conectado a MySQL ({}:{}/{}), instancia '{}'", config.dbHost, config.dbPort, config.dbName, config.instanceName);
        } catch (Exception e) {
            LOGGER.error("No se pudo conectar/crear la tabla en MySQL al arrancar -- se reintentara en cada sync.", e);
        }

        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            syncAll(server);
            if (database != null) {
                database.shutdown();
            }
        });
    }

    private void onServerTick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter < config.syncIntervalTicks) {
            return;
        }
        tickCounter = 0;
        syncAll(server);
    }

    private void syncAll(MinecraftServer server) {
        if (database == null) {
            return;
        }

        // Direccion BD -> juego primero, ver el mismo bloque en la version
        // 1.20.1 para la explicacion completa (lectura async + server.execute
        // + el push de mas abajo reafirmando source='game' cierra el ciclo).
        database.pullWebChangesAsync(changes -> server.execute(() -> applyWebChanges(server, changes)));

        ServerScoreboard scoreboard = server.getScoreboard();

        for (Objective objective : scoreboard.getObjectives()) {
            for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
                String playerName = entry.owner();
                int value = entry.value();

                String cacheKey = playerName + " " + objective.getName();
                Integer previous = lastSynced.get(cacheKey);
                if (previous != null && previous == value) {
                    continue;
                }
                lastSynced.put(cacheKey, value);

                String uuid = resolveUuid(server, playerName);
                database.upsertAsync(playerName, uuid, objective.getName(), value);
            }
        }
    }

    /** Solo resuelve UUID para jugadores conectados ahora mismo -- para offline queda NULL (ver RESUME.md). */
    private String resolveUuid(MinecraftServer server, String playerName) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
        return player != null ? player.getUUID().toString() : null;
    }

    /**
     * Aplica al scoreboard real cambios escritos externamente. Se ejecuta
     * ya en el hilo del servidor (ver la llamada en syncAll).
     *
     * Diferencias reales frente a la version 1.20.1 (confirmadas con
     * javap contra el jar merged real de este build, no adivinadas):
     * - addObjective ahora pide 6 argumentos, no 4: se anaden un boolean
     *   "autoUpdate" (false -- no aplica a un criterio DUMMY, ese flag es
     *   para criterios vanilla como HEALTH que el propio juego mantiene
     *   solo) y un NumberFormat opcional (null, mismo formato de numero
     *   por defecto que el resto del juego).
     * - Ya no hay getOrCreatePlayerScore(String, Objective): Score. El
     *   reemplazo es getOrCreatePlayerScore(ScoreHolder, Objective):
     *   ScoreAccess -- ScoreHolder.forNameOnly(String) crea uno valido
     *   para un nombre que no tiene por que estar online, exactamente
     *   igual de seguro para jugadores desconectados que el
     *   getOrCreatePlayerScore por String de 1.20.1.
     */
    private void applyWebChanges(MinecraftServer server, List<es.sund.sundscoreboardsync.WebScoreChange> changes) {
        ServerScoreboard scoreboard = server.getScoreboard();
        for (es.sund.sundscoreboardsync.WebScoreChange change : changes) {
            Objective objective = scoreboard.getObjective(change.objective());
            if (objective == null) {
                objective = scoreboard.addObjective(change.objective(), ObjectiveCriteria.DUMMY,
                        Component.literal(change.objective()), ObjectiveCriteria.RenderType.INTEGER,
                        false, null);
            }
            ScoreHolder holder = ScoreHolder.forNameOnly(change.playerName());
            ScoreAccess access = scoreboard.getOrCreatePlayerScore(holder, objective);
            access.set(change.score());
            LOGGER.info("Aplicado desde la web: {} {} = {}", change.playerName(), change.objective(), change.score());
        }
    }
}
