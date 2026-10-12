package fr.eternom.eterReward.module.ranking;

import fr.eternom.eterEconomy.api.EconomyApi;
import fr.eternom.eterMarket.api.MarketApi;
import fr.eternom.eterReward.module.ranking.RankingStore.Entry;
import fr.eternom.eterLib.helper.ranking.RankingSource;
import org.bukkit.Bukkit;
import org.bukkit.Material;

import java.util.List;
import java.util.UUID;

/**
 * Les classements. Les mensuels (pass, défis) sont à EterReward ; les autres demandent leur classement au plugin qui
 * possède les données, par son API (chaque plugin facultatif dans sa propre classe : sinon NoClassDefFoundError).
 */
public enum Boards {

    PASS(Material.NETHER_STAR, true),
    CHALLENGES(Material.TARGET, true),
    SUCCESS(Material.GOLDEN_APPLE, false),
    WEALTH(Material.GOLD_BLOCK, false),
    QUESTS(Material.WRITABLE_BOOK, false),
    CLANS(Material.WHITE_BANNER, false);

    private final Material icon;
    private final boolean monthly;

    Boards(Material icon, boolean monthly) {
        this.icon = icon;
        this.monthly = monthly;
    }

    public Material icon() {
        return icon;
    }

    /** Remis à zéro chaque mois, avec des récompenses pour le top 3. */
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
            case QUESTS -> Bukkit.getPluginManager().isPluginEnabled("EterMarket");
            case CLANS -> RankingSource.find("clans").isPresent();
            default -> true;
        };
    }

    /** Le top (bloquant). */
    List<Entry> top(RankingStore store, String season, int limit) {
        return switch (this) {
            case PASS -> store.topPass(season, limit);
            case CHALLENGES -> store.topChallenges(season, limit);
            case SUCCESS -> Success.top(limit);
            case WEALTH -> EconomyApi.get().map(api -> api.richest(limit).stream()
                    .map(ranked -> new Entry(ranked.player(), (long) Math.floor(ranked.value()))).toList()).orElse(List.of());
            case QUESTS -> Market.top(limit);
            case CLANS -> Source.top("clans", limit);
        };
    }

    /** {place (0 si absent), valeur} du joueur (bloquant). */
    long[] of(RankingStore store, String season, UUID player) {
        return switch (this) {
            case PASS -> store.passOf(player, season);
            case CHALLENGES -> store.challengesOf(player, season);
            case SUCCESS -> Success.of(player);
            case WEALTH -> EconomyApi.get().map(api -> new long[]{api.wealthRank(player), (long) Math.floor(api.balance(player))})
                    .orElse(new long[]{0, 0});
            case QUESTS -> Market.of(player);
            case CLANS -> Source.of("clans", player);
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

    /** Une source de classement d'EterLib (fournie par un autre plugin). */
    private static final class Source {
        static List<Entry> top(String id, int limit) {
            return RankingSource.find(id).map(source -> source.top(limit).stream()
                    .map(ranked -> new Entry(ranked.player(), ranked.value())).toList()).orElse(List.of());
        }

        static long[] of(String id, UUID player) {
            return RankingSource.find(id).map(source -> source.of(player)).orElse(new long[]{0, 0});
        }
    }

    /** Points de succès : la source de classement d'EterSuccess (EterLib), sans dépendre d'EterSuccess. */
    private static final class Success {
        static List<Entry> top(int limit) {
            return RankingSource.find("success").map(source -> source.top(limit).stream()
                    .map(ranked -> new Entry(ranked.player(), ranked.value())).toList()).orElse(List.of());
        }

        static long[] of(UUID player) {
            return RankingSource.find("success").map(source -> source.of(player)).orElse(new long[]{0, 0});
        }
    }

    private static final class Market {
        static List<Entry> top(int limit) {
            return MarketApi.get().map(api -> api.topQuests(limit).stream().map(ranked -> new Entry(ranked.player(), ranked.value())).toList())
                    .orElse(List.of());
        }

        static long[] of(UUID player) {
            return MarketApi.get().map(api -> {
                int rank = api.questRank(player);
                long value = rank == 0 ? 0 : api.topQuests(rank).getLast().value();
                return new long[]{rank, value};
            }).orElse(new long[]{0, 0});
        }
    }
}
