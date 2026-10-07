package fr.eternom.eterReward.module.daily;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;

import java.util.Map;
import java.util.UUID;

/**
 * Progression de chaque joueur, table eterreward_daily : dernier jour réclamé du cycle (1 à 7) et date de cette
 * réclamation (jour julien, LocalDate#toEpochDay). Commune à tout le réseau : une récompense par jour, où que l'on soit.
 * Appels bloquants : hors du thread principal.
 */
public class DailyStore {

    private static final String TABLE = "daily";

    /** lastDay : dernier jour réclamé (0 : jamais) ; lastClaim : date de cette réclamation (-1 : jamais). */
    public record Progress(int lastDay, long lastClaim) {

        static final Progress NONE = new Progress(0, -1);
    }

    private final Database database;

    public DailyStore(Database database) {
        this.database = database;
        database.createTable(TABLE,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("day", Column.Type.INT).notNull(),
                Column.of("last_claim", Column.Type.LONG).notNull());
    }

    public Progress get(UUID player) {
        return database.getFirst(TABLE, Map.of("uuid", player))
                .map(row -> new Progress(row.getInt("day"), row.getLong("last_claim")))
                .orElse(Progress.NONE);
    }

    /**
     * Enregistre la réclamation, seulement si la progression n'a pas changé depuis sa lecture : deux clics (ou deux
     * serveurs) au même moment ne donnent qu'une récompense.
     * @return true si cette réclamation est celle qui compte
     */
    public boolean claim(UUID player, Progress read, int day, long today) {
        if (read.equals(Progress.NONE)) {
            return database.execute("INSERT IGNORE INTO " + database.table(TABLE) + " (uuid, day, last_claim) VALUES (?, ?, ?)",
                    player, day, today) > 0;
        }
        return database.execute("UPDATE " + database.table(TABLE) + " SET day = ?, last_claim = ? WHERE uuid = ? AND last_claim = ?",
                day, today, player, read.lastClaim()) > 0;
    }
}
