package io.github.fartown.movo.agent.tools.personal

/**
 * 健康多来源去重聚合的纯逻辑。Health Connect 里同一度量常被多个来源重复记录
 * （手机计步 + 手表计步），直接跨来源 SUM 会翻倍。
 *
 * 策略：按度量分组，每个度量只认一个「主来源」的数值，而不是把各来源相加。主来源取
 * 记录条数最多者（数据最完整），条数并列时取累计数值最大者。抽成纯函数便于单测
 * （见 HealthSourceAggregatorTest）。
 *
 * 注意：要真正接上端到端去重，需要数据层按 package/device 分来源暴露明细。当前 Root 读
 * healthconnect.db 的聚合在 AgentPrivateDatabaseTools 直接 SUM、不分来源，接线见
 * AndroidHealthReadBackend 的说明。
 */

/** 一条按来源拆分的健康度量样本：度量名、来源（包名/设备）、数值、记录条数。 */
internal data class HealthSourceSample(
    val metric: String,
    val source: String,
    val value: Long,
    val records: Int = 1,
)

/** 去重聚合后某度量选中的主来源及其数值。 */
internal data class HealthMetricTotal(
    val metric: String,
    val source: String,
    val value: Long,
    val records: Int,
)

internal object HealthSourceAggregator {

    /** 按度量分组，各取主来源，返回 度量 → 去重后数值。 */
    fun dedupedTotals(samples: List<HealthSourceSample>): Map<String, Long> =
        dedupe(samples).associate { it.metric to it.value }

    /** 按度量分组，各取主来源（记录数最多，其次数值最大）。 */
    fun dedupe(samples: List<HealthSourceSample>): List<HealthMetricTotal> =
        samples.groupBy { it.metric }
            .map { (metric, metricSamples) ->
                val perSource = metricSamples.groupBy { it.source }
                    .map { (source, rows) ->
                        HealthMetricTotal(metric, source, rows.sumOf { it.value }, rows.sumOf { it.records })
                    }
                perSource.maxWithOrNull(compareBy({ it.records }, { it.value }))
                    ?: HealthMetricTotal(metric, source = "", value = 0L, records = 0)
            }
            .sortedBy { it.metric }
}
