package fr.eternom.eterReward.module.ranking;

import fr.eternom.eterEconomy.api.EconomyApi;
import fr.eternom.eterLib.helper.message.Numbers;
import fr.eternom.eterLib.helper.ranking.RankingSource;
import fr.eternom.eterReward.module.ranking.RankingStore.Entry;
import org.bukkit.Material;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.LongUnaryOperator;

/**
 * Les classements. Les mensuels (général, pass, défis, quêtes, votes, temps de jeu) et le temps de jeu total sont à
 * EterReward ; les autres demandent leur classement
 * au plugin qui possède les données, par son API ou une source d'EterLib (RankingSource).
 */
public enum Boards {

    /** Moyenne des classements du mois (GeneralRanking). */
    GENERAL(Material.NETHERITE_HELMET, true),
    PASS(Material.NETHER_STAR, true),
    CHALLENGES(Material.TARGET, true),
    QUESTS(Material.WRITABLE_BOOK, true),
    /** Votes validés sur les sites de vote (plugin Vote du site, commande eterreward vote). */
    VOTES(Material.PAPER, true),
    /** Minutes de jeu actif (AFK exclu, PassActivity) du mois ; PLAYTIME_TOTAL : depuis toujours. */
    PLAYTIME(Material.CLOCK, true),
    SUCCESS(Material.GOLDEN_APPLE, false),
    PLAYTIME_TOTAL(Material.RECOVERY_COMPASS, false),
    WEALTH(Material.GOLD_BLOCK, false),
    CLANS(Material.WHITE_BANNER, false);

    private final Material icon;
    private final boolean monthly;

    Boards(Material icon, boolean monthly) {
        this.icon = icon;
        this.monthly = monthly;
    }

    /** Ce qu'il faut pour lire un classement : la base, le mois, et le palier du pass d'une XP (général). */
    record Context(RankingStore store, String season, LongUnaryOperator tierOf) {
    }

    public Material icon() {
        return icon;
    }

    /** Remis à zéro chaque mois. */
    public boolean monthly() {
        return monthly;
    }

    public String id() {
        return name().toLowerCase();
    }

    /** Ce classement a ses données sur ce serveur. */
    boolean available() {
        return switch (this) {
            case SUCCESS -> RankingSource.find("success").isPresent();
            case CLANS -> RankingSource.find("clans").isPresent();
            default -> true;
        };
    }

    /** Le top (bloquant). */
    List<Entry> top(Context context, int limit) {
        RankingStore store = context.store();
        String season = context.season();
        return switch (this) {
            case PASS -> store.topPass(season, limit);
            case CHALLENGES -> store.topChallenges(season, limit);
            case SUCCESS -> Source.top("success", limit);
            case WEALTH -> EconomyApi.get().map(api -> api.richest(limit).stream()
                    .map(ranked -> new Entry(ranked.player(), (long) Math.floor(ranked.value()))).toList()).orElse(List.of());
            case QUESTS, VOTES, PLAYTIME -> store.topMonthly(id(), season, limit);
            case PLAYTIME_TOTAL -> store.topMonthly(PLAYTIME.id(), RankingStore.ALL_TIME, limit);
            case GENERAL -> GeneralRanking.compute(context).stream().limit(limit).toList();
            case CLANS -> Source.top("clans", limit);
        };
    }

    /** {place (0 si absent), valeur} du joueur (bloquant). */
    long[] of(Context context, UUID player) {
        RankingStore store = context.store();
        String season = context.season();
        return switch (this) {
            case PASS -> store.passOf(player, season);
            case CHALLENGES -> store.challengesOf(player, season);
            case SUCCESS -> Source.of("success", player);
            case WEALTH -> EconomyApi.get().map(api -> new long[]{api.wealthRank(player), (long) Math.floor(api.balance(player))})
                    .orElse(new long[]{0, 0});
            case QUESTS, VOTES, PLAYTIME -> store.monthlyOf(player, id(), season);
            case PLAYTIME_TOTAL -> store.monthlyOf(player, PLAYTIME.id(), RankingStore.ALL_TIME);
            case GENERAL -> GeneralRanking.standings(GeneralRanking.compute(context)).getOrDefault(player, new long[]{0, 0});
            case CLANS -> Source.of("clans", player);
        };
    }

    /** Valeur affichée : le général est gardé en dixièmes (875 -> 87,5), le temps de jeu en minutes (754 -> 12 h 34). */
    String display(long value) {
        return switch (this) {
            case GENERAL -> String.format(Locale.FRANCE, "%.1f", value / 10.0);
            case PLAYTIME, PLAYTIME_TOTAL -> String.format(Locale.FRANCE, "%s h %02d", Numbers.format(value / 60), value % 60);
            default -> Numbers.format(value);
        };
    }

    /** Nom affiché d'une ligne (ex : le nom du clan), null pour le pseudo du joueur. */
    String label(Entry entry) {
        return this == CLANS ? RankingSource.find("clans").map(source -> source.label(new RankingSource.Ranked(entry.player(), entry.value())))
                .orElse(null) : null;
    }

    /** Icône d'une ligne à la place de la tête, null pour la tête. */
    Material lineIcon(Entry entry) {
        return this == CLANS ? Material.WHITE_BANNER : null;
    }

    /** Une source de classement d'EterLib, fournie par un autre plugin (succès, clans) sans qu'on dépende de lui. */
    private static final class Source {
        static List<Entry> top(String id, int limit) {
            return RankingSource.find(id).map(source -> source.top(limit).stream()
                    .map(ranked -> new Entry(ranked.player(), ranked.value())).toList()).orElse(List.of());
        }

        static long[] of(String id, UUID player) {
            return RankingSource.find(id).map(source -> source.of(player)).orElse(new long[]{0, 0});
        }
    }
}
