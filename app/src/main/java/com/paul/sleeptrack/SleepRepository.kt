package com.paul.sleeptrack

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.random.Random
import kotlin.reflect.KClass

const val PERMISSION_READ_HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"
const val PERMISSION_READ_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
val PERMISSION_READ_SLEEP = HealthPermission.getReadPermission(SleepSessionRecord::class)
val PERMISSION_READ_STEPS = HealthPermission.getReadPermission(StepsRecord::class)
val PERMISSION_READ_HEART = HealthPermission.getReadPermission(RestingHeartRateRecord::class)
val PERMISSION_READ_WEIGHT = HealthPermission.getReadPermission(WeightRecord::class)

/** Les lectures de données : au moins une suffit pour afficher quelque chose. */
val DATA_PERMISSIONS = mapOf(
    Metric.SLEEP to PERMISSION_READ_SLEEP,
    Metric.STEPS to PERMISSION_READ_STEPS,
    Metric.HEART to PERMISSION_READ_HEART,
    Metric.WEIGHT to PERMISSION_READ_WEIGHT,
)

val REQUESTED_PERMISSIONS =
    DATA_PERMISSIONS.values.toSet() + PERMISSION_READ_HISTORY + PERMISSION_READ_BACKGROUND

/** Ce qu'on demande vraiment : inutile de réclamer le poids si l'utilisateur l'a masqué. */
fun requestedPermissions(visible: List<Metric>): Set<String> =
    visible.mapNotNull { DATA_PERMISSIONS[it] }.toSet() + PERMISSION_READ_HISTORY + PERMISSION_READ_BACKGROUND

private val NOT_ASLEEP = setOf(
    SleepSessionRecord.STAGE_TYPE_AWAKE,
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
)

/** Vrai si le message ressemble à un rate limit Health Connect. La classe exacte de
 *  l'exception varie selon la version/l'appareil (ni HealthConnectException ni
 *  IllegalStateException ne correspondent en pratique — testé), donc on se fie au
 *  texte plutôt qu'au type. */
private fun isRateLimitMessage(e: Throwable): Boolean =
    e.message?.contains("rate limit", ignoreCase = true) == true ||
        e.message?.contains("quota", ignoreCase = true) == true

/** Essaie l'appel, et s'il se fait jeter pour rate limit, retente une fois après une
 *  pause avant d'abandonner. Renvoie null si les deux tentatives échouent pour rate
 *  limit — à l'appelant de garder ce qu'il a déjà accumulé plutôt que de tout perdre.
 *  Toute autre erreur est relancée telle quelle : ce n'est pas à cette fonction de la
 *  masquer. */
private suspend fun <T> onceMoreOnRateLimit(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    if (!isRateLimitMessage(e)) throw e
    delay(2500)
    try {
        block()
    } catch (e2: CancellationException) {
        throw e2
    } catch (e2: Exception) {
        if (!isRateLimitMessage(e2)) throw e2
        null
    }
}

/** Ce que renvoie une lecture : les données obtenues, et si le rate limiter de Health
 *  Connect a forcé à s'arrêter en cours de route (dans ce cas, les données sont
 *  partielles — pas fausses, juste incomplètes). */
data class HealthLoadResult(val data: HealthData, val rateLimited: Boolean)

/** Lit toutes les métriques autorisées entre deux dates incluses. `concurrent` contrôle
 *  la stratégie : en parallèle (rapide) pour les petites lectures fréquentes comme le
 *  rattrapage des derniers jours au resume, ou séquentiel + espacé (plus lent mais
 *  bien plus sûr) pour la grosse lecture initiale d'une année complète, où le volume
 *  de données rend un rate limit beaucoup plus probable. Si Health Connect coupe la
 *  lecture en route, on renvoie ce qui a déjà été récupéré plutôt que de tout perdre. */
