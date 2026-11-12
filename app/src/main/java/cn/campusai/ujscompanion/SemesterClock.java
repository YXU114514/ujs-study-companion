package cn.campusai.ujscompanion;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** School dates are Beijing civil dates, independent of the device's timezone. */
public final class SemesterClock {
    public static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Shanghai");

    private SemesterClock() {}

    public static LocalDate today() { return today(Instant.now()); }

    public static LocalDate today(Instant instant) {
        return instant.atZone(SCHOOL_ZONE).toLocalDate();
    }

    public static LocalDate parseMonday(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        try {
            LocalDate parsed = LocalDate.parse(value);
            return parsed.getDayOfWeek() == DayOfWeek.MONDAY ? parsed : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static Integer teachingWeek(String monday, int total, LocalDate date) {
        LocalDate first = parseMonday(monday);
        if (first == null || date == null) return null;
        long days = ChronoUnit.DAYS.between(first, date);
        if (days < 0) return null;
        long week = days / 7 + 1;
        int limit = total >= 1 && total <= 60 ? total : 60;
        return week <= limit ? (int) week : null;
    }

    public static LocalDate mondayForWeek(int week, LocalDate date) {
        if (week < 1 || week > 60 || date == null) {
            throw new IllegalArgumentException("教学周必须为 1–60");
        }
        return date.minusDays(date.getDayOfWeek().getValue() - 1L + (week - 1L) * 7L);
    }

    public static String phase(String monday, int total, LocalDate date) {
        LocalDate first = parseMonday(monday);
        if (first == null || date == null) return "设置学期日期";
        if (date.isBefore(first)) return "尚未开学";
        Integer week = teachingWeek(monday, total, date);
        return week == null ? "本学期已结束" : "第" + week + "周";
    }
}
