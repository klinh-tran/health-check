package com.example.healthcheck.core.csv

import com.example.healthcheck.core.model.DayRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ImportPlannerTest {
    private val d1 = LocalDate.of(2026, 11, 20)
    private val d2 = d1.plusDays(1)
    private val d3 = d1.plusDays(2)

    private val existing = listOf(
        DayRecord(d1, steps = 1000),
        DayRecord(d2, steps = 2000, isDemo = true),
    )
    private val incoming = listOf(DayRecord(d1, steps = 10), DayRecord(d2, steps = 20), DayRecord(d3, steps = 30))

    @Test fun keepExistingSkipsRealDaysButReplacesDemoDays() {
        val plan = ImportPlanner.plan(existing, incoming, DuplicateMode.KEEP_EXISTING)
        assertEquals(listOf(d2, d3), plan.toWrite.map { it.date })
        assertEquals(1, plan.skipped)
        assertEquals(0, plan.replaced)
        assertEquals(1, plan.replacedDemo)
        assertEquals(2, plan.imported)
    }

    @Test fun replaceExistingWritesEveryDay() {
        val plan = ImportPlanner.plan(existing, incoming, DuplicateMode.REPLACE_EXISTING)
        assertEquals(listOf(10, 20, 30), plan.toWrite.map { it.steps })
        assertEquals(0, plan.skipped)
        assertEquals(1, plan.replaced)
        assertEquals(1, plan.replacedDemo)
    }

    @Test fun writtenDaysAreNeverDemo() {
        val plan = ImportPlanner.plan(emptyList(), incoming.map { it.copy(isDemo = true) }, DuplicateMode.KEEP_EXISTING)
        assertTrue(plan.toWrite.none { it.isDemo })
    }
}
