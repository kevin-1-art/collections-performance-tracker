package com.example.collections;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class PeriodWindowTest {
    @Test void weekStartsFridayAndIncludesThursday() {
        PeriodWindow fromFriday=PeriodWindow.forPeriod("week",null,null,LocalDate.of(2026,11,6));
        PeriodWindow fromThursday=PeriodWindow.forPeriod("week",null,null,LocalDate.of(2026,11,12));
        assertEquals(fromFriday,fromThursday);
        assertEquals(Instant.parse("2026-11-05T21:00:00Z"),fromFriday.start());
        assertEquals(Instant.parse("2026-11-12T21:00:00Z"),fromFriday.end());
    }

    @Test void customRangeIncludesTheWholeLastKampalaDay() {
        PeriodWindow custom=PeriodWindow.forPeriod("custom",LocalDate.of(2026,1,1),LocalDate.of(2026,1,2),LocalDate.now());
        assertEquals(Instant.parse("2025-12-31T21:00:00Z"),custom.start());
        assertEquals(Instant.parse("2026-01-02T21:00:00Z"),custom.end());
    }

    @Test void rejectsInvalidCustomRange() {
        assertThrows(IllegalArgumentException.class,()->PeriodWindow.forPeriod("custom",
                LocalDate.of(2026,1,2),LocalDate.of(2026,1,1),LocalDate.now()));
    }
}
