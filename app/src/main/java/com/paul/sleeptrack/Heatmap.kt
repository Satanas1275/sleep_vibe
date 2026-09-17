package com.paul.sleeptrack

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object Palette {
    val bg = Color(0xFF0D0D0D)
    val card = Color(0xFF171717)
    val empty = Color(0xFF262626)
    val text = Color(0xFFF2F2F2)
    val muted = Color(0xFF8A8A8A)

    // Du pire au meilleur : rouge, orange, jaune, vert clair, vert vif.
    val levels = listOf(
        Color(0xFFE5484D),
        Color(0xFFF2994A),
        Color(0xFFF2C94C),
        Color(0xFF8BD17C),
        Color(0xFF2ECC71),
    )
    val longDate: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)
}

enum class Metric(val label: String, val legend: List<String>) {
    SLEEP("Sommeil", listOf("<5h", "5-6h", "6-7h", "7-8h", "8h+")),
    STEPS("Pas", listOf("<3k", "3-6k", "6-8k", "8-10k", "10k+")),
}

fun colorFor(d: Duration): Color {
    val minutes = d.toMinutes()
    return when {
        minutes < 5 * 60 -> Palette.levels[0]
        minutes < 6 * 60 -> Palette.levels[1]
        minutes < 7 * 60 -> Palette.levels[2]
        minutes < 8 * 60 -> Palette.levels[3]
        else -> Palette.levels[4]
    }
}

fun colorForSteps(steps: Long): Color = when {
    steps < 3_000 -> Palette.levels[0]
    steps < 6_000 -> Palette.levels[1]
    steps < 8_000 -> Palette.levels[2]
    steps < 10_000 -> Palette.levels[3]
    else -> Palette.levels[4]
}

private val DAY_LABELS = listOf("Lun", "", "Mer", "", "Ven", "", "Dim")

@Composable
fun YearHeatmap(
    year: Int,
    selected: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
    colorAt: (LocalDate) -> Color?,
) {
    val gridStart = LocalDate.of(year, 1, 1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weeks = (ChronoUnit.DAYS.between(gridStart, LocalDate.of(year, 12, 31)) / 7 + 1).toInt()
    val labelWidth = 28.dp
    val currentOnSelect = rememberUpdatedState(onSelect)
    val currentColorAt = rememberUpdatedState(colorAt)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gridWidth = maxWidth - labelWidth
        val pitch = gridWidth / weeks
        val cell = pitch * 0.8f

        Column {
            Box(Modifier.padding(start = labelWidth).fillMaxWidth().height(16.dp)) {
                for (month in 1..12) {
                    val first = LocalDate.of(year, month, 1)
                    val col = (ChronoUnit.DAYS.between(gridStart, first) / 7).toInt()
                    Text(
                        first.month.getDisplayName(TextStyle.SHORT, Locale.FRENCH)
                            .take(3)
                            .replaceFirstChar { it.uppercase() },
                        color = Palette.muted,
                        fontSize = 9.sp,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.offset(x = pitch * col),
                    )
                }
            }
            Row {
                Column(Modifier.width(labelWidth)) {
                    DAY_LABELS.forEach { label ->
                        Box(Modifier.height(pitch), contentAlignment = Alignment.CenterStart) {
                            if (label.isNotEmpty()) {
                                Text(label, color = Palette.muted, fontSize = 9.sp, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
                Canvas(
                    Modifier
                        .width(gridWidth)
                        .height(pitch * 7)
                        .pointerInput(year, pitch) {
                            detectTapGestures { pos ->
                                val p = pitch.toPx()
                                val col = (pos.x / p).toInt().coerceIn(0, weeks - 1)
                                val row = (pos.y / p).toInt().coerceIn(0, 6)
                                val day = gridStart.plusDays(col * 7L + row)
                                currentOnSelect.value(day.takeIf { it.year == year })
                            }
                        }
                ) {
                    val p = pitch.toPx()
                    val s = cell.toPx()
                    val inset = (p - s) / 2
                    val radius = CornerRadius(s * 0.3f)
                    for (col in 0 until weeks) {
                        for (row in 0..6) {
                            val day = gridStart.plusDays(col * 7L + row)
                            if (day.year != year) continue
                            val topLeft = Offset(col * p + inset, row * p + inset)
                            drawRoundRect(currentColorAt.value(day) ?: Palette.empty, topLeft, Size(s, s), radius)
                            if (day == selected) {
                                drawRoundRect(Color.White, topLeft, Size(s, s), radius, style = Stroke(1.5.dp.toPx()))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Legend(metric: Metric) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(metric.label, color = Palette.muted, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Palette.levels.zip(metric.legend).forEach { (color, label) ->
            Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(3.dp))
            Text(label, color = Palette.muted, fontSize = 10.sp)
            Spacer(Modifier.width(6.dp))
        }
    }
}
