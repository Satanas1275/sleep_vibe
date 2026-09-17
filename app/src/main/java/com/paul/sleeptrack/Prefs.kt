package com.paul.sleeptrack

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate

/** Réglages de l'app : rappels, objectif, métrique affichée par le widget. */
object Prefs {
    private const val FILE = "sommeil"

    const val EVENING_ENABLED = "evening_enabled"
    const val EVENING_HOUR = "evening_hour"
    const val WEEKLY_ENABLED = "weekly_enabled"
    const val WEEKLY_HOUR = "weekly_hour"
    const val GOAL_MINUTES = "goal_minutes"
    const val WIDGET_METRIC = "widget_metric"

    const val DEFAULT_EVENING_HOUR = 22
    const val DEFAULT_WEEKLY_HOUR = 19
    const val DEFAULT_GOAL_MINUTES = 420

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun eveningEnabled(context: Context) = of(context).getBoolean(EVENING_ENABLED, false)
    fun eveningHour(context: Context) = of(context).getInt(EVENING_HOUR, DEFAULT_EVENING_HOUR)
    fun weeklyEnabled(context: Context) = of(context).getBoolean(WEEKLY_ENABLED, false)
    fun weeklyHour(context: Context) = of(context).getInt(WEEKLY_HOUR, DEFAULT_WEEKLY_HOUR)
    fun goalMinutes(context: Context) = of(context).getInt(GOAL_MINUTES, DEFAULT_GOAL_MINUTES)
    fun goal(context: Context): Duration = Duration.ofMinutes(goalMinutes(context).toLong())

    fun widgetMetric(context: Context): Metric =
        runCatching { Metric.valueOf(of(context).getString(WIDGET_METRIC, null) ?: "") }
            .getOrDefault(Metric.SLEEP)

    fun setWidgetMetric(context: Context, metric: Metric) {
        of(context).edit().putString(WIDGET_METRIC, metric.name).apply()
    }
}

/**
 * Dernières données lues, gardées pour que le widget et les rappels aient quelque chose
 * à afficher sans rouvrir l'app. Rien ne sort du téléphone : c'est un simple fichier de
 * préférences privé.
 */
object DataCache {
    private const val KEY = "cache"
    private const val DAYS_KEPT = 200L

    fun save(context: Context, data: HealthData) {
        val floor = LocalDate.now().minusDays(DAYS_KEPT)
        val root = JSONObject()
        root.put("sleep", jsonOf(data.nights.filterKeys { it > floor }.mapValues { it.value.toMinutes().toDouble() }))
        root.put("steps", jsonOf(data.steps.filterKeys { it > floor }.mapValues { it.value.toDouble() }))
        root.put("heart", jsonOf(data.heart.filterKeys { it > floor }))
        root.put("weight", jsonOf(data.weight.filterKeys { it > floor }))
        Prefs.of(context).edit().putString(KEY, root.toString()).apply()
    }

    fun load(context: Context): HealthData {
        val raw = Prefs.of(context).getString(KEY, null) ?: return HealthData()
        return runCatching {
            val root = JSONObject(raw)
            HealthData(
                nights = readMap(root, "sleep").mapValues { Duration.ofMinutes(it.value.toLong()) },
                steps = readMap(root, "steps").mapValues { it.value.toLong() },
                heart = readMap(root, "heart"),
                weight = readMap(root, "weight"),
            )
        }.getOrDefault(HealthData())
    }

    private fun jsonOf(values: Map<LocalDate, Double>): JSONObject {
        val obj = JSONObject()
        values.forEach { (date, value) -> obj.put(date.toString(), value) }
        return obj
    }

    private fun readMap(root: JSONObject, name: String): Map<LocalDate, Double> {
        val obj = root.optJSONObject(name) ?: return emptyMap()
        val out = mutableMapOf<LocalDate, Double>()
        obj.keys().forEach { key ->
            runCatching { out[LocalDate.parse(key)] = obj.getDouble(key) }
        }
        return out
    }
}
