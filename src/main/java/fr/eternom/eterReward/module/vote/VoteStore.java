package fr.eternom.eterReward.module.vote;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;

import java.util.Map;
import java.util.UUID;

/**
 * Série de votes de chaque joueur, table eterreward_votes : jours de vote d'affilée et dernier jour de vote (jour
 * julien, LocalDate#toEpochDay). Le nombre de votes du mois est au classement (eterreward_ranking_monthly).
 * Table eterreward_vote_away : ce qui attend un joueur à sa prochaine arrivée (votes reçus hors ligne, clés dues).
 * Appels bloquants : hors du thread principal.
 */
public class VoteStore {

    private static final String TABLE = "votes";
    /** Ce qui attend le joueur à sa prochaine arrivée (eterreward_vote_away). */
    private static final String AWAY = "vote_away";

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
        database.createTable(AWAY,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("votes", Column.Type.INT).notNull(),
                Column.of("money", Column.Type.DOUBLE).notNull(),
                Column.of("pass_xp", Column.Type.INT).notNull(),
                Column.of("keys_given", Column.Type.INT).notNull(),
                Column.of("keys_owed", Column.Type.INT).notNull());
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

    /**
     * Ce qui attend le joueur à sa prochaine arrivée : le résumé des votes reçus hors ligne (déjà donnés, à lui dire) et
     * les clés pas encore données (vote reçu sur un serveur sans EterResource).
     */
    public record Away(int votes, double money, int passXp, int keysGiven, int keysOwed) {

        public boolean isEmpty() {
            return votes <= 0 && keysOwed <= 0;
        }
    }

    /** Ajoute à ce qui attend le joueur (une seule requête). */
    public void addAway(UUID player, Away away) {
        database.execute("INSERT INTO " + database.table(AWAY) + " (uuid, votes, money, pass_xp, keys_given, keys_owed)"
                        + " VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE votes = votes + ?, money = money + ?,"
                        + " pass_xp = pass_xp + ?, keys_given = keys_given + ?, keys_owed = keys_owed + ?",
                player, away.votes(), away.money(), away.passXp(), away.keysGiven(), away.keysOwed(),
                away.votes(), away.money(), away.passXp(), away.keysGiven(), away.keysOwed());
    }

    /**
     * Lit et retire ce qui attend le joueur ; les clés dues seulement si withKeys (EterResource ici). Retiré par
     * soustraction : un vote arrivé entre la lecture et le retrait reste pour la fois suivante.
     */
    public Away takeAway(UUID player, boolean withKeys) {
        Away away = database.getFirst(AWAY, Map.of("uuid", player))
                .map(row -> new Away(row.getInt("votes"), row.getDouble("money"), row.getInt("pass_xp"),
                        row.getInt("keys_given"), withKeys ? row.getInt("keys_owed") : 0))
                .orElse(new Away(0, 0, 0, 0, 0));
        if (away.isEmpty()) {
            return away;
        }
        database.execute("UPDATE " + database.table(AWAY) + " SET votes = votes - ?, money = money - ?, pass_xp = pass_xp - ?,"
                        + " keys_given = keys_given - ?, keys_owed = keys_owed - ? WHERE uuid = ?",
                away.votes(), away.money(), away.passXp(), away.keysGiven(), away.keysOwed(), player);
        database.execute("DELETE FROM " + database.table(AWAY) + " WHERE uuid = ? AND votes <= 0 AND keys_owed <= 0", player);
        return away;
    }
}
