package es.sund.sundscoreboardsync;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JDBC directo (via DriverManager) en vez de la API propia de
 * database-utils: el driver MySQL/MariaDB ya llega al classpath en tiempo
 * de ejecucion porque database-utils lo trae empaquetado (jar-in-jar,
 * "depends": "database-utils" en fabric.mod.json) -- esto solo aprovecha
 * ese driver ya presente, sin depender de metodos de conveniencia de esa
 * libreria cuya firma exacta no se pudo confirmar sin decompilador (ver
 * RESUME.md, tarea 1). Todas las escrituras corren en un executor de un
 * solo hilo, nunca en el hilo del servidor -- mismo principio de "cero
 * impacto en performance" que ya se aplico en sundauth.
 */
public final class ScoreboardDatabase {
    private final SundScoreboardConfig config;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sundscoreboardsync-db");
        t.setDaemon(true);
        return t;
    });
    private volatile Connection connection;

    public ScoreboardDatabase(SundScoreboardConfig config) {
        this.config = config;
    }

    private String jdbcUrl() {
        return "jdbc:mysql://" + config.dbHost + ":" + config.dbPort + "/" + config.dbName
                + "?useSSL=true&serverTimezone=UTC";
    }

    /** Conecta y crea la tabla si hace falta. Bloqueante -- se llama solo una vez, al arrancar el mod. */
    public void connectAndEnsureSchema() throws SQLException {
        connection = DriverManager.getConnection(jdbcUrl(), config.dbUser, config.dbPassword);
        try (PreparedStatement stmt = connection.prepareStatement("""
                CREATE TABLE IF NOT EXISTS player_scoreboards (
                    instance VARCHAR(32) NOT NULL,
                    player_name VARCHAR(64) NOT NULL,
                    player_uuid CHAR(36) NULL,
                    objective VARCHAR(64) NOT NULL,
                    score INT NOT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (instance, player_name, objective)
                )
                """)) {
            stmt.execute();
        }
    }

    private boolean ensureConnected() {
        try {
            if (connection != null && connection.isValid(2)) {
                return true;
            }
            connection = DriverManager.getConnection(jdbcUrl(), config.dbUser, config.dbPassword);
            return true;
        } catch (SQLException e) {
            SundScoreboardSync.LOGGER.warn("No se pudo (re)conectar a MySQL: {}", e.getMessage());
            return false;
        }
    }

    /** Encola una escritura async de un valor de scoreboard. Nunca bloquea el hilo llamante. */
    public void upsertAsync(String playerName, String playerUuid, String objective, int score) {
        executor.submit(() -> {
            if (!ensureConnected()) {
                return;
            }
            String sql = """
                    INSERT INTO player_scoreboards (instance, player_name, player_uuid, objective, score)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE player_uuid = VALUES(player_uuid), score = VALUES(score)
                    """;
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, config.instanceName);
                stmt.setString(2, playerName);
                stmt.setString(3, playerUuid);
                stmt.setString(4, objective);
                stmt.setInt(5, score);
                stmt.executeUpdate();
            } catch (SQLException e) {
                SundScoreboardSync.LOGGER.warn("Fallo al escribir scoreboard de {} ({}): {}", playerName, objective, e.getMessage());
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException ignored) {
        }
    }
}
