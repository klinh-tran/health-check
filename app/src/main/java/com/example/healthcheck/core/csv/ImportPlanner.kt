package com.example.healthcheck.core.csv

import com.example.healthcheck.core.model.DayRecord

/** What to do when an imported day already has data on this phone. */
enum class DuplicateMode { KEEP_EXISTING, REPLACE_EXISTING }

/**
 * The result of matching imported days against the days already stored.
 * [toWrite] holds every day that will be written (new days plus replaced ones), always as real data.
 */
data class ImportPlan(
    val toWrite: List<DayRecord>,
    /** Real days already on the phone that were kept (Keep mode). */
    val skipped: Int,
    /** Real days already on the phone that were overwritten (Replace mode). */
    val replaced: Int,
    /** Demo days that were overwritten. Real data always replaces demo data, in either mode. */
    val replacedDemo: Int,
) {
    val imported: Int get() = toWrite.size
}

object ImportPlanner {
    fun plan(existing: List<DayRecord>, incoming: List<DayRecord>, mode: DuplicateMode): ImportPlan {
        val byDate = existing.associateBy { it.date }
        val toWrite = ArrayList<DayRecord>(incoming.size)
        var skipped = 0
        var replaced = 0
        var replacedDemo = 0
        for (r in incoming) {
            val old = byDate[r.date]
            when {
                old == null -> toWrite += r
                old.isDemo -> { toWrite += r; replacedDemo++ }
                mode == DuplicateMode.KEEP_EXISTING -> skipped++
                else -> { toWrite += r; replaced++ }
            }
        }
        return ImportPlan(toWrite.map { it.copy(isDemo = false) }, skipped, replaced, replacedDemo)
    }
}
