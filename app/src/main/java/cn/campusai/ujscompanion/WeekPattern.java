package cn.campusai.ujscompanion;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the school's week notation without inventing coverage for unknown text. */
public final class WeekPattern {
    private static final Pattern PART = Pattern.compile(
            "^\\s*(\\d+)\\s*(?:-\\s*(\\d+))?\\s*(?:周)?\\s*" +
            "(?:[（(]?\\s*([单双])\\s*周?\\s*[）)]?)?\\s*$");

    private WeekPattern() {}

    public static Set<Integer> parse(String raw) {
        if (raw == null || raw.trim().isEmpty() || raw.length() > 2048) {
            return Collections.emptySet();
        }
        String text = raw.replaceAll("[，、；;]", ",")
                .replaceAll("[–—~～至]", "-").replace("星期", "");
        Set<Integer> weeks = new TreeSet<>();
        for (String part : text.split(",", -1)) {
            if (part.trim().isEmpty()) continue;
            Matcher match = PART.matcher(part);
            if (!match.matches()) return Collections.emptySet();
            try {
                int first = Integer.parseInt(match.group(1));
                int last = match.group(2) == null ? first : Integer.parseInt(match.group(2));
                if (first < 1 || last < first || last > 60) return Collections.emptySet();
                String parity = match.group(3);
                for (int week = first; week <= last; week++) {
                    if (("单".equals(parity) && week % 2 == 0)
                            || ("双".equals(parity) && week % 2 != 0)) continue;
                    weeks.add(week);
                }
            } catch (NumberFormatException ignored) {
                return Collections.emptySet();
            }
        }
        return Collections.unmodifiableSet(weeks);
    }

    public static boolean includes(String raw, int week) {
        return week >= 1 && week <= 60 && parse(raw).contains(week);
    }
}
