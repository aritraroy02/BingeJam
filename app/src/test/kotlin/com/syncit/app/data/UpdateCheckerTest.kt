package com.syncit.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test fun newerPatchMinorMajor() {
        assertTrue(UpdateChecker.isNewer("0.2.1", "0.2.0"))
        assertTrue(UpdateChecker.isNewer("0.3.0", "0.2.9"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.9.9"))
    }

    @Test fun comparesNumbersNotText() = assertTrue(UpdateChecker.isNewer("0.10.0", "0.9.0"))

    @Test fun sameOrOlderIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("0.2.0", "0.2.0"))
        assertFalse(UpdateChecker.isNewer("0.1.9", "0.2.0"))
    }

    @Test fun missingPartsCountAsZero() {
        assertFalse(UpdateChecker.isNewer("0.2", "0.2.0"))
        assertTrue(UpdateChecker.isNewer("0.2.1", "0.2"))
    }

    @Test fun garbageIsNotNewer() = assertFalse(UpdateChecker.isNewer("", "0.2.0"))
}