suspend fun loadHealthData(
    client: HealthConnectClient,
    granted: Set<String>,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
    concurrent: Boolean = true,
): HealthLoadResult {
    suspend fun sleep() =
        if (PERMISSION_READ_SLEEP in granted) readSleepByNight(client, from, to, zone) else PartialResult(emptyMap(), false)
    suspend fun steps() =
        if (PERMISSION_READ_STEPS in granted) readStepsByDay(client, from, to, zone) else PartialResult(emptyMap(), false)
    suspend fun heart() =
        if (PERMISSION_READ_HEART in granted) readRestingHeartRate(client, from, to, zone) else PartialResult(emptyMap(), false)
    suspend fun weight() =
        if (PERMISSION_READ_WEIGHT in granted) readWeight(client, from, to, zone) else PartialResult(emptyMap(), false)

    // Petite classe locale juste pour porter les 4 résultats typés hors du bloc
    // concurrent/séquentiel sans passer par une List hétérogène (qui perdrait les
    // types précis et forcerait des casts non vérifiés).
    data class FourResults(
        val nights: PartialResult<Map<LocalDate, Duration>>,
        val steps: PartialResult<Map<LocalDate, Long>>,
        val heart: PartialResult<Map<LocalDate, Double>>,
        val weight: PartialResult<Map<LocalDate, Double>>,
    )

    val results = if (concurrent) {
        coroutineScope {
            val n = async { sleep() }
            val s = async { steps() }
            val h = async { heart() }
            val w = async { weight() }
            FourResults(n.await(), s.await(), h.await(), w.await())
        }
    } else {
        val n = sleep(); delay(200)
        val s = steps(); delay(200)
        val h = heart(); delay(200)
        val w = weight()
        FourResults(n, s, h, w)
    }

    return HealthLoadResult(
        data = HealthData(
            nights = results.nights.data,
            steps = results.steps.data,
            heart = results.heart.data,
            weight = results.weight.data,
        ),
        rateLimited = results.nights.rateLimited || results.steps.rateLimited ||
            results.heart.rateLimited || results.weight.rateLimited,
    )
}

suspend fun loadYear(
    client: HealthConnectClient,
    granted: Set<String>,
    year: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): HealthLoadResult = loadHealthData(
    client, granted, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), zone, concurrent = false,
)

/** Résultat d'une sous-lecture : les données glanées, et si elle a dû s'arrêter en
 *  route à cause du rate limiter. */
data class PartialResult<T>(val data: T, val rateLimited: Boolean)

/** Durée de sommeil par nuit, la nuit étant rattachée à la date du réveil. */
suspend fun readSleepByNight(
    client: HealthConnectClient,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): PartialResult<Map<LocalDate, Duration>> {
    val start = from.minusDays(1).atStartOfDay(zone).toInstant()
    val end = to.plusDays(2).atStartOfDay(zone).toInstant()
    val intervalsByDate = mutableMapOf<LocalDate, MutableList<Pair<Instant, Instant>>>()

    var pageToken: String? = null
    var firstPage = true
    var rateLimited = false
    do {
        if (!firstPage) delay(150)
        firstPage = false
        val response = onceMoreOnRateLimit {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageToken = pageToken,
                )
            )
        }
        if (response == null) {
            rateLimited = true
            break
        }
        for (session in response.records) {
            val date = session.endTime.atZone(zone).toLocalDate()
            if (date < from || date > to) continue
            val asleep = if (session.stages.isEmpty()) {
                listOf(session.startTime to session.endTime)
            } else {
                session.stages.filter { it.stage !in NOT_ASLEEP }.map { it.startTime to it.endTime }
            }
            intervalsByDate.getOrPut(date) { mutableListOf() } += asleep
        }
        pageToken = response.pageToken
    } while (pageToken != null)

    val result = intervalsByDate
        .mapValues { (_, intervals) -> mergedDuration(intervals) }
        .filterValues { !it.isZero }
    return PartialResult(result, rateLimited)
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
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): PartialResult<Map<LocalDate, Long>> {
    val out = mutableMapOf<LocalDate, Long>()
    val limit = minOf(to.plusDays(1), LocalDate.now(zone).plusDays(1))
    var chunkStart = from
    var first = true
    var rateLimited = false
    while (chunkStart.isBefore(limit)) {
        // Une année complète, c'est jusqu'à 12 appels d'affilée ; un petit espacement
        // évite de déclencher le rate limiter interne de Health Connect quand cette
        // lecture s'ajoute à celles du sommeil/cœur/poids dans le même chargement.
        if (!first) delay(120)
        first = false
        val chunkEnd = minOf(chunkStart.plusMonths(1), limit)
        val groups = onceMoreOnRateLimit {
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(chunkStart.atStartOfDay(), chunkEnd.atStartOfDay()),
                    timeRangeSlicer = Period.ofDays(1),
                )
            )
        }
        if (groups == null) {
            rateLimited = true
            break
        }
        for (group in groups) {
            val count = group.result[StepsRecord.COUNT_TOTAL] ?: continue
            if (count > 0) out[group.startTime.toLocalDate()] = count
        }
        chunkStart = chunkStart.plusMonths(1)
    }
    return PartialResult(out, rateLimited)
}

