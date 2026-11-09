package fr.eternom.eterReward.module.pass;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Le pass en base, commun au réseau. Appels bloquants : hors du thread principal.
 * - pass_seasons : le pass de chaque mois (season = "2026-10"), et s'il a été annoncé ;
 * - pass_progress : XP de chaque joueur pour la saison, temps de jeu compté le dernier jour (et son plafond, report
 *   compris), dernier niveau annoncé (une seule annonce par palier, même avec deux gains au même moment) ;
 * - pass_claims : paliers réclamés (une ligne par palier et par ligne), avec le serveur et l'heure (litiges) ;
 * - pass_premium : le premium de chaque joueur pour chaque mois (boutique, cadeau, achat en jeu, clan, staff) ;
 * - pass_box : la boîte de colis (/pass colis) ;
 * - pass_xp_log : l'XP gagnée par heure et par source (/pass historique), gardée 7 jours ;
 * - pass_boosts : évènements (XP multipliée, temps de jeu sans plafond) ;
 * - pass_clan_goals : objectifs de clan atteints, une fois par mois.
 * Chaque changement est UNE requête qui vérifie elle-même ses conditions : deux serveurs ou deux clics au même moment ne
 * donnent jamais deux fois.
 */
public class PassStore {

    private static final String SEASONS = "pass_seasons";
    private static final String PROGRESS = "pass_progress";
    private static final String CLAIMS = "pass_claims";
    /** Ancienne file d'attente (avant les colis) : vidée dans la boîte au démarrage. */
    private static final String PENDING = "pass_pending";
    private static final String PREMIUM = "pass_premium";
    private static final String BOX = "pass_box";
    private static final String LOG = "pass_xp_log";
    private static final String BOOSTS = "pass_boosts";
    private static final String CLAN_GOALS = "pass_clan_goals";

    private final Database database;

    public PassStore(Database database) {
        this.database = database;
        database.createTable(SEASONS,
                Column.of("season", Column.Type.STRING).length(7).primaryKey(),
                Column.of("pass", Column.Type.STRING).length(64).notNull());
        database.addColumn(SEASONS, Column.of("announced", Column.Type.BOOLEAN));
        database.createTable(PROGRESS,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("season", Column.Type.STRING).length(7).primaryKey(),
                Column.of("xp", Column.Type.INT).notNull(),
                Column.of("play_day", Column.Type.LONG).notNull(),
                Column.of("play_xp", Column.Type.INT).notNull());
        database.addColumn(PROGRESS, Column.of("play_cap", Column.Type.INT));
        database.addColumn(PROGRESS, Column.of("notified", Column.Type.INT));
        database.createTable(CLAIMS,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("season", Column.Type.STRING).length(7).primaryKey(),
                Column.of("tier", Column.Type.INT).primaryKey(),
                Column.of("premium", Column.Type.BOOLEAN).primaryKey());
        database.addColumn(CLAIMS, Column.of("server", Column.Type.STRING).length(64));
        database.addColumn(CLAIMS, Column.of("claimed_at", Column.Type.LONG));
        database.createTable(PENDING,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("pass", Column.Type.STRING).length(64).primaryKey(),
                Column.of("tier", Column.Type.INT).primaryKey(),
                Column.of("premium", Column.Type.BOOLEAN).primaryKey());
        database.createTable(PREMIUM,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("season", Column.Type.STRING).length(7).primaryKey(),
                Column.of("source", Column.Type.STRING).length(64),
                Column.of("given_at", Column.Type.LONG));
        database.createTable(BOX,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("uuid", Column.Type.UUID).notNull(),
                Column.of("season", Column.Type.STRING).length(7),
                Column.of("label", Column.Type.TEXT),
                Column.of("money", Column.Type.DOUBLE),
                Column.of("resource_keys", Column.Type.INT),
                Column.of("resource_minutes", Column.Type.INT),
                Column.of("items", Column.Type.TEXT),
                Column.of("permissions", Column.Type.TEXT),
                Column.of("commands", Column.Type.TEXT),
                Column.of("special", Column.Type.STRING).length(128),
                Column.of("created", Column.Type.LONG));
        database.execute("CREATE INDEX IF NOT EXISTS eterreward_pass_box_uuid ON " + database.table(BOX) + " (uuid)");
        database.createTable(LOG,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("hour", Column.Type.LONG).primaryKey(),
                Column.of("source", Column.Type.STRING).length(24).primaryKey(),
                Column.of("amount", Column.Type.INT).notNull());
        database.createTable(BOOSTS,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("until", Column.Type.LONG).notNull(),
                Column.of("multiplier", Column.Type.DOUBLE).notNull(),
                Column.of("uncapped", Column.Type.BOOLEAN).notNull(),
                Column.of("label", Column.Type.STRING).length(128));
        database.createTable(CLAN_GOALS,
                Column.of("clan", Column.Type.LONG).primaryKey(),
                Column.of("season", Column.Type.STRING).length(7).primaryKey(),
                Column.of("step", Column.Type.INT).primaryKey());
    }

