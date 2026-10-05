package io.github.fartown.movo.agent.tools.personal

import org.junit.Assert.assertEquals
import org.junit.Test

/** 健康多来源去重纯逻辑的单测：手机 + 手表不相加，按来源取单一主来源。 */
class HealthSourceAggregatorTest {

    @Test
    fun steps_notSummedAcrossSources_picksSourceWithMostRecords() {
        val samples = listOf(
            HealthSourceSample(metric = "steps", source = "phone", value = 8_000, records = 10),
            HealthSourceSample(metric = "steps", source = "watch", value = 7_000, records = 5),
            HealthSourceSample(metric = "sleep", source = "phone", value = 480, records = 1),
        )
        val totals = HealthSourceAggregator.dedupedTotals(samples)
        assertEquals("步数取记录更全的手机，不是 15000", 8_000L, totals["steps"])
        assertEquals(480L, totals["sleep"])
    }

    @Test
    fun tieOnRecords_picksHigherValue() {
        val samples = listOf(
            HealthSourceSample(metric = "steps", source = "phoneA", value = 8_000, records = 5),
            HealthSourceSample(metric = "steps", source = "phoneB", value = 9_000, records = 5),
        )
        val chosen = HealthSourceAggregator.dedupe(samples).single()
        assertEquals("phoneB", chosen.source)
        assertEquals(9_000L, chosen.value)
    }

    @Test
    fun sameSourceMultipleRows_areAggregatedWithinSource() {
        val samples = listOf(
            HealthSourceSample(metric = "steps", source = "phone", value = 3_000, records = 1),
            HealthSourceSample(metric = "steps", source = "phone", value = 2_000, records = 1),
            HealthSourceSample(metric = "steps", source = "watch", value = 4_000, records = 1),
        )
        // phone 同来源两条相加 5000（records 2） > watch 4000（records 1）→ 取 phone 5000。
        assertEquals(5_000L, HealthSourceAggregator.dedupedTotals(samples)["steps"])
    }

    @Test
    fun emptyInput_emptyResult() {
        assertEquals(emptyMap<String, Long>(), HealthSourceAggregator.dedupedTotals(emptyList()))
    }
}
