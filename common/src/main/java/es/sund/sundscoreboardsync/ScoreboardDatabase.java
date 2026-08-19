package es.sund.sundscoreboardsync;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

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
 *
 * Columna `source`: distingue una fila escrita por el propio juego
 * ('game', lo que este mod ya hacia) de una escrita externamente -- por
 * ejemplo por la web -- que todavia no se ha aplicado al scoreboard real
 * ('web'). El propio ciclo de sync la cierra solo: en cuanto el juego
 * adopta un valor 'web', la siguiente pasada de escritura periodica (ver
 * SundScoreboardSync) la vuelve a marcar 'game' con el valor ya vigente,
 * sin necesitar una columna de "aplicado" aparte.
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

    /**
     * Conecta y crea/actualiza la tabla si hace falta. Bloqueante -- se
     * llama solo una vez, al arrancar el mod.
     *
     * El ALTER TABLE que añade la columna `source` es deliberado y
     * separado del CREATE TABLE IF NOT EXISTS: una instalacion nueva ya
     * crea la columna en el CREATE, pero una tabla que ya existia en
     * produccion (SunD Origins la tiene desde antes de esta columna)
     * necesita el ALTER para adquirirla -- MySQL no altera
     * retroactivamente una tabla ya creada solo porque cambie el texto
     * de un CREATE TABLE IF NOT EXISTS.
     *
     * "ADD COLUMN IF NOT EXISTS" es sintaxis de MariaDB, no de MySQL
     * estandar -- reproducido contra la MySQL 8.0.46 real de produccion
     * (Contabo): revienta con SQLSyntaxErrorException, y como la columna
     * nunca llega a crearse, toda lectura/escritura posterior que la usa
     * falla en bucle con "Unknown column 'source'" cada ciclo de sync.
     * Portable de verdad: se comprueba antes contra information_schema.
     * columns si la columna ya existe (funciona igual en MySQL y
     * MariaDB) y solo se ejecuta el ALTER llano (sin IF NOT EXISTS) si
     * hace falta.
     */
    public void connectAndEnsureSchema() throws SQLException {
        connection = DriverManager.getConnection(jdbcUrl(), config.dbUser, config.dbPassword);
        try (PreparedStatement stmt = connection.prepareStatement("""
                CREATE TABLE IF NOT EXISTS player_scoreboards (
                    instance VARCHAR(32) NOT NULL,
                    player_name VARCHAR(64) NOT NULL,
                    player_uuid CHAR(36) NULL,
                    objective VARCHAR(64) NOT NULL,
                    score INT NOT NULL,
                    source ENUM('game','web') NOT NULL DEFAULT 'game',
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (instance, player_name, objective)
                )
                """)) {
            stmt.execute();
        }
        if (!columnExists("player_scoreboards", "source")) {
            try (PreparedStatement stmt = connection.prepareStatement(
                    "ALTER TABLE player_scoreboards ADD COLUMN "
                            + "source ENUM('game','web') NOT NULL DEFAULT 'game' AFTER score")) {
                stmt.execute();
            }
        }
    }

    /**
     * Comprobacion portable (MySQL y MariaDB) de si una columna ya
     * existe, contra information_schema.columns filtrando por la base de
     * datos actual (DATABASE()) en vez de asumir "IF NOT EXISTS" en el
     * propio ALTER, que MySQL estandar no soporta.
     */
    private boolean columnExists(String table, String column) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, table);
            stmt.setString(2, column);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
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

    /**
     * Encola una escritura async de un valor de scoreboard. Nunca bloquea
     * el hilo llamante. Siempre marca source='game' -- esta es la unica
     * via de escritura del propio juego, y debe reafirmar 'game' aunque la
     * fila estuviera marcada 'web' de una escritura externa todavia no
     * recogida (si ese es el caso, syncAll() ya la aplico al scoreboard
     * real antes de leer este mismo valor para el push, ver
     * SundScoreboardSync).
     */
    public void upsertAsync(String playerName, String playerUuid, String objective, int score) {
        executor.submit(() -> {
            if (!ensureConnected()) {
                return;
            }
            String sql = """
                    INSERT INTO player_scoreboards (instance, player_name, player_uuid, objective, score, source)
                    VALUES (?, ?, ?, ?, ?, 'game')
                    ON DUPLICATE KEY UPDATE player_uuid = VALUES(player_uuid), score = VALUES(score), source = 'game'
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

    /**
     * Lee de forma async las filas pendientes de aplicar al juego
     * (source='web') para esta instancia, y llama a {@code callback} con
     * la lista (nunca vacia -- si no hay nada pendiente, no se llama).
     * El callback se ejecuta en el hilo de la base de datos, igual que el
     * resto de este fichero: quien lo reciba es responsable de saltar de
     * vuelta al hilo del servidor antes de tocar el scoreboard real (ver
     * SundScoreboardSync.syncAll, que usa MinecraftServer#execute).
     */
    public void pullWebChangesAsync(Consumer<List<WebScoreChange>> callback) {
        executor.submit(() -> {
            if (!ensureConnected()) {
                return;
            }
            List<WebScoreChange> changes = new ArrayList<>();
            String sql = "SELECT player_name, objective, score FROM player_scoreboards WHERE instance = ? AND source = 'web'";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, config.instanceName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        changes.add(new WebScoreChange(rs.getString("player_name"), rs.getString("objective"), rs.getInt("score")));
                    }
                }
            } catch (SQLException e) {
                SundScoreboardSync.LOGGER.warn("Fallo al leer cambios pendientes desde la web: {}", e.getMessage());
                return;
            }
            if (!changes.isEmpty()) {
                callback.accept(changes);
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
