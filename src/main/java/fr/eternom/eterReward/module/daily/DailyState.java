package fr.eternom.eterReward.module.daily;

import fr.eternom.eterReward.module.daily.DailyStore.Progress;

/**
 * Où en est un joueur aujourd'hui, d'après sa progression :
 * - déjà réclamé aujourd'hui : rien à réclamer jusqu'à demain ;
 * - réclamé hier (et le cycle n'est pas fini) : la série continue, jour suivant ;
 * - sinon (jamais, jour manqué, cycle de 7 jours terminé) : la série repart au jour 1.
 *
 * @param claimed   jours déjà réclamés dans le cycle affiché (0 à 7)
 * @param claimable jour à réclamer aujourd'hui (1 à 7), 0 s'il est déjà réclamé
 */
public record DailyState(int claimed, int claimable, Progress progress) {

    public static final int CYCLE = 7;

    public static DailyState of(Progress progress, long today) {
        if (progress.lastClaim() == today) {
            return new DailyState(progress.lastDay(), 0, progress);
        }
        if (progress.lastClaim() == today - 1 && progress.lastDay() < CYCLE) {
            return new DailyState(progress.lastDay(), progress.lastDay() + 1, progress);
        }
        return new DailyState(0, 1, progress);
    }

    public boolean canClaim() {
        return claimable > 0;
    }

    /** Une série était en cours mais un jour a été manqué : elle repart de zéro. */
    public boolean streakLost(long today) {
        return progress.lastClaim() >= 0 && progress.lastClaim() < today - 1 && progress.lastDay() < CYCLE;
    }
}
