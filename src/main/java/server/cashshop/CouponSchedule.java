package server.cashshop;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

/**
 * When a rate coupon (5211xxx EXP, 5360xxx drop) works: the {@code nxcoupons} row the coupon task checks
 * ({@code Server.updateActiveCoupons}), in plain words for players ({@code @coupons}, the Cash Shop purchase note).
 *
 * <p>{@code activeday} is a bit mask indexed by {@link java.util.Calendar#DAY_OF_WEEK} (Sunday = bit 1 ... Saturday =
 * bit 7; 124 = Mon-Fri, 130 = Sat+Sun, 254 = every day). Hours are {@code [start, end)} in the SERVER's default zone,
 * which {@code Server} sets from config {@code TIMEZONE} (GMT-6 on test and live). The rows come from the same WZ
 * {@code info/time} strings ("MON:07-11") the Cash Shop names are built from ({@code tools/cashshop/coupon_schedule.py}).
 */
public record CouponSchedule(int dayMask, int start, int end) {
    /** Pacific time: where the owner and the players are. Only used to translate the server's hours. */
    public static final ZoneId PLAYER_ZONE = ZoneId.of("America/Los_Angeles");

    private static final String[] WZ_DAYS = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};
    private static final String[] SHORT = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

    public static CouponSchedule fromDb(int activeDay, int startHour, int endHour) {
        return new CouponSchedule(activeDay, startHour, endHour);
    }

    /** The WZ form, e.g. ["MON:07-11", "TUE:07-11", ...]. HOL entries are ignored. Null if the days differ. */
    public static CouponSchedule fromWz(List<String> times) {
        int mask = 0;
        int s = -1, e = -1;
        for (String t : times) {
            String[] dayAndSpan = t.split(":");
            if (dayAndSpan.length != 2) {
                return null;
            }
            String day = dayAndSpan[0].trim().toUpperCase(Locale.ROOT);
            if (day.equals("HOL")) {
                continue;
            }
            int idx = List.of(WZ_DAYS).indexOf(day);
            String[] span = dayAndSpan[1].split("-");
            if (idx < 0 || span.length != 2) {
                return null;
            }
            int a = Integer.parseInt(span[0].trim()), b = Integer.parseInt(span[1].trim());
            if (s >= 0 && (a != s || b != e)) {
                return null;
            }
            s = a;
            e = b;
            mask |= 1 << calendarDay(idx);
        }
        return mask == 0 ? null : new CouponSchedule(mask, s, e);
    }

    /** 0 = Monday .. 6 = Sunday -> Calendar.DAY_OF_WEEK (Sunday 1 .. Saturday 7). */
    private static int calendarDay(int mondayFirst) {
        return mondayFirst == 6 ? 1 : mondayFirst + 2;
    }

    public boolean onDay(int mondayFirst) {
        return (dayMask & (1 << calendarDay(mondayFirst))) != 0;
    }

    /** The coupon task's own test, at a given moment in the server zone. */
    public boolean activeAt(ZonedDateTime serverTime) {
        int dow = serverTime.getDayOfWeek().getValue() - 1;  // Monday 0
        int h = serverTime.getHour();
        return onDay(dow) && start <= h && end > h;
    }

    public boolean allDay() {
        return start == 0 && end >= 24;
    }

    /** "Mon-Fri 7-11 AM", "Sat-Sun 10 AM-2 PM", "every day 1-5 PM", "any time". */
    public String describe() {
        return describe(daySet(), start, end);
    }

    /**
     * The same window on another clock, {@code shiftHours} after the server's (Pacific during PDT is -1 from
     * GMT-6, -2 during PST). A window pushed over midnight moves its days with it: Mon-Fri 12-4 AM server is
     * Sun-Thu 10 PM-2 AM in PST.
     */
    public String describeShifted(int shiftHours) {
        if (allDay()) {
            return "any time";
        }
        boolean[] days = daySet();
        int s = start + shiftHours, e = end + shiftHours;
        while (s < 0) {
            s += 24;
            e += 24;
            days = rotate(days, -1);
        }
        while (s >= 24) {
            s -= 24;
            e -= 24;
            days = rotate(days, 1);
        }
        return describe(days, s, e);
    }

    /** Whole hours from the server zone to Pacific at {@code now} (DST-aware); null if not whole hours. */
    public static Integer playerShiftHours(ZonedDateTime now) {
        int server = now.getOffset().getTotalSeconds();
        int player = now.withZoneSameInstant(PLAYER_ZONE).getOffset().getTotalSeconds();
        int diff = player - server;
        return diff % 3600 == 0 ? diff / 3600 : null;
    }

    private boolean[] daySet() {
        boolean[] d = new boolean[7];
        for (int i = 0; i < 7; i++) {
            d[i] = onDay(i);
        }
        return d;
    }

    private static boolean[] rotate(boolean[] days, int by) {
        boolean[] out = new boolean[7];
        for (int i = 0; i < 7; i++) {
            out[Math.floorMod(i + by, 7)] = days[i];
        }
        return out;
    }

    private static String describe(boolean[] days, int s, int e) {
        if (s == 0 && e >= 24 && count(days) == 7) {
            return "any time";
        }
        String hours = (s == 0 && e >= 24) ? "all day" : hours(s, e);
        return days(days) + " " + hours;
    }

    private static int count(boolean[] days) {
        int n = 0;
        for (boolean b : days) {
            n += b ? 1 : 0;
        }
        return n;
    }

    /** "every day", "Mon-Fri", "Sat-Sun", "Sun-Thu", "Mon", or a list "Mon/Wed". */
    static String days(boolean[] days) {
        int n = count(days);
        if (n == 7) {
            return "every day";
        }
        if (n == 0) {
            return "no day";
        }
        // One cyclic run? Find where it starts (a day on whose previous day is off).
        for (int i = 0; i < 7; i++) {
            if (days[i] && !days[Math.floorMod(i - 1, 7)]) {
                int len = 0;
                while (len < 7 && days[(i + len) % 7]) {
                    len++;
                }
                if (len == n) {
                    return n == 1 ? SHORT[i] : SHORT[i] + "-" + SHORT[(i + n - 1) % 7];
                }
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if (days[i]) {
                sb.append(sb.isEmpty() ? "" : "/").append(SHORT[i]);
            }
        }
        return sb.toString();
    }

    /** [7, 11) -> "7-11 AM", [10, 14) -> "10 AM-2 PM", [0, 4) -> "12-4 AM", [22, 26) -> "10 PM-2 AM". */
    static String hours(int s, int e) {
        String ss = s % 24 < 12 ? "AM" : "PM", es = e % 24 < 12 ? "AM" : "PM";
        int sh = s % 12 == 0 ? 12 : s % 12, eh = e % 12 == 0 ? 12 : e % 12;
        if (ss.equals(es) && e - s < 12 && e != 12) {
            return sh + "-" + eh + " " + es;
        }
        return sh + " " + ss + "-" + eh + " " + es;
    }

    /**
     * The chat line after buying a coupon in the Cash Shop window: "2x EXP M-F 7-11am 30d works Mon-Fri 7-11 AM
     * server time (Mon-Fri 6-10 AM Pacific). @coupons shows your coupons." {@code now} is in the server zone.
     */
    public String purchaseNote(String name, ZonedDateTime now) {
        StringBuilder sb = new StringBuilder(name).append(" works ").append(describe());
        if (!describe().equals("any time")) {
            sb.append(" server time");
            Integer shift = playerShiftHours(now);
            if (shift != null && shift != 0 && !allDay()) {
                sb.append(" (").append(describeShifted(shift)).append(" Pacific)");
            }
        }
        return sb.append(". @coupons shows your coupons.").toString();
    }

    /** "UTC-6" for the zone's offset at {@code now}. */
    public static String offsetLabel(ZonedDateTime now) {
        int secs = now.getOffset().getTotalSeconds();
        if (secs == 0) {
            return "UTC";
        }
        int h = Math.abs(secs) / 3600, m = Math.abs(secs) % 3600 / 60;
        return "UTC" + (secs < 0 ? "-" : "+") + h + (m != 0 ? String.format(":%02d", m) : "");
    }

    /** "Sat 3:12 PM". */
    public static String clock(ZonedDateTime t) {
        int h = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        return SHORT[t.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue()] + " "
                + h + String.format(":%02d ", t.getMinute()) + (t.getHour() < 12 ? "AM" : "PM");
    }
}
