package com.syncit.app.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.syncit.app.ui.glass.ControlShape
import com.syncit.app.ui.glass.raised
import com.syncit.app.ui.theme.Glass
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Dates of birth (2026-11-10): stored as "YYYY-MM-DD", private to the person and the app's owner. */
object BirthDates {
    const val EARLIEST_YEAR = 1920

    /** BingeJam is 18+ (Terms); younger dates are refused (user's request, 2026-11-10). */
    const val MIN_AGE = 18
    private val DISPLAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

    fun parse(value: String?): LocalDate? = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun wire(date: LocalDate): String = date.toString()

    fun display(date: LocalDate): String = date.format(DISPLAY)

    fun isValid(date: LocalDate, today: LocalDate = LocalDate.now()): Boolean = date.year >= EARLIEST_YEAR && !date.isAfter(today)

    fun age(date: LocalDate, today: LocalDate = LocalDate.now()): Int = Period.between(date, today).years

    /** What's wrong with [date], worded for the person entering it, or null if it's fine. */
    fun problem(date: LocalDate, today: LocalDate = LocalDate.now()): String? = when {
        !isValid(date, today) -> "That doesn't look like a real date of birth"
        age(date, today) < MIN_AGE -> "You need to be $MIN_AGE or older to use BingeJam"
        else -> null
    }

    /** The next birthday on or after [today]; 29 February falls on 28 February in other years. */
    fun nextBirthday(date: LocalDate, today: LocalDate = LocalDate.now()): LocalDate {
        val thisYear = birthdayIn(date, today.year)
        return if (thisYear.isBefore(today)) birthdayIn(date, today.year + 1) else thisYear
    }

    fun daysUntil(date: LocalDate, today: LocalDate = LocalDate.now()): Long = ChronoUnit.DAYS.between(today, nextBirthday(date, today))

    /** "today", "tomorrow", "in 12 days". */
    fun whenText(days: Long): String = when (days) {
        0L -> "today"
        1L -> "tomorrow"
        else -> "in $days days"
    }

    private fun birthdayIn(date: LocalDate, year: Int): LocalDate =
        if (date.monthValue == 2 && date.dayOfMonth == 29 && !java.time.Year.isLeap(year.toLong())) {
            LocalDate.of(year, 2, 28)
        } else {
            date.withYear(year)
        }
}

/** A field that opens the calendar; shows the picked date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthDateField(value: LocalDate?, onPick: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val p = Glass.palette
    var open by remember { mutableStateOf(false) }
    val problem = value?.let { BirthDates.problem(it) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(ControlShape)
            .raised(ControlShape)
            .clickable(role = Role.Button, onClickLabel = "Pick your date of birth") { open = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.DateRange, contentDescription = null, tint = p.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            value?.let(BirthDates::display) ?: "Date of birth",
            style = MaterialTheme.typography.bodyLarge,
            color = if (value == null) p.textTertiary else p.textPrimary,
        )
    }
    problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.danger) }
    }
    if (open) {
        val today = LocalDate.now()
        val todayMillis = today.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = value?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli(),
            initialDisplayedMonthMillis = (value ?: LocalDate.of(2000, 1, 1)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            yearRange = BirthDates.EARLIEST_YEAR..today.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayMillis

                override fun isSelectableYear(year: Int): Boolean = year in BirthDates.EARLIEST_YEAR..today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { ms -> onPick(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()) }
                        open = false
                    },
                    enabled = state.selectedDateMillis != null,
                ) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state, title = { Text("Your date of birth", Modifier.padding(start = 24.dp, top = 16.dp)) })
        }
    }
}
