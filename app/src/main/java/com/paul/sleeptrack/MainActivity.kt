package com.paul.sleeptrack

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.time.Duration
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Palette.bg, surface = Palette.card)) {
                SleepApp()
            }
        }
    }
}

private sealed interface UiState {
    data object Loading : UiState
    data object NotInstalled : UiState
    data object NeedsPermission : UiState
    data class Ready(
        val nights: Map<LocalDate, Duration>,
        val steps: Map<LocalDate, Long>,
        val missing: Set<String>,
    ) : UiState
    data class Error(val message: String) : UiState
}

@Composable
private fun SleepApp() {
    val context = LocalContext.current
    var year by remember { mutableIntStateOf(LocalDate.now().year) }
    var metric by remember { mutableStateOf(Metric.SLEEP) }
    var demo by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<UiState>(UiState.Loading) }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { refreshKey++ }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshKey++ }

    LaunchedEffect(year, demo, refreshKey) {
        if (demo) {
            state = UiState.Ready(demoData(year), demoSteps(year), emptySet())
            return@LaunchedEffect
        }
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            state = UiState.NotInstalled
            return@LaunchedEffect
        }
        val client = HealthConnectClient.getOrCreate(context)
        state = try {
            val granted = client.permissionController.getGrantedPermissions()
            if (PERMISSION_READ_SLEEP !in granted && PERMISSION_READ_STEPS !in granted) {
                UiState.NeedsPermission
            } else {
                UiState.Ready(
                    nights = if (PERMISSION_READ_SLEEP in granted) readSleepByNight(client, year) else emptyMap(),
                    steps = if (PERMISSION_READ_STEPS in granted) readStepsByDay(client, year) else emptyMap(),
                    missing = REQUESTED_PERMISSIONS - granted,
                )
            }
        } catch (e: Exception) {
            UiState.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        when (val s = state) {
            UiState.Loading -> Box(Modifier.fillMaxWidth().padding(top = 120.dp), Alignment.Center) {
                CircularProgressIndicator(color = Palette.levels.last())
            }
            UiState.NotInstalled -> Message(
                title = "Health Connect n'est pas disponible",
                body = "Installe ou mets à jour Health Connect depuis le Play Store, puis reviens ici.",
                action = "Ouvrir le Play Store",
                onAction = {
                    val uri = Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"))
                    }
                },
                onDemo = { demo = true },
            )
            UiState.NeedsPermission -> Message(
                title = "Accès au sommeil et aux pas",
                body = "Sommeil lit uniquement tes sessions de sommeil et ton nombre de pas dans Health Connect pour dessiner tes grilles. Rien ne quitte ton téléphone.",
                action = "Autoriser l'accès",
                onAction = { permissionLauncher.launch(REQUESTED_PERMISSIONS) },
                onDemo = { demo = true },
            )
            is UiState.Error -> Message(
                title = "Erreur de lecture",
                body = s.message,
                action = "Réessayer",
                onAction = { refreshKey++ },
                onDemo = { demo = true },
            )
            is UiState.Ready -> MainScreen(
                year = year,
                metric = metric,
                data = s,
                demo = demo,
                onYear = { year = it },
                onMetric = { metric = it },
                onRequestPermissions = { permissionLauncher.launch(REQUESTED_PERMISSIONS) },
                onExitDemo = { demo = false },
            )
        }
    }
}

@Composable
private fun MainScreen(
    year: Int,
    metric: Metric,
    data: UiState.Ready,
    demo: Boolean,
    onYear: (Int) -> Unit,
    onMetric: (Metric) -> Unit,
    onRequestPermissions: () -> Unit,
    onExitDemo: () -> Unit,
) {
    var selected by remember(year) { mutableStateOf<LocalDate?>(null) }
    val currentYear = LocalDate.now().year
    val count = if (metric == Metric.SLEEP) data.nights.size else data.steps.size

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (demo) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mode démo (données fictives)", color = Palette.levels[2], fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onExitDemo) { Text("Quitter", color = Palette.text) }
            }
        }

        MetricSwitch(metric, onMetric)

        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArrowButton("‹", enabled = true) { onYear(year - 1) }
                    Text("$year", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                    ArrowButton("›", enabled = year < currentYear) { onYear(year + 1) }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (metric == Metric.SLEEP) "$count nuits" else "$count jours",
                        fontSize = 18.sp,
                        color = Palette.muted,
                    )
                }
                YearHeatmap(
                    year = year,
                    selected = selected,
                    onSelect = { selected = it },
                    colorAt = { day ->
                        when (metric) {
                            Metric.SLEEP -> data.nights[day]?.let(::colorFor)
                            Metric.STEPS -> data.steps[day]?.let(::colorForSteps)
                        }
                    },
                )
                Legend(metric)
            }
        }

        selected?.let { day -> DayDetail(day, data.nights[day], data.steps[day]) }

        Stats(metric, data)

        if (data.missing.isNotEmpty() && !demo) {
            Panel {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(missingText(data.missing), color = Palette.muted, fontSize = 13.sp)
                    OutlinedButton(onClick = onRequestPermissions) {
                        Text("Compléter les autorisations", color = Palette.text)
                    }
                }
            }
        }
    }
}

