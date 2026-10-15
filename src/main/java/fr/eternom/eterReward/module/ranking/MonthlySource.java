package fr.eternom.eterReward.module.ranking;

import fr.eternom.eterLib.helper.ranking.RankingSource;

import java.util.List;
import java.util.UUID;

/**
 * Un classement du mois d'EterReward offert aux autres plugins par EterLib, sans qu'ils dépendent de lui : identifiant
 * « monthly_&lt;classement&gt; » (monthly_playtime, monthly_quests, monthly_challenges). EterClan s'en sert pour
 * l'activité de ses membres dans son classement des clans. Toujours le mois en cours.
 */
public class MonthlySource implements RankingSource {

    private final RankingService ranking;
    private final Boards board;

    public MonthlySource(RankingService ranking, Boards board) {
        this.ranking = ranking;
        this.board = board;
    }

    @Override
    public String id() {
        return "monthly_" + board.id();
    }

    @Override
    public List<Ranked> top(int limit) {
        return board.top(ranking.currentContext(), limit).stream().map(entry -> new Ranked(entry.player(), entry.value())).toList();
    }

    @Override
    public long[] of(UUID player) {
        return board.of(ranking.currentContext(), player);
    }
}
