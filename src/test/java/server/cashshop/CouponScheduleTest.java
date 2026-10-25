package server.cashshop;

import client.command.commands.gm0.CouponsCommand;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rate coupon schedules in words, from the real WZ {@code info/time} strings (Item.wz/Cash/0521.img, 0536.img) and the
 * matching {@code nxcoupons} rows (activeday, starthour, endhour) the coupon task checks.
 */
class CouponScheduleTest {
    private static final ZoneId SERVER = ZoneId.of("GMT-6");   // config TIMEZONE on test and live
    private static final List<String> WEEK = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    private static List<String> wz(List<String> days, String span) {
        return days.stream().map(d -> d + ":" + span).toList();
    }

    // 5211004 Type 1 - Weekdays: WZ MON..FRI:07-11, nxcoupons (124, 7, 11)
    private static final List<String> WZ_5211004 = wz(WEEK.subList(0, 5), "07-11");
    // 5211010 Type 2 - Weekends: SAT,SUN:10-14, (130, 10, 14)
    private static final List<String> WZ_5211010 = wz(List.of("SAT", "SUN"), "10-14");
    // 5211016 Type 3 - Everyday: 13-17, (254, 13, 17)
    private static final List<String> WZ_5211016 = wz(WEEK, "13-17");
    // 5211037 Type 6 - Weekdays: 00-04, (124, 0, 4)
    private static final List<String> WZ_5211037 = wz(WEEK.subList(0, 5), "00-04");
    // 5211060 2 hour 3x: every day 00-24 plus HOL:00-24, (255, 0, 24)
    private static final List<String> WZ_5211060 = Stream.concat(wz(WEEK, "00-24").stream(),
            Stream.of("HOL:00-24")).toList();

    @Test
    void wzStringsMatchTheNxcouponsRows() {
        assertEquals(CouponSchedule.fromDb(124, 7, 11), CouponSchedule.fromWz(WZ_5211004));
        assertEquals(CouponSchedule.fromDb(130, 10, 14), CouponSchedule.fromWz(WZ_5211010));
        assertEquals(CouponSchedule.fromDb(254, 13, 17), CouponSchedule.fromWz(WZ_5211016));
        assertEquals(CouponSchedule.fromDb(124, 0, 4), CouponSchedule.fromWz(WZ_5211037));
        // 255 carries an unused bit 0; the days are the same as 254.
        assertEquals(CouponSchedule.fromDb(255, 0, 24).describe(), CouponSchedule.fromWz(WZ_5211060).describe());
        assertNull(CouponSchedule.fromWz(List.of("MON:07-11", "TUE:10-14")));
    }

    @Test
    void describesServerHours() {
        assertEquals("Mon-Fri 7-11 AM", CouponSchedule.fromWz(WZ_5211004).describe());
        assertEquals("Sat-Sun 10 AM-2 PM", CouponSchedule.fromWz(WZ_5211010).describe());
        assertEquals("every day 1-5 PM", CouponSchedule.fromWz(WZ_5211016).describe());
        assertEquals("Mon-Fri 12-4 AM", CouponSchedule.fromWz(WZ_5211037).describe());
        assertEquals("any time", CouponSchedule.fromWz(WZ_5211060).describe());
        assertEquals("every day 7-11 PM", CouponSchedule.fromDb(254, 19, 23).describe());   // 5360005
    }

    @Test
    void translatesToPacificIncludingTheDayWhenItCrossesMidnight() {
        // PDT is one hour behind GMT-6, PST two.
        assertEquals("Mon-Fri 6-10 AM", CouponSchedule.fromWz(WZ_5211004).describeShifted(-1));
        assertEquals("Sat-Sun 8 AM-12 PM", CouponSchedule.fromWz(WZ_5211010).describeShifted(-2));
        assertEquals("Sun-Thu 10 PM-2 AM", CouponSchedule.fromWz(WZ_5211037).describeShifted(-2));
        assertEquals("Fri-Sat 11 PM-3 AM", CouponSchedule.fromDb(130, 0, 4).describeShifted(-1));   // 5211038
        assertEquals("any time", CouponSchedule.fromWz(WZ_5211060).describeShifted(-2));

        ZonedDateTime summer = ZonedDateTime.of(2026, 10, 24, 15, 0, 0, 0, SERVER);
        ZonedDateTime winter = ZonedDateTime.of(2026, 12, 5, 15, 0, 0, 0, SERVER);
        assertEquals(-1, CouponSchedule.playerShiftHours(summer));
        assertEquals(-2, CouponSchedule.playerShiftHours(winter));
        assertEquals("UTC-6", CouponSchedule.offsetLabel(summer));
        assertEquals("Sat 3:00 PM", CouponSchedule.clock(summer));
    }

