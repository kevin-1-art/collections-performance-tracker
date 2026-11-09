package com.example.collections;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class PeriodWindowTest {
    @Test void weekStartsFridayAndIncludesThursday() {
        PeriodWindow fromFriday=PeriodWindow.forPeriod("week",null,null,LocalDate.of(2026,11,6));
        PeriodWindow fromThursday=PeriodWindow.forPeriod("week",null,null,LocalDate.of(2026,11,12));
        assertEquals(fromFriday,fromThursday);
        assertEquals(LocalDate.of(2026,11,6),fromFriday.start());
        assertEquals(LocalDate.of(2026,11,12),fromFriday.end());
    }

    @Test void customRangeIncludesBothSelectedCalendarDates() {
        PeriodWindow custom=PeriodWindow.forPeriod("custom",LocalDate.of(2026,1,1),LocalDate.of(2026,1,2),LocalDate.now());
        assertEquals(LocalDate.of(2026,1,1),custom.start());
        assertEquals(LocalDate.of(2026,1,2),custom.end());
    }

    @Test void rejectsInvalidCustomRange() {
        assertThrows(IllegalArgumentException.class,()->PeriodWindow.forPeriod("custom",
                LocalDate.of(2026,1,2),LocalDate.of(2026,1,1),LocalDate.now()));
    }

    @Test void monthUsesTheCurrentKampalaCalendarMonth() {
        PeriodWindow month=PeriodWindow.forPeriod("month",null,null,LocalDate.of(2026,2,12));
        assertEquals(LocalDate.of(2026,2,1),month.start());
        assertEquals(LocalDate.of(2026,2,28),month.end());
    }
}