private fun missingText(missing: Set<String>): String {
    val parts = buildList {
        if (PERMISSION_READ_SLEEP in missing) add("le sommeil")
        if (PERMISSION_READ_STEPS in missing) add("les pas")
    }
    val history = PERMISSION_READ_HISTORY in missing
    return when {
        parts.isEmpty() && history ->
            "Sans l'autorisation « historique », Health Connect ne donne que les 30 jours précédant la première autorisation."
        history -> "Health Connect ne partage pas encore ${parts.joinToString(" et ")}, ni l'historique au-delà de 30 jours."
        else -> "Health Connect ne partage pas encore ${parts.joinToString(" et ")}."
    }
}

@Composable
private fun MetricSwitch(metric: Metric, onMetric: (Metric) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.card, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Metric.entries.forEach { entry ->
            val active = entry == metric
            Box(
                Modifier
                    .weight(1f)
                    .background(
                        if (active) Palette.empty else Color.Transparent,
                        RoundedCornerShape(11.dp),
                    )
                    .clickable { onMetric(entry) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    entry.label,
                    color = if (active) Palette.text else Palette.muted,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun ArrowButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.width(36.dp),
    ) {
        Text(symbol, fontSize = 30.sp, color = if (enabled) Palette.muted else Palette.card)
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Palette.card, RoundedCornerShape(20.dp))
    ) { content() }
}

@Composable
private fun DayDetail(day: LocalDate, duration: Duration?, steps: Long?) {
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                day.format(Palette.longDate).replaceFirstChar { it.uppercase() },
                color = Palette.text,
                fontWeight = FontWeight.SemiBold,
            )
            DetailLine(
                "Sommeil",
                duration?.let(::formatDuration) ?: "Aucune donnée",
                duration?.let(::colorFor),
            )
            DetailLine(
                "Pas",
                steps?.let { "${formatSteps(it)} pas" } ?: "Aucune donnée",
                steps?.let(::colorForSteps),
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String, color: Color?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).background(color ?: Palette.empty, RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(10.dp))
        Text(label, color = Palette.muted, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Text(value, color = color ?: Palette.muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun Stats(metric: Metric, data: UiState.Ready) {
    when (metric) {
        Metric.SLEEP -> {
            val nights = data.nights
            if (nights.isEmpty()) {
                EmptyNote("Aucune nuit enregistrée pour cette année.")
                return
            }
            val avg = averageDuration(nights.values)
            val last7 = nights.filterKeys { it > LocalDate.now().minusDays(7) }.values
            val avg7 = if (last7.isEmpty()) null else averageDuration(last7)
            val best = nights.values.max()
            val shortNights = nights.values.count { it < Duration.ofHours(6) }

            StatRow(
                "Moyenne" to (formatDuration(avg) to colorFor(avg)),
                "7 derniers jours" to ((avg7?.let(::formatDuration) ?: "—") to (avg7?.let(::colorFor) ?: Palette.muted)),
            )
            StatRow(
                "Record" to (formatDuration(best) to colorFor(best)),
                "Nuits < 6h" to ("$shortNights" to if (shortNights > 0) Palette.levels[0] else Palette.levels.last()),
            )
        }
        Metric.STEPS -> {
            val steps = data.steps
            if (steps.isEmpty()) {
                EmptyNote("Aucun pas enregistré pour cette année.")
                return
            }
            val avg = steps.values.sum() / steps.size
            val last7 = steps.filterKeys { it > LocalDate.now().minusDays(7) }.values
            val avg7 = if (last7.isEmpty()) null else last7.sum() / last7.size
            val best = steps.values.max()
            val goalDays = steps.values.count { it >= 10_000 }

            StatRow(
                "Moyenne" to (formatSteps(avg) to colorForSteps(avg)),
                "7 derniers jours" to ((avg7?.let(::formatSteps) ?: "—") to (avg7?.let(::colorForSteps) ?: Palette.muted)),
            )
            StatRow(
                "Record" to (formatSteps(best) to colorForSteps(best)),
                "Jours ≥ 10k" to ("$goalDays" to if (goalDays > 0) Palette.levels.last() else Palette.levels[0]),
            )
        }
    }
}

@Composable
private fun StatRow(
    left: Pair<String, Pair<String, Color>>,
    right: Pair<String, Pair<String, Color>>,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(left.first, left.second.first, left.second.second, Modifier.weight(1f))
        StatTile(right.first, right.second.first, right.second.second, Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, accent: Color, modifier: Modifier) {
    Box(modifier.background(Palette.card, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Column {
            Text(label, color = Palette.muted, fontSize = 12.sp)
            Text(value, color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, color = Palette.muted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
}

@Composable
private fun Message(title: String, body: String, action: String, onAction: () -> Unit, onDemo: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(body, color = Palette.muted, fontSize = 15.sp, textAlign = TextAlign.Center)
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.levels.last(), contentColor = Palette.bg),
        ) { Text(action) }
        TextButton(onClick = onDemo) { Text("Voir une démo", color = Palette.muted) }
    }
}

private fun averageDuration(values: Collection<Duration>): Duration =
    Duration.ofSeconds(values.sumOf { it.seconds } / values.size)

fun formatDuration(d: Duration): String = "%dh%02d".format(d.toHours(), d.toMinutesPart())

fun formatSteps(steps: Long): String = "%,d".format(steps).replace(',', ' ')