    // ---------- Saisons ----------

    public Optional<String> season(String season) {
        return database.getFirst(SEASONS, Map.of("season", season)).map(row -> row.getString("pass"));
    }

    /** Écrit le pass du mois s'il n'y en a pas encore (le premier serveur gagne), puis le relit. */
    public String createSeason(String season, String pass) {
        database.execute("INSERT IGNORE INTO " + database.table(SEASONS) + " (season, pass) VALUES (?, ?)", season, pass);
        return season(season).orElse(pass);
    }

    /** Commande du staff : force le pass d'un mois (déjà annoncé s'il est en cours). */
    public void setSeason(String season, String pass) {
        database.execute("INSERT INTO " + database.table(SEASONS) + " (season, pass) VALUES (?, ?) ON DUPLICATE KEY UPDATE pass = ?",
                season, pass, pass);
    }

    /** Marque la saison annoncée ; true pour le seul serveur qui la marque (il fait l'annonce). */
    public boolean markAnnounced(String season) {
        return database.execute("UPDATE " + database.table(SEASONS) + " SET announced = 1 WHERE season = ? AND announced IS NULL",
                season) > 0;
    }

    /** Au démarrage : les saisons passées (et celle en cours, sauf le 1er du mois) comptent comme annoncées. */
    public void markOldAnnounced(String before) {
        database.execute("UPDATE " + database.table(SEASONS) + " SET announced = 1 WHERE announced IS NULL AND season < ?", before);
    }

    // ---------- Progression ----------

    /** Ligne de progression d'un joueur (null s'il n'a encore rien gagné ce mois). */
    public record Progress(int xp, long playDay, int playXp, int playCap, Integer notified) {

        static final Progress NONE = new Progress(0, -1, 0, 0, null);

        static Progress of(Row row) {
            Object notified = row.get("notified");
            return new Progress(row.getInt("xp"), row.getLong("play_day"), row.getInt("play_xp"), row.getInt("play_cap"),
                    notified == null ? null : row.getInt("notified"));
        }
    }

    public Progress progress(UUID player, String season) {
        return database.getFirst(PROGRESS, Map.of("uuid", player, "season", season)).map(Progress::of).orElse(Progress.NONE);
    }

    public int xp(UUID player, String season) {
        return progress(player, season).xp();
    }

    /** Les lignes de plusieurs joueurs d'un coup (temps de jeu : une requête par minute pour tout le serveur). */
    public Map<UUID, Progress> progress(List<UUID> players, String season) {
        Map<UUID, Progress> result = new HashMap<>();
        if (players.isEmpty()) {
            return result;
        }
        List<Object> params = new ArrayList<>();
        params.add(season);
        StringJoiner in = new StringJoiner(", ", "(", ")");
        for (UUID player : players) {
            in.add("?");
            params.add(player);
        }
        for (Row row : database.query("SELECT * FROM " + database.table(PROGRESS) + " WHERE season = ? AND uuid IN " + in,
                params.toArray())) {
            result.put(row.getUUID("uuid"), Progress.of(row));
        }
        return result;
    }

    /** Ajoute de l'XP (quête, /daily, staff...). */
    public void addXp(UUID player, String season, int amount) {
        database.execute("INSERT INTO " + database.table(PROGRESS) + " (uuid, season, xp, play_day, play_xp) VALUES (?, ?, ?, -1, 0)"
                + " ON DUPLICATE KEY UPDATE xp = GREATEST(0, xp + ?)", player, season, amount, amount);
    }

    /** Fixe l'XP (staff). */
    public void setXp(UUID player, String season, int xp) {
        database.execute("INSERT INTO " + database.table(PROGRESS) + " (uuid, season, xp, play_day, play_xp, notified) VALUES (?, ?, ?, -1, 0, NULL)"
                + " ON DUPLICATE KEY UPDATE xp = ?, notified = NULL", player, season, xp, xp);
    }

    /** Une minute de temps de jeu calculée : XP gagnée, et l'état du jour après elle. */
    public record PlaytimeGain(UUID player, int gain, long day, int playXp, int playCap) {
    }

    /** Toutes les minutes du serveur en UNE requête. */
    public void addPlaytime(String season, List<PlaytimeGain> gains) {
        if (gains.isEmpty()) {
            return;
        }
        StringJoiner values = new StringJoiner(", ");
        List<Object> params = new ArrayList<>();
        for (PlaytimeGain gain : gains) {
            values.add("(?, ?, ?, ?, ?, ?)");
            params.addAll(List.of(gain.player(), season, gain.gain(), gain.day(), gain.playXp(), gain.playCap()));
        }
        database.execute("INSERT INTO " + database.table(PROGRESS) + " (uuid, season, xp, play_day, play_xp, play_cap) VALUES " + values
                + " ON DUPLICATE KEY UPDATE xp = xp + VALUES(xp), play_day = VALUES(play_day), play_xp = VALUES(play_xp),"
                + " play_cap = VALUES(play_cap)", params.toArray());
    }

