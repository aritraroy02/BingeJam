package com.syncit.app.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BirthDatesTest {
    private val today = LocalDate.of(2026, 11, 10)

    @Test fun wireRoundTrip() = assertEquals(LocalDate.of(2001, 3, 12), BirthDates.parse(BirthDates.wire(LocalDate.of(2001, 3, 12))))

    @Test fun badTextIsNull() = assertNull(BirthDates.parse("12/03/2001"))

    @Test fun ageCountsWholeYears() {
        assertEquals(25, BirthDates.age(LocalDate.of(2001, 11, 10), today))
        assertEquals(24, BirthDates.age(LocalDate.of(2001, 11, 11), today))
    }

    @Test fun birthdayTodayIsZeroDays() = assertEquals(0L, BirthDates.daysUntil(LocalDate.of(1999, 11, 10), today))

    @Test fun passedBirthdayIsNextYear() = assertEquals(LocalDate.of(2027, 11, 9), BirthDates.nextBirthday(LocalDate.of(1999, 11, 9), today))

    @Test fun leapDayFallsOn28thInOtherYears() =
        assertEquals(LocalDate.of(2027, 2, 28), BirthDates.nextBirthday(LocalDate.of(2004, 2, 29), today))

    @Test fun leapDayStaysInLeapYears() =
        assertEquals(LocalDate.of(2028, 2, 29), BirthDates.nextBirthday(LocalDate.of(2004, 2, 29), LocalDate.of(2027, 3, 1)))

    @Test fun futureAndAncientDatesInvalid() {
        assertFalse(BirthDates.isValid(LocalDate.of(2026, 11, 11), today))
        assertFalse(BirthDates.isValid(LocalDate.of(1900, 1, 1), today))
        assertTrue(BirthDates.isValid(LocalDate.of(2000, 1, 1), today))
    }

    @Test fun wording() {
        assertEquals("today", BirthDates.whenText(0))
        assertEquals("tomorrow", BirthDates.whenText(1))
        assertEquals("in 12 days", BirthDates.whenText(12))
    }
}
