package fr.eternom.eterReward.module.ranking;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Les classements mensuels d'EterReward, en base. Appels bloquants : hors du thread principal.
 * - XP du pass : lu dans eterreward_pass_progress (la saison = le mois) ;
 * - eterreward_challenge_monthly : défis réussis par mois ;
 * - eterreward_ranking_monthly : autres valeurs du mois par classement (quêtes de métier...) ;
 * - eterreward_ranking_rewards : récompenses de fin de mois déjà données (un seul serveur les donne).
 */
public class RankingStore {

    private static final String PASS = "pass_progress";
    private static final String CHALLENGES = "challenge_monthly";
    private static final String REWARDS = "ranking_rewards";
    private static final String MONTHLY = "ranking_monthly";

    /** Un joueur et sa valeur. */
    public record Entry(UUID player, long value) {
    }

    private final Database database;

    public RankingStore(Database database) {
        this.database = database;
        database.createTable(CHALLENGES,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("season", Column.Type.STRING).length(7).primaryKey(),
                Column.of("completed", Column.Type.INT).notNull());
        database.createTable(MONTHLY,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("season", Column.Type.STRING).length(7).primaryKey(),
                Column.of("board", Column.Type.STRING).length(32).primaryKey(),
                Column.of("amount", Column.Type.LONG).notNull());
        database.createTable(REWARDS,
                Column.of("season", Column.Type.STRING).length(7).primaryKey(),
                Column.of("board", Column.Type.STRING).length(32).primaryKey(),
                Column.of("given_at", Column.Type.LONG).notNull());
    }

    /** Un défi réussi ce mois-ci. */
    public void addChallenge(UUID player, String season) {
        database.execute("INSERT INTO " + database.table(CHALLENGES) + " (uuid, season, completed) VALUES (?, ?, 1)"
                + " ON DUPLICATE KEY UPDATE completed = completed + 1", player, season);
    }

    /** Une action de plus ce mois-ci dans un classement (ex : "quests"). */
    public void addMonthly(UUID player, String season, String board) {
        database.execute("INSERT INTO " + database.table(MONTHLY) + " (uuid, season, board, amount) VALUES (?, ?, ?, 1)"
                + " ON DUPLICATE KEY UPDATE amount = amount + 1", player, season, board);
    }

    public List<Entry> topMonthly(String board, String season, int limit) {
        return database.query("SELECT uuid, amount FROM " + database.table(MONTHLY) + " WHERE season = ? AND board = ? AND amount > 0"
                + " ORDER BY amount DESC LIMIT ?", season, board, limit).stream()
                .map(row -> new Entry(row.getUUID("uuid"), row.getLong("amount"))).toList();
    }

    public long[] monthlyOf(UUID player, String board, String season) {
        long mine = database.getFirst(MONTHLY, Map.of("uuid", player, "season", season, "board", board))
                .map(row -> row.getLong("amount")).orElse(0L);
        if (mine <= 0) {
            return new long[]{0, 0};
        }
        long above = database.query("SELECT COUNT(*) AS above FROM " + database.table(MONTHLY)
                + " WHERE season = ? AND board = ? AND amount > ?", season, board, mine).getFirst().getLong("above");
        return new long[]{above + 1, mine};
    }

    /** Toutes les valeurs du mois (classement général) : XP du pass, défis, ou un classement de ranking_monthly. */
    public List<Entry> allPass(String season) {
        return top(PASS, "xp", season, Integer.MAX_VALUE);
    }

    public List<Entry> allChallenges(String season) {
        return top(CHALLENGES, "completed", season, Integer.MAX_VALUE);
    }

    public List<Entry> allMonthly(String board, String season) {
        return topMonthly(board, season, Integer.MAX_VALUE);
    }

    public List<Entry> topPass(String season, int limit) {
        return top(PASS, "xp", season, limit);
    }

    public List<Entry> topChallenges(String season, int limit) {
        return top(CHALLENGES, "completed", season, limit);
    }

    public long[] passOf(UUID player, String season) {
        return rankOf(PASS, "xp", player, season);
    }

    public long[] challengesOf(UUID player, String season) {
        return rankOf(CHALLENGES, "completed", player, season);
    }

    /** Réserve les récompenses de fin de mois d'un classement ; false si un autre serveur les a déjà données. */
    public boolean claimRewards(String season, String board) {
        return database.execute("INSERT IGNORE INTO " + database.table(REWARDS) + " (season, board, given_at) VALUES (?, ?, ?)",
                season, board, System.currentTimeMillis()) > 0;
    }

    private List<Entry> top(String table, String column, String season, int limit) {
        return database.query("SELECT uuid, " + column + " AS value FROM " + database.table(table)
                        + " WHERE season = ? AND " + column + " > 0 ORDER BY " + column + " DESC LIMIT ?", season, limit).stream()
                .map(row -> new Entry(row.getUUID("uuid"), row.getLong("value")))
                .toList();
    }

    /** {place (0 si rien), valeur} du joueur. */
    private long[] rankOf(String table, String column, UUID player, String season) {
        long mine = database.getFirst(table, Map.of("uuid", player, "season", season)).map(row -> row.getLong(column)).orElse(0L);
        if (mine <= 0) {
            return new long[]{0, 0};
        }
        long above = database.query("SELECT COUNT(*) AS above FROM " + database.table(table) + " WHERE season = ? AND " + column + " > ?",
                season, mine).getFirst().getLong("above");
        return new long[]{above + 1, mine};
    }
}
