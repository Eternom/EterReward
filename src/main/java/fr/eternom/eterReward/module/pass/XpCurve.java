package fr.eternom.eterReward.module.pass;

import java.util.List;

/**
 * L'XP des paliers (logique pure, testée) : chaque palier a son coût (pass.xp-curve, sinon pass.xp-per-tier ; la
 * dernière valeur de la courbe vaut pour les paliers suivants), puis des paliers bonus répétables au-delà du dernier
 * (pass.overflow : un tous les overflowXp, overflowMax au plus).
 * « Niveau » = palier + paliers bonus : c'est lui qui déclenche les annonces.
 */
public final class XpCurve {

    /** thresholds[i] : XP totale pour atteindre le palier i (thresholds[0] = 0). */
    private final int[] thresholds;
    private final int overflowXp;
    private final int overflowMax;

    public XpCurve(List<Integer> costs, int defaultCost, int tiers, int overflowXp, int overflowMax) {
        this.thresholds = new int[tiers + 1];
        int total = 0;
        for (int tier = 1; tier <= tiers; tier++) {
            int cost = costs.isEmpty() ? defaultCost : costs.get(Math.min(tier - 1, costs.size() - 1));
            total += Math.max(1, cost);
            thresholds[tier] = total;
        }
        this.overflowXp = Math.max(0, overflowXp);
        this.overflowMax = overflowXp > 0 ? Math.max(0, overflowMax) : 0;
    }

    public int tiers() {
        return thresholds.length - 1;
    }

    /** XP totale pour atteindre ce palier (0 pour le palier 0 ; au-delà du dernier : les paliers bonus). */
    public int threshold(int tier) {
        if (tier <= 0) {
            return 0;
        }
        if (tier <= tiers()) {
            return thresholds[tier];
        }
        return thresholds[tiers()] + (tier - tiers()) * Math.max(1, overflowXp);
    }

    /** Palier atteint (0 à tiers()). */
    public int tier(int xp) {
        int tier = 0;
        while (tier < tiers() && xp >= thresholds[tier + 1]) {
            tier++;
        }
        return tier;
    }

    /** Paliers bonus atteints au-delà du dernier palier (0 à overflowMax). */
    public int overflow(int xp) {
        int beyond = xp - thresholds[tiers()];
        if (overflowXp <= 0 || beyond < overflowXp) {
            return 0;
        }
        return Math.min(overflowMax, beyond / overflowXp);
    }

    public int overflowMax() {
        return overflowMax;
    }

    /** Palier + paliers bonus. */
    public int level(int xp) {
        return tier(xp) + overflow(xp);
    }

    public int maxLevel() {
        return tiers() + overflowMax;
    }

    /** XP gagnée dans le niveau en cours, et XP du niveau entier : la barre de progression. {0, 0} si tout est atteint. */
    public int[] progress(int xp) {
        int level = level(xp);
        if (level >= maxLevel()) {
            return new int[]{0, 0};
        }
        int from = threshold(level);
        return new int[]{xp - from, threshold(level + 1) - from};
    }

    /** XP à ajouter pour gagner skip niveaux d'un coup, depuis xp (pour finir au début du niveau visé). */
    public int xpToSkip(int xp, int skip) {
        int target = Math.min(maxLevel(), level(xp) + Math.max(0, skip));
        return Math.max(0, threshold(target) - xp);
    }

    // ---------- Rattrapage et plafond (logique pure) ----------

    /**
     * Rattrapage : le palier « attendu » à ce jour du mois (expectedEnd paliers à la fin, linéaire) ; un joueur en
     * dessous de plus de margin paliers gagne multiplier fois plus d'XP (hors temps de jeu). 1 sinon.
     */
    public static double catchUp(int tier, int dayOfMonth, int daysInMonth, int expectedEnd, int margin, double multiplier) {
        if (multiplier <= 1 || expectedEnd <= 0) {
            return 1;
        }
        double expected = expectedEnd * (double) dayOfMonth / Math.max(1, daysInMonth);
        return tier < expected - margin ? multiplier : 1;
    }

    /**
     * Plafond d'XP de temps de jeu d'aujourd'hui : celui du jour, plus une part (carry) de ce qui n'a pas été utilisé
     * la veille (au plus un plafond entier : un seul jour reporté). Un jour sans jouer compte comme non utilisé.
     * playDay : dernier jour compté (-1 : jamais), playXp et playCap : ce jour-là.
     */
    public static int capToday(int baseCap, long today, long playDay, int playXp, int playCap, double carry) {
        if (playDay == today) {
            return playCap > 0 ? playCap : baseCap;
        }
        if (playDay < 0 || carry <= 0) {
            return baseCap;
        }
        int unused = playDay == today - 1 ? Math.max(0, (playCap > 0 ? playCap : baseCap) - playXp) : baseCap;
        return baseCap + (int) Math.floor(Math.min(baseCap, unused) * Math.min(1, carry));
    }
}