    /**
     * Le niveau level est annoncé : true pour le seul appel qui l'enregistre (deux gains au même moment, deux serveurs :
     * une seule annonce).
     */
    public boolean markNotified(UUID player, String season, int level) {
        return database.execute("UPDATE " + database.table(PROGRESS) + " SET notified = ? WHERE uuid = ? AND season = ?"
                + " AND (notified IS NULL OR notified < ?)", level, player, season, level) > 0;
    }

    /** XP de tous les joueurs d'une saison (bilan, objectifs de clan). */
    public List<Integer> allXp(String season) {
        return database.query("SELECT xp FROM " + database.table(PROGRESS) + " WHERE season = ? AND xp > 0", season).stream()
                .map(row -> row.getInt("xp")).toList();
    }

    /** XP de quelques joueurs (les membres d'un clan) ; 0 pour ceux qui n'ont rien. */
    public Map<UUID, Integer> xpOf(List<UUID> players, String season) {
        Map<UUID, Integer> xp = new LinkedHashMap<>();
        players.forEach(player -> xp.put(player, 0));
        progress(players, season).forEach((player, progress) -> xp.put(player, progress.xp()));
        return xp;
    }

    // ---------- Réclamations ----------

    /** Paliers réclamés : "12" pour la ligne gratuite, "12p" pour la premium (31 et plus : paliers bonus). */
    public Set<String> claims(UUID player, String season) {
        Set<String> claimed = new HashSet<>();
        database.get(CLAIMS, Map.of("uuid", player, "season", season))
                .forEach(row -> claimed.add(row.getInt("tier") + (row.getBoolean("premium") ? "p" : "")));
        return claimed;
    }

    /** Enregistre la réclamation ; false si elle l'était déjà (double clic, autre serveur). */
    public boolean claim(UUID player, String season, int tier, boolean premium, String server) {
        return database.execute("INSERT IGNORE INTO " + database.table(CLAIMS)
                        + " (uuid, season, tier, premium, server, claimed_at) VALUES (?, ?, ?, ?, ?, ?)",
                player, season, tier, premium, server, System.currentTimeMillis()) > 0;
    }

    /** Annule une réclamation (don impossible, ou staff). */
    public boolean unclaim(UUID player, String season, int tier, boolean premium) {
        return database.delete(CLAIMS, Map.of("uuid", player, "season", season, "tier", tier, "premium", premium)) > 0;
    }

    /** Journal des réclamations d'un joueur (staff) : les plus récentes d'abord. */
    public List<Row> claimLog(UUID player, int limit) {
        return database.query("SELECT season, tier, premium, server, claimed_at FROM " + database.table(CLAIMS)
                + " WHERE uuid = ? ORDER BY claimed_at DESC LIMIT " + Math.max(1, limit), player);
    }

    /** Remet le joueur à zéro pour la saison (staff). */
    public void reset(UUID player, String season) {
        database.delete(PROGRESS, Map.of("uuid", player, "season", season));
        database.delete(CLAIMS, Map.of("uuid", player, "season", season));
    }

    // ---------- Premium ----------

    /** Donne le premium de ce mois ; false s'il l'avait déjà. */
    public boolean givePremium(UUID player, String season, String source) {
        return database.execute("INSERT IGNORE INTO " + database.table(PREMIUM) + " (uuid, season, source, given_at) VALUES (?, ?, ?, ?)",
                player, season, source, System.currentTimeMillis()) > 0;
    }

    public boolean hasPremium(UUID player, String season) {
        return database.getFirst(PREMIUM, Map.of("uuid", player, "season", season)).isPresent();
    }

    public int premiumCount(String season) {
        return database.query("SELECT COUNT(*) AS total FROM " + database.table(PREMIUM) + " WHERE season = ?", season)
                .stream().findFirst().map(row -> row.getInt("total")).orElse(0);
    }

    // ---------- Boîte de colis ----------

    public long addParcel(UUID player, Parcel parcel) {
        Map<String, Object> values = new HashMap<>();
        values.put("uuid", player);
        values.put("season", parcel.season());
        values.put("label", parcel.label());
        values.put("money", parcel.money());
        values.put("resource_keys", parcel.keys());
        values.put("resource_minutes", parcel.minutes());
        values.put("items", parcel.itemsText());
        values.put("permissions", parcel.permissionsText());
        values.put("commands", parcel.commandsText());
        values.put("special", parcel.special());
        values.put("created", System.currentTimeMillis());
        return database.insertReturningId(BOX, values);
    }

