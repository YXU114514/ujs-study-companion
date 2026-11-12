package cn.campusai.ujscompanion;

import org.junit.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.TimeZone;
import static org.junit.Assert.*;

public class SemesterClockTest {
    @Test public void invalidAnchorsDoNotBecomeWeekOne() {
        for (String anchor : new String[]{null, "", "2026-02-30", "2026-9-7", "2026-09-08"}) {
            assertNull(SemesterClock.teachingWeek(anchor, 20, LocalDate.of(2026, 11, 9)));
            assertEquals("设置学期日期", SemesterClock.phase(anchor, 20, LocalDate.of(2026, 11, 9)));
        }
    }

    @Test public void weekChangesAtBeijingMondayMidnight() {
        LocalDate before = SemesterClock.today(Instant.parse("2026-09-13T15:59:59Z"));
        LocalDate after = SemesterClock.today(Instant.parse("2026-09-13T16:00:00Z"));
        assertEquals(Integer.valueOf(1), SemesterClock.teachingWeek("2026-09-07", 20, before));
        assertEquals(Integer.valueOf(2), SemesterClock.teachingWeek("2026-09-07", 20, after));
        assertEquals(LocalDate.of(2026, 9, 14), after);
    }

    @Test public void deviceTimezoneDoesNotChangeSchoolDate() {
        TimeZone prior = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            assertEquals(LocalDate.of(2026, 9, 14), SemesterClock.today(Instant.parse("2026-09-13T16:00:00Z")));
        } finally { TimeZone.setDefault(prior); }
    }

    @Test public void preTermAndEndAreExplicit() {
        assertEquals("尚未开学", SemesterClock.phase("2026-09-07", 10, LocalDate.of(2026, 9, 6)));
        assertEquals(Integer.valueOf(10), SemesterClock.teachingWeek("2026-09-07", 10, LocalDate.of(2026, 11, 15)));
        assertNull(SemesterClock.teachingWeek("2026-09-07", 10, LocalDate.of(2026, 11, 16)));
        assertEquals("本学期已结束", SemesterClock.phase("2026-09-07", 10, LocalDate.of(2026, 11, 16)));
    }

    @Test public void calibrationRoundTripsEveryDayOfWeek() {
        for (int day = 9; day <= 15; day++) {
            LocalDate today = LocalDate.of(2026, 11, day);
            LocalDate first = SemesterClock.mondayForWeek(10, today);
            assertEquals(LocalDate.of(2026, 9, 7), first);
            assertEquals(Integer.valueOf(10), SemesterClock.teachingWeek(first.toString(), 20, today));
        }
    }

    @Test public void yearBoundaryAndHistoricalDefaultWork() {
        assertEquals(Integer.valueOf(2), SemesterClock.teachingWeek("2026-12-28", 20, LocalDate.of(2027, 1, 4)));
        assertEquals(Integer.valueOf(11), SemesterClock.teachingWeek("2026-08-31", 60, LocalDate.of(2026, 11, 12)));
    }

    @Test(expected = IllegalArgumentException.class) public void zeroWeekCalibrationIsRejected() {
        SemesterClock.mondayForWeek(0, LocalDate.of(2026, 11, 12));
    }
}
