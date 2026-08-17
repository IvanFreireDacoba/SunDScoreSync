package es.sund.sundscoreboardsync;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
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
}