    public List<Parcel> parcels(UUID player) {
        return database.query("SELECT * FROM " + database.table(BOX) + " WHERE uuid = ? ORDER BY id", player).stream()
                .map(row -> new Parcel(row.getLong("id"), row.getString("season"), row.getString("label"), row.getDouble("money"),
                        row.getInt("resource_keys"), row.getInt("resource_minutes"), Parcel.parseItems(row.getString("items")),
                        Parcel.parsePermissions(row.getString("permissions")), Parcel.parseCommands(row.getString("commands")),
                        row.getString("special")))
                .toList();
    }

    public int parcelCount(UUID player) {
        return database.query("SELECT COUNT(*) AS total FROM " + database.table(BOX) + " WHERE uuid = ?", player)
                .stream().findFirst().map(row -> row.getInt("total")).orElse(0);
    }

    /** Retire le colis de la boîte ; true pour le seul appel qui le retire (il le donne). */
    public boolean takeParcel(UUID player, long id) {
        return database.delete(BOX, Map.of("uuid", player, "id", id)) > 0;
    }

    /** L'ancienne file d'attente (pass, palier, ligne), retirée de la base pour devenir des colis. */
    public record Pending(UUID player, String pass, int tier, boolean premium) {
    }

    public List<Pending> takeAllPending() {
        List<Pending> taken = new ArrayList<>();
        for (Row row : database.get(PENDING, Map.of())) {
            Pending pending = new Pending(row.getUUID("uuid"), row.getString("pass"), row.getInt("tier"), row.getBoolean("premium"));
            if (database.delete(PENDING, Map.of("uuid", pending.player(), "pass", pending.pass(), "tier", pending.tier(),
                    "premium", pending.premium())) > 0) {
                taken.add(pending);
            }
        }
        return taken;
    }

    // ---------- Journal d'XP ----------

    /** Ajoute amount à l'heure en cours pour cette source. */
    public void log(UUID player, String source, int amount) {
        log(List.of(player), source, amount);
    }

    public void log(List<UUID> players, String source, int amount) {
        if (players.isEmpty() || amount <= 0) {
            return;
        }
        long hour = System.currentTimeMillis() / 3_600_000L;
        StringJoiner values = new StringJoiner(", ");
        List<Object> params = new ArrayList<>();
        for (UUID player : players) {
            values.add("(?, ?, ?, ?)");
            params.addAll(List.of(player, hour, source, amount));
        }
        database.execute("INSERT INTO " + database.table(LOG) + " (uuid, hour, source, amount) VALUES " + values
                + " ON DUPLICATE KEY UPDATE amount = amount + VALUES(amount)", params.toArray());
    }

    /** XP gagnée par source depuis hours heures. */
    public Map<String, Integer> history(UUID player, int hours) {
        long from = System.currentTimeMillis() / 3_600_000L - hours + 1;
        Map<String, Integer> sums = new LinkedHashMap<>();
        for (Row row : database.query("SELECT source, SUM(amount) AS total FROM " + database.table(LOG)
                + " WHERE uuid = ? AND hour >= ? GROUP BY source ORDER BY total DESC", player, from)) {
            sums.put(row.getString("source"), row.getInt("total"));
        }
        return sums;
    }

    /** Le journal est gardé 7 jours. */
    public void purgeLog() {
        database.execute("DELETE FROM " + database.table(LOG) + " WHERE hour < ?", System.currentTimeMillis() / 3_600_000L - 7 * 24);
    }

    // ---------- Évènements ----------

    /** Un évènement en cours : XP multipliée, temps de jeu sans plafond. */
    public record Boost(long until, double multiplier, boolean uncapped, String label) {
    }

    public void addBoost(Boost boost) {
        database.insert(BOOSTS, Map.of("until", boost.until(), "multiplier", boost.multiplier(), "uncapped", boost.uncapped(),
                "label", boost.label()));
    }

    public List<Boost> boosts() {
        return database.query("SELECT * FROM " + database.table(BOOSTS) + " WHERE until > ?", System.currentTimeMillis()).stream()
                .map(row -> new Boost(row.getLong("until"), row.getDouble("multiplier"), row.getBoolean("uncapped"), row.getString("label")))
                .toList();
    }

    public void clearBoosts() {
        database.execute("DELETE FROM " + database.table(BOOSTS));
    }

    // ---------- Objectifs de clan ----------

    /** true pour le seul appel qui enregistre l'objectif atteint (il donne l'XP aux membres). */
    public boolean reachClanGoal(long clan, String season, int step) {
        return database.execute("INSERT IGNORE INTO " + database.table(CLAN_GOALS) + " (clan, season, step) VALUES (?, ?, ?)",
                clan, season, step) > 0;
    }
}
