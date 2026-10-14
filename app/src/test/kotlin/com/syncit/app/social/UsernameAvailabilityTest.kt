package com.syncit.app.social

import com.syncit.app.social.UsernameAvailability.Status
import com.syncit.core.social.UsernameCheck
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Real (short) delays on runBlocking's single thread; no coroutines-test dependency needed. */
class UsernameAvailabilityTest {

    private val checked = mutableListOf<String>()

    private fun checker(taken: Set<String> = emptySet()): suspend (String) -> UsernameCheck = { name ->
        checked += name
        if (name in taken) UsernameCheck.Taken else UsernameCheck.Available(name)
    }

    @Test fun debouncesToTheLastInput() = runBlocking {
        coroutineScope {
            val a = UsernameAvailability(this, checker(), debounceMs = DEBOUNCE)
            a.onInput("riy")
            delay(DEBOUNCE / 3)
            a.onInput("riya")
            delay(DEBOUNCE / 3)
            a.onInput("riya_s")
            assertEquals(Status.Checking, a.status.value)
            delay(DEBOUNCE * 3)
            assertEquals(listOf("riya_s"), checked)
            assertEquals(Status.Available("riya_s"), a.status.value)
            assertEquals("riya_s", a.usable)
        }
    }

    @Test fun invalidInputIsImmediateAndNeverChecked() = runBlocking {
        coroutineScope {
            val a = UsernameAvailability(this, checker(), debounceMs = DEBOUNCE)
            a.onInput("ab")
            assertEquals(Status.Invalid("At least 3 characters"), a.status.value)
            delay(DEBOUNCE * 2)
            assertEquals(emptyList<String>(), checked)
            assertNull(a.usable)
        }
    }

    @Test fun takenName() = runBlocking {
        coroutineScope {
            val a = UsernameAvailability(this, checker(taken = setOf("riya")), debounceMs = DEBOUNCE)
            a.onInput("@Riya")
            delay(DEBOUNCE * 3)
            assertEquals(Status.Taken, a.status.value)
            assertNull(a.usable)
        }
    }

    @Test fun currentUsernameIsUnchangedNotTaken() = runBlocking {
        coroutineScope {
            val a = UsernameAvailability(
                this,
                checker(taken = setOf("riya")),
                currentUsername = { "riya" },
                debounceMs = DEBOUNCE,
            )
            a.onInput("riya")
            assertEquals(Status.Unchanged, a.status.value)
            assertEquals("riya", a.usable)
            delay(DEBOUNCE * 2)
            assertEquals(emptyList<String>(), checked)
        }
    }

    @Test fun clearingCancelsAPendingCheck() = runBlocking {
        coroutineScope {
            val a = UsernameAvailability(this, checker(), debounceMs = DEBOUNCE)
            a.onInput("riya")
            a.onInput("")
            delay(DEBOUNCE * 2)
            assertEquals(Status.Empty, a.status.value)
            assertEquals(emptyList<String>(), checked)
        }
    }

    @Test fun networkErrorShowsFailed() = runBlocking {
        coroutineScope {
            val a = UsernameAvailability(this, { error("offline") }, debounceMs = DEBOUNCE)
            a.onInput("riya")
            delay(DEBOUNCE * 3)
            assertEquals(Status.Failed, a.status.value)
        }
    }

    private companion object {
        const val DEBOUNCE = 60L
    }
}
