package es.sund.sundscoreboardsync;

/** Una fila pendiente de aplicar al juego, escrita externamente (source='web'). */
public record WebScoreChange(String playerName, String objective, int score) {
}
