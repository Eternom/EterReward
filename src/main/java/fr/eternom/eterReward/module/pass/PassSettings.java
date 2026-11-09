package fr.eternom.eterReward.module.pass;

import org.bukkit.configuration.ConfigurationSection;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Réglages du pass (config.yml > pass). Relus par /eterreward pass reload.
 *
 * @param curve           XP des paliers et paliers bonus
 * @param capDays         plafond de temps de jeu multiplié certains jours (samedi, dimanche...)
 * @param carryOver       part du plafond non utilisé la veille reportée sur le jour (0 : rien)
 * @param questXp         easy, normal, hard, bonus
 * @param overflowMoney   récompense de chaque palier bonus
 * @param catchUpEnd      palier « attendu » en fin de mois pour le rattrapage (0 : pas de rattrapage)
 * @param graceDays       jours pour réclamer le pass du mois d'avant
 * @param lateBuyDays     premium acheté dans les derniers jours du mois : le mois suivant est offert
 * @param drawAheadDays   le pass du mois suivant est tiré ce nombre de jours avant la fin du mois
 * @param previewDays     aperçu du pass suivant ce nombre de jours avant la fin du mois
 * @param noRepeat        un pass ne revient pas avant ce nombre de mois
 * @param premiumPrice    premium acheté en jeu (0 : pas d'achat en jeu)
 * @param clanDiscount    réduction du premium acheté pour tout un clan (0,25 = -25 %)
 * @param shopUrl         lien de la boutique (bouton premium)
 * @param successXp       XP du pass par point de succès débloqué (EterSuccess)
 * @param worldXp         XP de temps de jeu multipliée dans certains mondes (préfixe du nom)
 * @param clanGoals       objectifs de clan : palier moyen des membres -> XP pour chacun
 * @param discordWebhook  annonces et bilans sur Discord (vide : non)
 */
public record PassSettings(XpCurve curve, int playtimeXpPerMinute, int playtimeCap, Map<DayOfWeek, Double> capDays,
                           double carryOver, int dailyXp, Map<String, Integer> questXp,
                           double overflowMoney, int overflowKeys,
                           int catchUpEnd, int catchUpMargin, double catchUpMultiplier,
                           int graceDays, int lateBuyDays, int drawAheadDays, int previewDays, int noRepeat,
                           double premiumPrice, double clanDiscount, int clanMinMembers, String shopUrl, boolean badge,
                           double successXp, Map<String, Double> worldXp, List<ClanGoal> clanGoals,
                           boolean announce, String discordWebhook) {

    /** Un objectif de clan : quand le palier moyen des membres atteint averageTier, chacun gagne xp (une fois par mois). */
    public record ClanGoal(int averageTier, int xp) {
    }

    public static PassSettings load(ConfigurationSection config, int tiers, Logger logger) {
        ConfigurationSection pass = config.getConfigurationSection("pass");
        if (pass == null) {
            pass = config.createSection("pass");
        }
        List<Integer> costs = new ArrayList<>();
        for (Object value : pass.getList("xp-curve", List.of())) {
            try {
                costs.add(Math.max(1, Integer.parseInt(String.valueOf(value).trim())));
            } catch (NumberFormatException e) {
                logger.warning("pass.xp-curve : valeur ignorée : " + value);
            }
        }
        XpCurve curve = new XpCurve(costs, Math.max(1, pass.getInt("xp-per-tier", 1200)), tiers,
                pass.getInt("overflow.xp", 2400), pass.getInt("overflow.max", 20));

        Map<String, Integer> questXp = new HashMap<>();
        for (String tier : List.of("easy", "normal", "hard", "bonus")) {
            questXp.put(tier, Math.max(0, pass.getInt("quest-xp." + tier)));
        }
        Map<DayOfWeek, Double> capDays = new EnumMap<>(DayOfWeek.class);
        ConfigurationSection days = pass.getConfigurationSection("playtime-cap-days");
        if (days != null) {
            for (String day : days.getKeys(false)) {
                try {
                    capDays.put(DayOfWeek.valueOf(day.toUpperCase(Locale.ROOT)), Math.max(0, days.getDouble(day)));
                } catch (IllegalArgumentException e) {
                    logger.warning("pass.playtime-cap-days : jour inconnu : " + day + " (MONDAY ... SUNDAY)");
                }
            }
        }
        Map<String, Double> worldXp = new HashMap<>();
        ConfigurationSection worlds = pass.getConfigurationSection("world-xp");
        if (worlds != null) {
            for (String world : worlds.getKeys(false)) {
                worldXp.put(world.toLowerCase(Locale.ROOT), Math.max(0, worlds.getDouble(world)));
            }
        }
        List<ClanGoal> goals = new ArrayList<>();
        for (Map<?, ?> goal : pass.getMapList("clan-goals")) {
            try {
                goals.add(new ClanGoal(Integer.parseInt(String.valueOf(goal.get("average-tier"))),
                        Integer.parseInt(String.valueOf(goal.get("xp")))));
            } catch (NumberFormatException e) {
                logger.warning("pass.clan-goals : objectif ignoré : " + goal);
            }
        }
        goals.sort((a, b) -> Integer.compare(a.averageTier(), b.averageTier()));

        return new PassSettings(curve,
                Math.max(0, pass.getInt("playtime-xp-per-minute", 5)),
                Math.max(0, pass.getInt("playtime-daily-cap", 500)),
                Map.copyOf(capDays),
                Math.clamp(pass.getDouble("playtime-carry-over", 0.5), 0, 1),
                Math.max(0, pass.getInt("daily-xp", 300)),
                Map.copyOf(questXp),
                Math.max(0, pass.getDouble("overflow.money", 150)),
                Math.max(0, pass.getInt("overflow.keys", 0)),
                Math.max(0, pass.getInt("catch-up.expected-end-tier", 22)),
                Math.max(0, pass.getInt("catch-up.margin", 3)),
                Math.max(1, pass.getDouble("catch-up.multiplier", 2)),
                Math.max(0, pass.getInt("grace-days", 7)),
                Math.max(0, pass.getInt("premium.late-buy-days", 5)),
                Math.max(0, pass.getInt("draw-ahead-days", 6)),
                Math.max(0, pass.getInt("preview-days", 3)),
                Math.max(1, pass.getInt("no-repeat-months", 3)),
                Math.max(0, pass.getDouble("premium.price", 0)),
                Math.clamp(pass.getDouble("premium.clan-discount", 0.25), 0, 0.9),
                Math.max(1, pass.getInt("premium.clan-min-members", 3)),
                pass.getString("premium.shop-url", "").trim(),
                pass.getBoolean("premium.badge", true),
                Math.max(0, pass.getDouble("success-xp-per-point", 10)),
                Map.copyOf(worldXp),
                List.copyOf(goals),
                pass.getBoolean("announce", true),
                pass.getString("discord-webhook", "").trim());
    }

    public int questXp(String tier) {
        return questXp.getOrDefault(tier, 0);
    }

    /** Multiplicateur du temps de jeu dans ce monde (préfixe du nom, ex : "ressource"), 1 sinon. */
    public double worldMultiplier(String world) {
        String name = world.toLowerCase(Locale.ROOT);
        return worldXp.entrySet().stream().filter(entry -> name.startsWith(entry.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse(1.0);
    }
}
