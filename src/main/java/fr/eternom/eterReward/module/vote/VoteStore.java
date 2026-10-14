package fr.eternom.eterReward.module.vote;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;

import java.util.Map;
import java.util.UUID;

/**
 * Série de votes de chaque joueur, table eterreward_votes : jours de vote d'affilée et dernier jour de vote (jour
 * julien, LocalDate#toEpochDay). Le nombre de votes du mois est au classement (eterreward_ranking_monthly).
 * Appels bloquants : hors du thread principal.
 */
public class VoteStore {

    private static final String TABLE = "votes";

    /** Série après ce vote, et si c'est le premier vote du joueur aujourd'hui. */
    public record Day(int streak, boolean first) {
    }

    private final Database database;

    public VoteStore(Database database) {
        this.database = database;
        database.createTable(TABLE,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("streak", Column.Type.INT).notNull(),
                Column.of("last_day", Column.Type.LONG).notNull());
    }

    /**
     * Enregistre un vote du jour today. La série avance au premier vote du jour (+1 si le dernier vote date d'hier,
     * sinon elle repart à 1) ; les votes suivants du même jour ne la changent pas. La condition last_day <> today est
     * dans la requête : deux votes au même instant (deux serveurs) ne comptent qu'une fois la journée.
     */
    public Day record(UUID player, long today) {
        // streak est calculé avant que last_day soit remplacé (MySQL applique le SET de gauche à droite)
        boolean first = database.execute("UPDATE " + database.table(TABLE) + " SET streak = IF(last_day = ?, streak + 1, 1),"
                + " last_day = ? WHERE uuid = ? AND last_day <> ?", today - 1, today, player, today) > 0
                || database.execute("INSERT IGNORE INTO " + database.table(TABLE) + " (uuid, streak, last_day) VALUES (?, 1, ?)",
                player, today) > 0;
        int streak = database.getFirst(TABLE, Map.of("uuid", player)).map(row -> row.getInt("streak")).orElse(1);
        return new Day(streak, first);
    }
}