/** Fréquence cardiaque au repos : moyenne des relevés du jour. */
suspend fun readRestingHeartRate(
    client: HealthConnectClient,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): PartialResult<Map<LocalDate, Double>> = readDailyMean(
    client, RestingHeartRateRecord::class, from, to, zone,
    at = { it.time }, value = { it.beatsPerMinute.toDouble() },
)

/** Poids en kilos : moyenne des pesées du jour. */
suspend fun readWeight(
    client: HealthConnectClient,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): PartialResult<Map<LocalDate, Double>> = readDailyMean(
    client, WeightRecord::class, from, to, zone,
    at = { it.time }, value = { it.weight.inKilograms },
)

// Cœur au repos et poids tiennent en quelques relevés par jour : la lecture brute
// évite d'avoir à deviner le type de retour des agrégats.
private suspend fun <T : Record> readDailyMean(
    client: HealthConnectClient,
    type: KClass<T>,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId,
    at: (T) -> Instant,
    value: (T) -> Double,
): PartialResult<Map<LocalDate, Double>> {
    val start = from.atStartOfDay(zone).toInstant()
    val end = to.plusDays(1).atStartOfDay(zone).toInstant()
    val sums = mutableMapOf<LocalDate, Pair<Double, Int>>()

    var pageToken: String? = null
    var firstPage = true
    var rateLimited = false
    do {
        if (!firstPage) delay(150)
        firstPage = false
        val response = onceMoreOnRateLimit {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageToken = pageToken,
                )
            )
        }
        if (response == null) {
            rateLimited = true
            break
        }
        for (record in response.records) {
            val date = at(record).atZone(zone).toLocalDate()
            val (sum, n) = sums[date] ?: (0.0 to 0)
            sums[date] = (sum + value(record)) to (n + 1)
        }
        pageToken = response.pageToken
    } while (pageToken != null)

    val result = sums.mapValues { (_, acc) -> acc.first / acc.second }
    return PartialResult(result, rateLimited)
}

fun demoHealthData(year: Int): HealthData {
    val rnd = Random(year)
    val today = LocalDate.now()
    val nights = mutableMapOf<LocalDate, Duration>()
    val steps = mutableMapOf<LocalDate, Long>()
    val heart = mutableMapOf<LocalDate, Double>()
    val weight = mutableMapOf<LocalDate, Double>()
    var kg = 72.0
    var activeYesterday = false

    var d = LocalDate.of(year, 1, 1)
    while (d.year == year && !d.isAfter(today)) {
        val active = rnd.nextFloat() > 0.25f
        val walked = (if (active) 9_500 else 4_500) + rnd.nextInt(-2_500, 2_500)
        steps[d] = walked.toLong().coerceAtLeast(400)
        if (rnd.nextFloat() > 0.08f) {
            // La nuit qui se termine le jour d suit la soirée de d-1 : c'est l'activité
            // de la veille qui l'allonge, de quoi donner à la corrélation quelque chose
            // à montrer en mode démo.
            val bonus = if (activeYesterday) 22 else -18
            val minutes = (7.1 * 60 + bonus + rnd.nextDouble(-1.0, 1.0) * 105).toLong().coerceIn(180, 660)
            nights[d] = Duration.ofMinutes(minutes)
        }
        activeYesterday = active
        if (rnd.nextFloat() > 0.3f) heart[d] = 56.0 + rnd.nextDouble(-5.0, 6.0)
        if (rnd.nextFloat() > 0.5f) {
            kg = (kg + rnd.nextDouble(-0.25, 0.22)).coerceIn(69.0, 76.0)
            weight[d] = kg
        }
        d = d.plusDays(1)
    }
    return HealthData(nights, steps, heart, weight)
}
