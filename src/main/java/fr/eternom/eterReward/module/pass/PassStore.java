package fr.eternom.eterReward.module.pass;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Le pass en base, commun au réseau. Appels bloquants : hors du thread principal.
 * - eterreward_pass_seasons : le pass tiré pour chaque mois (season = "2026-10"), un seul par mois ;
 * - eterreward_pass_progress : XP de chaque joueur pour la saison, et le temps de jeu déjà compté aujourd'hui (plafond) ;
 * - eterreward_pass_claims : paliers réclamés (une ligne par palier et par ligne gratuite ou premium).
 * Chaque changement est UNE requête qui vérifie elle-même ses conditions : deux serveurs ou deux clics au même moment ne
 * donnent jamais deux fois.
 */
public class PassStore {

    private static final String SEASONS = "pass_seasons";
    private static final String PROGRESS = "pass_progress";
    private static final String CLAIMS = "pass_claims";
    /** Récompenses réclamées dont les objets et commandes n'ont pas pu être donnés (joueur parti) : à sa prochaine arrivée. */
    private static final String PENDING = "pass_pending";

    private final Database database;

    public PassStore(Database database) {
        this.database = database;
        database.createTable(SEASONS,
                Column.of("season", Column.Type.STRING).length(7).primaryKey(),
                Column.of("pass", Column.Type.STRING).length(64).notNull());
        database.createTable(PROGRESS,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("season", Column.Type.STRING).length(7).primaryKey(),
                Column.of("xp", Column.Type.INT).notNull(),
                Column.of("play_day", Column.Type.LONG).notNull(),
                Column.of("play_xp", Column.Type.INT).notNull());
        database.createTable(CLAIMS,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("season", Column.Type.STRING).length(7).primaryKey(),
                Column.of("tier", Column.Type.INT).primaryKey(),
                Column.of("premium", Column.Type.BOOLEAN).primaryKey());
        database.createTable(PENDING,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("pass", Column.Type.STRING).length(64).primaryKey(),
                Column.of("tier", Column.Type.INT).primaryKey(),
                Column.of("premium", Column.Type.BOOLEAN).primaryKey());
    }

    public Optional<String> season(String season) {
        return database.getFirst(SEASONS, Map.of("season", season)).map(row -> row.getString("pass"));
    }

    /** Écrit le pass du mois s'il n'y en a pas encore (le premier serveur gagne), puis le relit. */
    public String createSeason(String season, String pass) {
        database.execute("INSERT IGNORE INTO " + database.table(SEASONS) + " (season, pass) VALUES (?, ?)", season, pass);
        return season(season).orElse(pass);
    }

    /** Commande du staff : force le pass du mois. */
    public void setSeason(String season, String pass) {
        database.set(SEASONS, Map.of("season", season, "pass", pass), "season");
    }

    public int xp(UUID player, String season) {
        return database.getFirst(PROGRESS, Map.of("uuid", player, "season", season)).map(row -> row.getInt("xp")).orElse(0);
    }

    /** Ajoute de l'XP (quête, /daily, staff). */
    public void addXp(UUID player, String season, int amount) {
        database.execute("INSERT INTO " + database.table(PROGRESS) + " (uuid, season, xp, play_day, play_xp) VALUES (?, ?, ?, -1, 0)"
                + " ON DUPLICATE KEY UPDATE xp = xp + ?", player, season, amount, amount);
    }

    /**
     * Ajoute de l'XP de temps de jeu, sans dépasser cap par jour. Les colonnes sont mises à jour de gauche à droite :
     * xp d'abord (avec l'ancien compteur du jour), puis le compteur, puis le jour.
     */
    public void addPlaytime(UUID player, String season, int amount, long day, int cap) {
        int first = Math.min(amount, cap);
        database.execute("INSERT INTO " + database.table(PROGRESS) + " (uuid, season, xp, play_day, play_xp) VALUES (?, ?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE"
                        + " xp = xp + LEAST(?, GREATEST(0, IF(play_day = ?, ? - play_xp, ?))),"
                        + " play_xp = IF(play_day = ?, LEAST(?, play_xp + ?), LEAST(?, ?)),"
                        + " play_day = ?",
                player, season, first, day, first,
                amount, day, cap, cap,
                day, cap, amount, cap, amount,
                day);
    }

    /** Paliers réclamés : "12" pour la ligne gratuite, "12p" pour la premium. */
    public Set<String> claims(UUID player, String season) {
        Set<String> claimed = new HashSet<>();
        database.get(CLAIMS, Map.of("uuid", player, "season", season))
                .forEach(row -> claimed.add(row.getInt("tier") + (row.getBoolean("premium") ? "p" : "")));
        return claimed;
    }

    /** Enregistre la réclamation ; false si elle l'était déjà (double clic, autre serveur). */
    public boolean claim(UUID player, String season, int tier, boolean premium) {
        return database.execute("INSERT IGNORE INTO " + database.table(CLAIMS) + " (uuid, season, tier, premium) VALUES (?, ?, ?, ?)",
                player, season, tier, premium) > 0;
    }

    /** Une récompense dont les objets et commandes attendent le joueur. */
    public record Pending(String pass, int tier, boolean premium) {
    }

    public void addPending(UUID player, Pending pending) {
        database.execute("INSERT IGNORE INTO " + database.table(PENDING) + " (uuid, pass, tier, premium) VALUES (?, ?, ?, ?)",
                player, pending.pass(), pending.tier(), pending.premium());
    }

    /** Les récompenses en attente, retirées de la base une à une : un même colis n'est donné qu'une fois (deux serveurs). */
    public List<Pending> takePending(UUID player) {
        List<Pending> taken = new ArrayList<>();
        for (var row : database.get(PENDING, Map.of("uuid", player))) {
            Pending pending = new Pending(row.getString("pass"), row.getInt("tier"), row.getBoolean("premium"));
            if (database.delete(PENDING, Map.of("uuid", player, "pass", pending.pass(), "tier", pending.tier(),
                    "premium", pending.premium())) > 0) {
                taken.add(pending);
            }
        }
        return taken;
    }
}
