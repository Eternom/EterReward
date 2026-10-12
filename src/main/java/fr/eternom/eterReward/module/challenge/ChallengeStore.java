package fr.eternom.eterReward.module.challenge;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Les défis en base, communs au réseau. Appels bloquants : hors du thread principal.
 * - eterreward_challenge_periods : les défis tirés pour chaque période ("d:20373" pour un jour, "w:2026-41" pour une
 *   semaine), le premier serveur qui en a besoin les écrit ;
 * - eterreward_challenge_progress : l'avancée de chaque joueur et si le défi est validé (récompense donnée).
 */
public class ChallengeStore {

    private static final String PERIODS = "challenge_periods";
    private static final String PROGRESS = "challenge_progress";

    private final Database database;

    public ChallengeStore(Database database) {
        this.database = database;
        database.createTable(PERIODS,
                Column.of("period", Column.Type.STRING).length(16).primaryKey(),
                Column.of("challenges", Column.Type.STRING).length(255).notNull());
        database.createTable(PROGRESS,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("period", Column.Type.STRING).length(16).primaryKey(),
                Column.of("challenge", Column.Type.STRING).length(64).primaryKey(),
                Column.of("progress", Column.Type.INT).notNull(),
                Column.of("done", Column.Type.BOOLEAN).notNull());
    }

    public Optional<List<String>> period(String period) {
        return database.getFirst(PERIODS, Map.of("period", period))
                .map(row -> Arrays.asList(row.getString("challenges").split(",")));
    }

    /** Écrit les défis de la période s'ils n'y sont pas encore (le premier serveur gagne), puis les relit. */
    public List<String> createPeriod(String period, List<String> challenges) {
        database.execute("INSERT IGNORE INTO " + database.table(PERIODS) + " (period, challenges) VALUES (?, ?)",
                period, String.join(",", challenges));
        return period(period).orElse(challenges);
    }

    /** Avancée de chaque défi d'une période : challenge -> {progress, done}. */
    public Map<String, int[]> progress(UUID player, String period) {
        Map<String, int[]> progress = new HashMap<>();
        for (Row row : database.get(PROGRESS, Map.of("uuid", player, "period", period))) {
            progress.put(row.getString("challenge"), new int[]{row.getInt("progress"), row.getBoolean("done") ? 1 : 0});
        }
        return progress;
    }

    /** Ajoute à l'avancée (une seule requête, plusieurs serveurs possibles). */
    public void add(UUID player, String period, String challenge, int amount) {
        database.execute("INSERT INTO " + database.table(PROGRESS) + " (uuid, period, challenge, progress, done) VALUES (?, ?, ?, ?, FALSE)"
                + " ON DUPLICATE KEY UPDATE progress = progress + ?", player, period, challenge, amount, amount);
    }

    /** Valide le défi s'il est atteint et pas encore validé ; true pour le seul appel qui le valide (récompense). */
    public boolean complete(UUID player, String period, String challenge, int target) {
        return database.execute("UPDATE " + database.table(PROGRESS) + " SET done = TRUE"
                + " WHERE uuid = ? AND period = ? AND challenge = ? AND done = FALSE AND progress >= ?",
                player, period, challenge, target) > 0;
    }
}