    @Test
    void activeAtMirrorsTheCouponTask() {
        CouponSchedule weekdays = CouponSchedule.fromWz(WZ_5211004);
        ZonedDateTime fri7 = ZonedDateTime.of(2026, 10, 23, 7, 0, 0, 0, SERVER);
        assertTrue(weekdays.activeAt(fri7));
        assertTrue(weekdays.activeAt(fri7.plusHours(3).plusMinutes(59)));
        assertFalse(weekdays.activeAt(fri7.plusHours(4)));      // end hour is exclusive
        assertFalse(weekdays.activeAt(fri7.minusHours(1)));
        assertFalse(weekdays.activeAt(fri7.plusDays(1)));       // Saturday
        assertTrue(CouponSchedule.fromWz(WZ_5211010).activeAt(fri7.plusDays(2).plusHours(3)));  // Sun 10 AM
    }

    @Test
    void purchaseNote() {
        ZonedDateTime summer = ZonedDateTime.of(2026, 10, 24, 15, 0, 0, 0, SERVER);
        ZonedDateTime winter = ZonedDateTime.of(2026, 12, 5, 15, 0, 0, 0, SERVER);
        assertEquals("2x EXP M-F 7-11am 30d works Mon-Fri 7-11 AM server time (Mon-Fri 6-10 AM Pacific)."
                        + " @coupons shows your coupons.",
                CouponSchedule.fromWz(WZ_5211004).purchaseNote("2x EXP M-F 7-11am 30d", summer));
        assertEquals("2x EXP M-F 12-4am 30d works Mon-Fri 12-4 AM server time (Sun-Thu 10 PM-2 AM Pacific)."
                        + " @coupons shows your coupons.",
                CouponSchedule.fromWz(WZ_5211037).purchaseNote("2x EXP M-F 12-4am 30d", winter));
        assertEquals("3x EXP anytime 2hrs works any time. @coupons shows your coupons.",
                CouponSchedule.fromWz(WZ_5211060).purchaseNote("3x EXP anytime 2hrs", summer));
    }

    @Test
    void couponsCommandReport() {
        ZonedDateTime now = ZonedDateTime.of(2026, 10, 24, 15, 12, 0, 0, SERVER);   // Sat 3:12 PM server
        long in30d = now.toInstant().toEpochMilli() + 30L * 24 * 3600 * 1000 - 1000;
        List<CouponsCommand.Held> held = List.of(
                new CouponsCommand.Held(5211004, "2x EXP M-F 7-11am 30d", in30d),
                new CouponsCommand.Held(5211016, "2x EXP 1-5pm 10d", -1),
                new CouponsCommand.Held(5211060, "3x EXP anytime 2hrs", now.toInstant().toEpochMilli() + 90 * 60_000));
        var schedules = java.util.Map.of(5211004, CouponSchedule.fromDb(124, 7, 11),
                5211016, CouponSchedule.fromDb(254, 13, 17), 5211060, CouponSchedule.fromDb(255, 0, 24));
        Set<Integer> active = Set.of(5211016, 5211060);

        List<String> lines = CouponsCommand.report(held, now, schedules::get, active::contains, false);
        assertEquals("Coupon hours are server time (UTC-6). Now: server Sat 3:12 PM, Pacific Sat 2:12 PM"
                + " (server is 1 hour ahead).", lines.get(0));
        assertEquals("2x EXP M-F 7-11am 30d: Mon-Fri 7-11 AM server = Mon-Fri 6-10 AM Pacific. Not working now."
                + " Expires in 29 days.", lines.get(1));
        assertEquals("2x EXP 1-5pm 10d: every day 1-5 PM server = every day 12-4 PM Pacific. Working now.", lines.get(2));
        assertEquals("3x EXP anytime 2hrs: any time. Working now. Expires in 90 minutes.", lines.get(3));
        assertTrue(lines.get(4).startsWith("Coupons do not stack"), lines.get(4));
        assertTrue(lines.get(5).startsWith("Cash Shop coupon names use server hours"), lines.get(5));

        List<String> none = CouponsCommand.report(List.of(), now, schedules::get, active::contains, false);
        assertEquals(3, none.size());
        assertEquals("You have no EXP or drop coupons.", none.get(1));
    }
}
