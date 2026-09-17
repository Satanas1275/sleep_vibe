package com.paul.sleeptrack

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.random.Random

const val PERMISSION_READ_HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"
val PERMISSION_READ_SLEEP = HealthPermission.getReadPermission(SleepSessionRecord::class)
val PERMISSION_READ_STEPS = HealthPermission.getReadPermission(StepsRecord::class)
val REQUESTED_PERMISSIONS = setOf(PERMISSION_READ_SLEEP, PERMISSION_READ_STEPS, PERMISSION_READ_HISTORY)

private val NOT_ASLEEP = setOf(
    SleepSessionRecord.STAGE_TYPE_AWAKE,
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
)

/** Durée de sommeil par nuit, la nuit étant rattachée à la date du réveil. */
suspend fun readSleepByNight(
    client: HealthConnectClient,
    year: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): Map<LocalDate, Duration> {
    val start = LocalDate.of(year, 1, 1).minusDays(1).atStartOfDay(zone).toInstant()
    val end = LocalDate.of(year + 1, 1, 1).plusDays(1).atStartOfDay(zone).toInstant()
    val intervalsByDate = mutableMapOf<LocalDate, MutableList<Pair<Instant, Instant>>>()

    var pageToken: String? = null
    do {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
                pageToken = pageToken,
            )
        )
        for (session in response.records) {
            val date = session.endTime.atZone(zone).toLocalDate()
            if (date.year != year) continue
            val asleep = if (session.stages.isEmpty()) {
                listOf(session.startTime to session.endTime)
            } else {
                session.stages.filter { it.stage !in NOT_ASLEEP }.map { it.startTime to it.endTime }
            }
            intervalsByDate.getOrPut(date) { mutableListOf() } += asleep
        }
        pageToken = response.pageToken
    } while (pageToken != null)

    return intervalsByDate
        .mapValues { (_, intervals) -> mergedDuration(intervals) }
        .filterValues { !it.isZero }
}

// Plusieurs applis (montre + téléphone) peuvent enregistrer la même nuit : on fusionne les chevauchements.
private fun mergedDuration(intervals: List<Pair<Instant, Instant>>): Duration {
    var total = Duration.ZERO
    var curStart: Instant? = null
    var curEnd: Instant? = null
    for ((s, e) in intervals.sortedBy { it.first }) {
        if (curStart == null || curEnd == null || s > curEnd) {
            if (curStart != null && curEnd != null) total += Duration.between(curStart, curEnd)
            curStart = s
            curEnd = e
        } else if (e > curEnd) {
            curEnd = e
        }
    }
    if (curStart != null && curEnd != null) total += Duration.between(curStart, curEnd)
    return total
}

/** Nombre de pas par jour. L'agrégation Health Connect dédoublonne déjà montre et téléphone. */
suspend fun readStepsByDay(
    client: HealthConnectClient,
    year: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): Map<LocalDate, Long> {
    val out = mutableMapOf<LocalDate, Long>()
    val limit = minOf(LocalDate.of(year + 1, 1, 1), LocalDate.now(zone).plusDays(1))
    var monthStart = LocalDate.of(year, 1, 1)
    while (monthStart.isBefore(limit)) {
        val monthEnd = minOf(monthStart.plusMonths(1), limit)
        val groups = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(monthStart.atStartOfDay(), monthEnd.atStartOfDay()),
                timeRangeSlicer = Period.ofDays(1),
            )
        )
        for (group in groups) {
            val count = group.result[StepsRecord.COUNT_TOTAL] ?: continue
            if (count > 0) out[group.startTime.toLocalDate()] = count
        }
        monthStart = monthStart.plusMonths(1)
    }
    return out
}

fun demoSteps(year: Int): Map<LocalDate, Long> {
    val rnd = Random(year + 1)
    return demoData(year).keys.associateWith { (6500 + rnd.nextInt(-4000, 7000)).toLong().coerceAtLeast(400) }
}

fun demoData(year: Int): Map<LocalDate, Duration> {
    val rnd = Random(year)
    val today = LocalDate.now()
    val out = mutableMapOf<LocalDate, Duration>()
    var d = LocalDate.of(year, 1, 1)
    while (d.year == year && !d.isAfter(today)) {
        if (rnd.nextFloat() > 0.08f) {
            val minutes = (7.3 * 60 + rnd.nextDouble(-1.0, 1.0) * 150).toLong().coerceIn(180, 660)
            out[d] = Duration.ofMinutes(minutes)
        }
        d = d.plusDays(1)
    }
    return out
}
