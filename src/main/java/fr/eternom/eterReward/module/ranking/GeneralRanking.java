package fr.eternom.eterReward.module.ranking;

import fr.eternom.eterReward.module.ranking.RankingStore.Entry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongUnaryOperator;

/**
 * Le classement général du mois : seulement ce qui est fait dans le mois (un nouveau joueur part à égalité), sur trois
 * classements : paliers du pass (premium ou non), défis réussis, quêtes de métier réussies. Dans chacun, une note sur
 * 100 selon la PLACE (pas la valeur : aucun classement n'écrase les autres) : 1er sur N = 100, dernier = 100/N, absent
 * = 0. Le général est la moyenne des trois notes, gardée en dixièmes (875 = 87,5 points).
 */
final class GeneralRanking {

    private GeneralRanking() {
    }

    /** Tout le classement, du premier au dernier (bloquant). tierOf : XP du pass -> palier. */
    static List<Entry> compute(RankingStore store, String season, LongUnaryOperator tierOf) {
        List<Entry> tiers = store.allPass(season).stream().map(entry -> new Entry(entry.player(), tierOf.applyAsLong(entry.value())))
                .filter(entry -> entry.value() > 0).toList();
        List<List<Entry>> boards = List.of(tiers, store.allChallenges(season), store.allMonthly(Boards.QUESTS.id(), season));
        Map<UUID, Double> total = new HashMap<>();
        for (List<Entry> board : boards) {
            notes(board).forEach((player, note) -> total.merge(player, note, Double::sum));
        }
        List<Entry> general = new ArrayList<>();
        total.forEach((player, sum) -> general.add(new Entry(player, Math.round(sum / boards.size() * 10))));
        general.sort(Comparator.comparingLong(Entry::value).reversed().thenComparing(Entry::player));
        return general;
    }

    /** Note sur 100 de chaque joueur classé : les ex aequo ont la même place. */
    private static Map<UUID, Double> notes(List<Entry> board) {
        List<Entry> sorted = board.stream().filter(entry -> entry.value() > 0)
                .sorted(Comparator.comparingLong(Entry::value).reversed()).toList();
        Map<UUID, Double> notes = new HashMap<>();
        int size = sorted.size();
        int place = 0;
        for (int i = 0; i < size; i++) {
            if (i == 0 || sorted.get(i).value() != sorted.get(i - 1).value()) {
                place = i + 1;
            }
            notes.put(sorted.get(i).player(), 100.0 * (size - place + 1) / size);
        }
        return notes;
    }

    /** Places (1, 2, 2, 4...) du classement général trié. */
    static Map<UUID, long[]> standings(List<Entry> general) {
        Map<UUID, long[]> standings = new HashMap<>();
        int place = 0;
        for (int i = 0; i < general.size(); i++) {
            if (i == 0 || general.get(i).value() != general.get(i - 1).value()) {
                place = i + 1;
            }
            standings.put(general.get(i).player(), new long[]{place, general.get(i).value()});
        }
        return standings;
    }
}
