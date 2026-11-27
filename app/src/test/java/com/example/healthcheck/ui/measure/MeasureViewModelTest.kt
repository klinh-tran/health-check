package com.example.healthcheck.ui.measure

import com.example.healthcheck.core.signal.FrameSample
import com.example.healthcheck.core.signal.SessionPhase
import com.example.healthcheck.core.signal.Synth
import com.example.healthcheck.data.DailyEntry
import com.example.healthcheck.data.DailyEntryDao
import com.example.healthcheck.data.EntryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

private class MemoryDao : DailyEntryDao {
    val rows = MutableStateFlow<Map<LocalDate, DailyEntry>>(emptyMap())
    override fun observeAll(): Flow<List<DailyEntry>> = rows.map { it.values.sortedBy(DailyEntry::date) }
    override suspend fun getAll() = rows.value.values.sortedBy(DailyEntry::date)
    override suspend fun get(date: LocalDate) = rows.value[date]
    override suspend fun upsert(entry: DailyEntry) { rows.value = rows.value + (entry.date to entry) }
    override suspend fun upsertAll(entries: List<DailyEntry>) { rows.value = rows.value + entries.associateBy { it.date } }
    override suspend fun delete(date: LocalDate) { rows.value = rows.value - date }
    override suspend fun deleteAll() { rows.value = emptyMap() }
    override suspend fun deleteDemo() { rows.value = rows.value.filterValues { !it.isDemo } }
}

/**
 * The retry paths, end to end through the ViewModel (frames are fed the way the camera's analyzer
 * thread feeds them). Runs on the real Default dispatcher; no Android or test-dispatcher library needed.
 */
class MeasureViewModelTest {

    private val dao = MemoryDao()
    /** Frames arrive much faster than real time here, so the live-update clock advances one frame per call. */
    private val fakeClock = AtomicLong(0)
    private val vm = MeasureViewModel(EntryRepository(dao) { 1L }, Dispatchers.Default, clockMs = { fakeClock.addAndGet(34) })

    private fun run(bpm: Double, seconds: Double = 66.0) = Synth.frames(Synth.beatsAtBpm(bpm, seconds), durationS = seconds)
    private fun room(seconds: Double) = run(70.0, seconds).map { it.copy(r = 120.0, g = 115.0, b = 110.0, coverage = 0.0) }

    private fun feed(frames: List<FrameSample>, id: Int = vm.state.value.sessionId) = frames.forEach { vm.onFrame(id, it) }

    private fun await(timeoutMs: Long = 30_000, what: String, done: (MeasureUiState) -> Boolean): MeasureUiState {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val s = vm.state.value
            if (done(s)) return s
            Thread.sleep(5)
        }
        fail("timed out waiting for $what; state=${vm.state.value}")
        error("")
    }

    private fun awaitPhase(vararg phases: MeasurePhase) = await(what = phases.joinToString()) { it.phase in phases }

    private fun measureSuccessfully(bpm: Double): MeasureUiState {
        vm.begin()
        assertEquals(MeasurePhase.STARTING, vm.state.value.phase)
        feed(run(bpm))
        val s = awaitPhase(MeasurePhase.SUCCESS, MeasurePhase.FAILED)
        assertEquals("run failed: ${s.failureCode} ${s.failureMessage}", MeasurePhase.SUCCESS, s.phase)
        return s
    }

    @Test fun twoRunsInARowBothWork() {
        val first = measureSuccessfully(64.0)
        assertEquals(64.0, first.result!!.heartRateBpm, 2.0)
        vm.reset()
        val second = measureSuccessfully(88.0)
        assertEquals(88.0, second.result!!.heartRateBpm, 2.0)
        assertNotEquals(first.sessionId, second.sessionId)
        assertEquals(88.0, dao.rows.value.values.single().heartRateBpm!!, 2.0)
    }

    @Test fun cancelThenRestartStartsCleanAndCompletes() {
        vm.begin()
        val firstId = vm.state.value.sessionId
        feed(run(70.0, 20.0))
        await(what = "counting") { it.session == SessionPhase.MEASURING && it.measuredS > 5 }
        vm.cancel()
        assertEquals(MeasurePhase.INSTRUCTIONS, vm.state.value.phase)

        vm.begin()
        val fresh = vm.state.value
        assertNotEquals(firstId, fresh.sessionId)
        assertEquals(MeasurePhase.STARTING, fresh.phase)
        assertEquals(0.0, fresh.measuredS, 0.0)
        assertEquals(0, fresh.beatCount)
        assertNull(fresh.live)
        assertNull(fresh.result)

        // A late frame burst from the old camera (old id) must be ignored: if it got through, 15 s of
        // "no finger" would end the new run with a timeout.
        feed(room(15.0), id = firstId)
        feed(run(76.0))
        val done = awaitPhase(MeasurePhase.SUCCESS, MeasurePhase.FAILED)
        assertEquals("${done.failureCode}", MeasurePhase.SUCCESS, done.phase)
        assertEquals(76.0, done.result!!.heartRateBpm, 2.0)
    }

    @Test fun afterAFailedRunTryAgainWorksImmediately() {
        vm.begin()
        feed(room(12.0))
        val failed = awaitPhase(MeasurePhase.FAILED)
        assertEquals("NO_FINGER_TIMEOUT", failed.failureCode)
        // "Try again" = reset + begin
        vm.reset()
        assertEquals(MeasurePhase.INSTRUCTIONS, vm.state.value.phase)
        assertEquals(70.0, measureSuccessfully(70.0).result!!.heartRateBpm, 2.0)
    }

    @Test fun afterALowQualityRejectionTryAgainWorks() {
        vm.begin()
        // Finger only for the first 15 s, then a long drop: analysed and rejected.
        feed(run(70.0, 30.0).map { s -> if (s.timestampNs > 15_000_000_000L) s.copy(r = 120.0, g = 115.0, b = 110.0, coverage = 0.0) else s })
        val failed = awaitPhase(MeasurePhase.FAILED)
        assertNotEquals(null, failed.diagnostics)
        vm.reset()
        measureSuccessfully(70.0)
    }

    @Test fun leavingTheAppThenComingBackWorks() {
        vm.begin()
        feed(run(70.0, 10.0))
        await(what = "measuring") { it.phase == MeasurePhase.MEASURING }
        vm.onLeftApp()
        assertEquals(MeasurePhase.FAILED, vm.state.value.phase)
        assertEquals("LEFT_APP", vm.state.value.failureCode)
        vm.reset()
        measureSuccessfully(70.0)
    }

    @Test fun beginWhileAlreadyMeasuringRestartsInsteadOfGettingStuck() {
        vm.begin()
        feed(run(70.0, 10.0))
        await(what = "measuring") { it.phase == MeasurePhase.MEASURING }
        measureSuccessfully(81.0)
    }

    @Test fun aCameraErrorCanBeRetried() {
        vm.begin()
        vm.onCameraUnavailable(vm.state.value.sessionId, "Camera unavailable", "busy", canRetry = true)
        assertEquals(MeasurePhase.FAILED, vm.state.value.phase)
        // A late error report from an old camera doesn't touch the new run.
        val oldId = vm.state.value.sessionId - 1
        vm.reset()
        vm.begin()
        vm.onCameraUnavailable(oldId, "Camera unavailable", "late", canRetry = true)
        assertEquals(MeasurePhase.STARTING, vm.state.value.phase)
        feed(run(70.0))
        assertEquals(MeasurePhase.SUCCESS, awaitPhase(MeasurePhase.SUCCESS, MeasurePhase.FAILED).phase)
    }
}
