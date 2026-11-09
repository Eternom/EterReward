package fr.eternom.eterReward.module.pass;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class XpCurveTest {

    /** 30 paliers : 600, 800, 1000, puis 1200 ; bonus tous les 2400, 20 au plus. */
    private final XpCurve curve = new XpCurve(List.of(600, 800, 1000, 1200), 1200, 30, 2400, 20);

    @Test
    void thresholdsFollowTheCurveThenRepeatTheLastCost() {
        assertEquals(0, curve.threshold(0));
        assertEquals(600, curve.threshold(1));
        assertEquals(1400, curve.threshold(2));
        assertEquals(2400, curve.threshold(3));
        assertEquals(3600, curve.threshold(4));
        assertEquals(2400 + 27 * 1200, curve.threshold(30));
    }

    @Test
    void flatCurveWhenNoCosts() {
        XpCurve flat = new XpCurve(List.of(), 1200, 30, 0, 0);
        assertEquals(36_000, flat.threshold(30));
        assertEquals(0, flat.tier(1199));
        assertEquals(1, flat.tier(1200));
        assertEquals(30, flat.tier(1_000_000));
        assertEquals(0, flat.overflow(1_000_000));
        assertEquals(30, flat.maxLevel());
    }

    @Test
    void tierAtExactThresholds() {
        assertEquals(0, curve.tier(599));
        assertEquals(1, curve.tier(600));
        assertEquals(2, curve.tier(1400));
        assertEquals(29, curve.tier(curve.threshold(30) - 1));
        assertEquals(30, curve.tier(curve.threshold(30)));
    }

    @Test
    void bonusTiersAfterTheLastOneAreCapped() {
        int end = curve.threshold(30);
        assertEquals(0, curve.overflow(end + 2399));
        assertEquals(1, curve.overflow(end + 2400));
        assertEquals(31, curve.level(end + 2400));
        assertEquals(20, curve.overflow(end + 2400 * 100));
        assertEquals(50, curve.maxLevel());
        assertEquals(end + 2400, curve.threshold(31));
    }

    @Test
    void progressBarInsideTheCurrentLevel() {
        assertArrayEquals(new int[]{100, 800}, curve.progress(700));
        assertArrayEquals(new int[]{0, 2400}, curve.progress(curve.threshold(30)));
        assertArrayEquals(new int[]{0, 0}, curve.progress(curve.threshold(30) + 2400 * 20));
    }

    @Test
    void skipLandsAtTheStartOfTheTargetLevel() {
        assertEquals(1400 - 700, curve.xpToSkip(700, 1));
        assertEquals(3600 - 700, curve.xpToSkip(700, 3));
        // On ne dépasse pas le dernier niveau
        int max = curve.threshold(50);
        assertEquals(0, curve.xpToSkip(max, 5));
    }

    @Test
    void catchUpOnlyBelowTheExpectedPace() {
        // Mi-mois (15/30), 22 paliers attendus en fin de mois -> 11 attendus, marge 3
        assertEquals(2, XpCurve.catchUp(7, 15, 30, 22, 3, 2));
        assertEquals(1, XpCurve.catchUp(8, 15, 30, 22, 3, 2));
        assertEquals(1, XpCurve.catchUp(0, 1, 30, 22, 3, 2));
        assertEquals(1, XpCurve.catchUp(0, 30, 30, 0, 3, 2));
        assertEquals(1, XpCurve.catchUp(0, 30, 30, 22, 3, 1));
    }

    @Test
    void capCarriesHalfOfYesterdayUnusedCap() {
        long today = 1000;
        // Rien encore aujourd'hui, hier 300/500 utilisés : 500 + 200 × 0,5
        assertEquals(600, XpCurve.capToday(500, today, today - 1, 300, 500, 0.5));
        // Hier plafond plein : rien à reporter
        assertEquals(500, XpCurve.capToday(500, today, today - 1, 500, 500, 0.5));
        // Pas joué hier (ni depuis) : un seul jour reporté, au plus
        assertEquals(750, XpCurve.capToday(500, today, today - 5, 0, 500, 0.5));
        // Le report d'hier ne se cumule pas au-delà d'un plafond entier
        assertEquals(750, XpCurve.capToday(500, today, today - 1, 0, 1200, 0.5));
        // Aujourd'hui déjà compté : le plafond enregistré
        assertEquals(650, XpCurve.capToday(500, today, today, 200, 650, 0.5));
        // Jamais joué, ou pas de report
        assertEquals(500, XpCurve.capToday(500, today, -1, 0, 0, 0.5));
        assertEquals(500, XpCurve.capToday(500, today, today - 1, 0, 500, 0));
    }
}
