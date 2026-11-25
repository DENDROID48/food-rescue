package com.foodrescue.service;

import com.foodrescue.dto.ImpactResponse;
import com.foodrescue.dto.ImpactResponse.AchievementView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Streaks, achievements, personal impact and the community challenge, without a database. */
class GamificationServiceTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    /** A Wednesday. */
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

    private static GamificationService.Rescue rescue(LocalDate day, long store, int units, String saved, Integer gramsPerUnit,
                                                     boolean drop) {
        return new GamificationService.Rescue(day.atTime(18, 0).toInstant(ZoneOffset.UTC), store, units, new BigDecimal(saved),
                gramsPerUnit == null ? 0 : gramsPerUnit * units, gramsPerUnit != null, drop);
    }

    private static ImpactResponse compute(List<GamificationService.Rescue> rescues) {
        return GamificationService.compute(rescues, UTC, TODAY, 120, 400);
    }

    private static AchievementView achievement(ImpactResponse r, String code) {
        return r.achievements().stream().filter(a -> a.code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void nothingCollectedYet() {
        ImpactResponse r = compute(List.of());

        assertThat(r.rescues()).isZero();
        assertThat(r.moneySaved()).isEqualByComparingTo("0");
        assertThat(r.streak()).isEqualTo(new ImpactResponse.Streak(0, 0, false));
        assertThat(r.achievements()).noneMatch(AchievementView::unlocked);
        assertThat(achievement(r, "FIRST_RESCUE").progressText()).isEqualTo("0 of 1");
    }

    @Test
    void totalsCountMoneyWeightShopsAndUnknownWeights() {
        ImpactResponse r = compute(List.of(
                rescue(TODAY.minusDays(3), 1, 2, "12.50", 500, false),
                rescue(TODAY.minusDays(2), 2, 1, "4.00", null, true),
                rescue(TODAY, 1, 3, "9.00", 250, false)));

        assertThat(r.rescues()).isEqualTo(3);
        assertThat(r.units()).isEqualTo(6);
        assertThat(r.moneySaved()).isEqualByComparingTo("25.50");
        assertThat(r.kgRescued()).isEqualTo(1.8);
        assertThat(r.unitsWithoutWeight()).isEqualTo(1);
        assertThat(r.shops()).isEqualTo(2);
        assertThat(achievement(r, "DROP_CATCHER").unlocked()).isTrue();
        assertThat(achievement(r, "DROP_CATCHER").unlockedOn()).isEqualTo(TODAY.minusDays(2));
        assertThat(achievement(r, "LOCAL_EXPLORER").progressText()).isEqualTo("2 of 3 shops");
        assertThat(achievement(r, "FIVE_KG").progressText()).isEqualTo("1.8 of 5 kg");
        assertThat(achievement(r, "FIVE_KG").percent()).isEqualTo(35); // 1.75 kg
    }

    @Test
    void achievementsRememberTheDayTheyWereReached() {
        List<GamificationService.Rescue> rescues = new ArrayList<>();
        for (int i = 12; i >= 1; i--) rescues.add(rescue(TODAY.minusDays(i), 1, 1, "1.00", null, false));

        ImpactResponse r = compute(rescues);

        assertThat(achievement(r, "FIRST_RESCUE").unlockedOn()).isEqualTo(TODAY.minusDays(12));
        assertThat(achievement(r, "REGULAR").unlockedOn()).isEqualTo(TODAY.minusDays(3)); // the 10th
        assertThat(achievement(r, "HERO").unlocked()).isFalse();
        assertThat(achievement(r, "HERO").progressText()).isEqualTo("12 of 50");
    }

    @Test
    void weeklyStreakCountsWeeksInARowAndSurvivesUntilTheWeekAfter() {
        // Weeks of Nov 2, 9, 16 (three in a row), a gap, then nothing this week (Nov 23).
        List<GamificationService.Rescue> rescues = List.of(
                rescue(LocalDate.of(2026, 10, 19), 1, 1, "1", null, false), // a lone earlier week
                rescue(LocalDate.of(2026, 11, 3), 1, 1, "1", null, false),
                rescue(LocalDate.of(2026, 11, 8), 1, 1, "1", null, false),   // Sunday, same week as Nov 3
                rescue(LocalDate.of(2026, 11, 10), 1, 1, "1", null, false),
                rescue(LocalDate.of(2026, 11, 20), 1, 1, "1", null, false));

        ImpactResponse r = compute(rescues);

        assertThat(r.streak().bestWeeks()).isEqualTo(3);
        assertThat(r.streak().currentWeeks()).isEqualTo(3); // last week counted, so still alive
        assertThat(r.streak().rescuedThisWeek()).isFalse();

        ImpactResponse later = GamificationService.compute(rescues, UTC, TODAY.plusWeeks(1), 0, 400);
        assertThat(later.streak().currentWeeks()).isZero(); // a whole week missed
        assertThat(later.streak().bestWeeks()).isEqualTo(3);
    }

    @Test
    void steadyStreakUnlocksAfterFourWeeksInARow() {
        List<GamificationService.Rescue> rescues = new ArrayList<>();
        for (int w = 3; w >= 0; w--) rescues.add(rescue(TODAY.minusWeeks(w), 1, 1, "1", null, false));

        ImpactResponse r = compute(rescues);

        assertThat(r.streak()).isEqualTo(new ImpactResponse.Streak(4, 4, true));
        assertThat(achievement(r, "STEADY_STREAK").unlockedOn()).isEqualTo(TODAY);
    }

    @Test
    void communityChallengeShowsTheMonthAndYourShare() {
        ImpactResponse r = compute(List.of(
                rescue(LocalDate.of(2026, 10, 30), 1, 5, "1", null, false), // last month: not counted
                rescue(LocalDate.of(2026, 11, 2), 1, 2, "1", null, false),
                rescue(TODAY, 1, 3, "1", null, false)));

        ImpactResponse.Challenge c = r.challenge();
        assertThat(c.title()).isEqualTo("Rescue 400 items together in November");
        assertThat(c.communityUnits()).isEqualTo(120);
        assertThat(c.yourUnits()).isEqualTo(5);
        assertThat(c.percent()).isEqualTo(30);
        assertThat(c.endsOn()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(c.daysLeft()).isEqualTo(5);
    }
}
