package es.sund.sundscoreboardsync;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Mismo patron que config/sundauth.properties (ver
 * datos extra/Documentacion/sundauth-mod/README.md): se genera solo con
 * valores vacios/de aviso la primera vez que arranca el mod, y hay que
 * rellenarla a mano una vez por servidor.
 */
public final class SundScoreboardConfig {
    private static final String FILE_NAME = "sundscoreboardsync.properties";

    public final String dbHost;
    public final int dbPort;
    public final String dbName;
    public final String dbUser;
    public final String dbPassword;
    public final String instanceName;
    public final int syncIntervalTicks;
    public final boolean configured;

    private SundScoreboardConfig(Properties p) {
        this.dbHost = p.getProperty("dbHost", "");
        this.dbPort = parseIntOrDefault(p.getProperty("dbPort", "3306"), 3306);
        this.dbName = p.getProperty("dbName", "");
        this.dbUser = p.getProperty("dbUser", "");
        this.dbPassword = p.getProperty("dbPassword", "");
        this.instanceName = p.getProperty("instanceName", "unknown");
        this.syncIntervalTicks = parseIntOrDefault(p.getProperty("syncIntervalTicks", "600"), 600);
        this.configured = !dbHost.isBlank() && !dbName.isBlank() && !dbUser.isBlank();
    }

    private static int parseIntOrDefault(String raw, int def) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static SundScoreboardConfig loadOrCreate() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path file = configDir.resolve(FILE_NAME);
        Properties p = new Properties();

        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IOException e) {
                SundScoreboardSync.LOGGER.error("No se pudo leer {}", file, e);
            }
        } else {
            p.setProperty("dbHost", "");
            p.setProperty("dbPort", "3306");
            p.setProperty("dbName", "");
            p.setProperty("dbUser", "");
            p.setProperty("dbPassword", "");
            p.setProperty("instanceName", "unknown");
            p.setProperty("syncIntervalTicks", "600");
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "SunD Scoreboard Sync -- rellenar dbHost/dbName/dbUser/dbPassword e instanceName (SunDOrigins o CobbleSpain). syncIntervalTicks: cada cuantos ticks de servidor se revisa el scoreboard (20 ticks = 1s; 600 = 30s por defecto).");
            } catch (IOException e) {
                SundScoreboardSync.LOGGER.error("No se pudo crear {}", file, e);
            }
        }

        return new SundScoreboardConfig(p);
    }
}
