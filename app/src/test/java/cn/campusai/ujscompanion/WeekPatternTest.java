package cn.campusai.ujscompanion;

import org.junit.Test;
import java.util.Arrays;
import java.util.TreeSet;
import static org.junit.Assert.*;

public class WeekPatternTest {
    @Test public void parityAndSeparatorsArePreserved() {
        assertEquals(new TreeSet<>(Arrays.asList(1, 3, 5, 8, 10, 12)), WeekPattern.parse("1-5周(单)，8至12周（双）"));
        assertEquals(new TreeSet<>(Arrays.asList(2, 4, 7)), WeekPattern.parse("2周、4周；7周"));
    }

    @Test public void duplicateWeeksDoNotDuplicateMeetings() {
        assertEquals(new TreeSet<>(Arrays.asList(1, 2, 3)), WeekPattern.parse("1-3周,2周,3周"));
        assertTrue(WeekPattern.includes("1-16周(单)", 11));
        assertFalse(WeekPattern.includes("1-16周(单)", 12));
    }

    @Test public void unknownNotationNeverInventsCoverage() {
        for (String value : new String[]{null, "", "全学期", "1-3周,未知", "0-4周", "4-2周", "1-61周", "99999999999周"}) {
            assertTrue(String.valueOf(value), WeekPattern.parse(value).isEmpty());
        }
    }

    @Test public void boundaryWeekSixtyIsAllowed() {
        assertTrue(WeekPattern.includes("60周", 60));
        assertFalse(WeekPattern.includes("60周", 61));
    }
}
