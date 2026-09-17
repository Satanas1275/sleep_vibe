package com.paul.sleeptrack

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
        // Un arrêt forcé annule les alarmes : on les repose à chaque lancement.
        Reminders.reschedule(this)
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
    data class Ready(val data: HealthData, val missing: Set<String>) : UiState
    data class Error(val message: String) : UiState
}

@Composable
private fun SleepApp() {
    val context = LocalContext.current
    var year by remember { mutableIntStateOf(LocalDate.now().year) }
    var metric by remember { mutableStateOf(Prefs.widgetMetric(context)) }
    var demo by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<UiState>(UiState.Loading) }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { refreshKey++ }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshKey++ }

    LaunchedEffect(year, demo, refreshKey) {
        if (demo) {
            state = UiState.Ready(demoHealthData(year), emptySet())
            return@LaunchedEffect
        }
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            state = UiState.NotInstalled
            return@LaunchedEffect
        }
        val client = HealthConnectClient.getOrCreate(context)
        state = try {
            val granted = client.permissionController.getGrantedPermissions()
            if (DATA_PERMISSIONS.values.none { it in granted }) {
                UiState.NeedsPermission
            } else {
                val data = loadYear(client, granted, year)
                // Le widget et les rappels lisent ce cache : on le rafraîchit à chaque
                // passage sur l'année en cours.
                if (year == LocalDate.now().year) {
                    DataCache.save(context, data)
                    updateAllWidgets(context)
                }
                UiState.Ready(data, REQUESTED_PERMISSIONS - granted)
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
        when {
            settings -> SettingsScreen(
                data = (state as? UiState.Ready)?.data ?: DataCache.load(context),
                onBack = { settings = false },
            )
            else -> when (val s = state) {
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
                    title = "Accès à tes données de santé",
                    body = "Sommeil lit tes nuits, tes pas, ton cœur au repos et ton poids dans Health " +
                        "Connect pour dessiner tes grilles. Rien ne quitte ton téléphone.",
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
                    data = s.data,
                    missing = s.missing,
                    demo = demo,
                    onYear = { year = it },
                    onMetric = {
                        metric = it
                        Prefs.setWidgetMetric(context, it)
                        updateAllWidgets(context)
                    },
                    onRequestPermissions = { permissionLauncher.launch(REQUESTED_PERMISSIONS) },
                    onExitDemo = { demo = false },
                    onSettings = { settings = true },
                )
            }
        }
    }
}

@Composable
private fun MainScreen(
    year: Int,
    metric: Metric,
    data: HealthData,
    missing: Set<String>,
    demo: Boolean,
    onYear: (Int) -> Unit,
    onMetric: (Metric) -> Unit,
    onRequestPermissions: () -> Unit,
    onExitDemo: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    var selected by remember(year) { mutableStateOf<LocalDate?>(null) }
    val currentYear = LocalDate.now().year
    val scale = remember(metric, data) { scaleFor(metric, data) }
    val series = remember(metric, data) { data.series(metric) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sommeil", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { shareYearImage(context, year, metric, data) }) {
                Text("Partager", color = Palette.muted, fontSize = 14.sp)
            }
            TextButton(onClick = onSettings) {
                Text("Réglages", color = Palette.muted, fontSize = 14.sp)
            }
        }

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
                    Text(metric.countLabel(series.size), fontSize = 18.sp, color = Palette.muted)
                }
                YearHeatmap(
                    year = year,
                    selected = selected,
                    onSelect = { selected = it },
                    colorAt = { day -> series[day]?.let(scale::colorOf) },
                )
                Legend(metric, scale)
            }
        }

        selected?.let { day -> DayDetail(day, data) }

        if (series.isEmpty()) {
            EmptyNote("Aucune donnée « ${metric.label.lowercase()} » pour cette année.")
        } else {
            val tiles = statTiles(metric, data, scale)
            StatRow(tiles[0], tiles[1])
            StatRow(tiles[2], tiles[3])
        }

        if (data.steps.isNotEmpty() && data.nights.isNotEmpty()) {
            Panel { CorrelationPanel(data) }
        }

        if (missing.isNotEmpty() && !demo) {
            Panel {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(missingText(missing), color = Palette.muted, fontSize = 13.sp)
                    OutlinedButton(onClick = onRequestPermissions) {
                        Text("Compléter les autorisations", color = Palette.text)
                    }
                }
            }
        }
    }
}

private fun missingText(missing: Set<String>): String {
    val parts = DATA_PERMISSIONS.filterValues { it in missing }.keys.map { it.detailLabel.lowercase() }
    val extras = buildList {
        if (PERMISSION_READ_HISTORY in missing) add("l'historique au-delà de 30 jours")
        if (PERMISSION_READ_BACKGROUND in missing) add("la lecture en arrière-plan (widget et rappels)")
    }
    return buildString {
        if (parts.isNotEmpty()) {
            append("Health Connect ne partage pas encore ${parts.joinToString(", ")}.")
        }
        if (extras.isNotEmpty()) {
            if (isNotEmpty()) append(" ")
            append("Il manque aussi ${extras.joinToString(" et ")}.")
        }
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
                    fontSize = 14.sp,
                    maxLines = 1,
                    softWrap = false,
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
private fun DayDetail(day: LocalDate, data: HealthData) {
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                day.format(Palette.longDate).replaceFirstChar { it.uppercase() },
                color = Palette.text,
                fontWeight = FontWeight.SemiBold,
            )
            Metric.entries.forEach { entry ->
                val value = data.series(entry)[day]
                DetailLine(
                    entry.detailLabel,
                    value?.let(entry::format) ?: "Aucune donnée",
                    value?.let { scaleFor(entry, data).colorOf(it) },
                )
            }
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
private fun StatRow(left: StatTile, right: StatTile) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTileView(left, Modifier.weight(1f))
        StatTileView(right, Modifier.weight(1f))
    }
}

@Composable
private fun StatTileView(tile: StatTile, modifier: Modifier) {
    Box(modifier.background(Palette.card, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Column {
            Text(tile.label, color = Palette.muted, fontSize = 12.sp)
            Text(tile.value, color = tile.color, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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

// ---------------------------------------------------------------- Réglages

/** Marqueur : la demande d'autorisation en cours vient du bouton d'exemple. */
private const val SAMPLE = "sample"

@Composable
private fun SettingsScreen(data: HealthData, onBack: () -> Unit) {
    val context = LocalContext.current
    var evening by remember { mutableStateOf(Prefs.eveningEnabled(context)) }
    var eveningHour by remember { mutableIntStateOf(Prefs.eveningHour(context)) }
    var weekly by remember { mutableStateOf(Prefs.weeklyEnabled(context)) }
    var weeklyHour by remember { mutableIntStateOf(Prefs.weeklyHour(context)) }
    var goal by remember { mutableIntStateOf(Prefs.goalMinutes(context)) }
    var pendingSwitch by remember { mutableStateOf<String?>(null) }

    fun persist() {
        Prefs.of(context).edit()
            .putBoolean(Prefs.EVENING_ENABLED, evening)
            .putInt(Prefs.EVENING_HOUR, eveningHour)
            .putBoolean(Prefs.WEEKLY_ENABLED, weekly)
            .putInt(Prefs.WEEKLY_HOUR, weeklyHour)
            .putInt(Prefs.GOAL_MINUTES, goal)
            .apply()
        Reminders.reschedule(context)
    }

    fun sampleWeekly() {
        val message = Reminders.weeklyMessage(data)
            ?: ("Ta semaine" to "Pas encore assez de nuits enregistrées pour un résumé.")
        Reminders.notify(
            context, Reminders.NOTIFICATION_SAMPLE, Reminders.CHANNEL_WEEKLY,
            "Résumé hebdomadaire", message.first, message.second,
        )
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            when (pendingSwitch) {
                Prefs.EVENING_ENABLED -> evening = true
                Prefs.WEEKLY_ENABLED -> weekly = true
                SAMPLE -> sampleWeekly()
            }
            persist()
        }
        pendingSwitch = null
    }

    // Activer un rappel sans pouvoir notifier ne servirait à rien : on demande d'abord.
    fun enable(key: String, turnOn: Boolean, apply: (Boolean) -> Unit) {
        if (turnOn && !Reminders.canNotify(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pendingSwitch = key
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        apply(turnOn)
        persist()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Réglages", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Retour", color = Palette.muted) }
        }

        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingSwitch(
                    title = "Rappel du soir",
                    subtitle = "Si la moyenne des 7 derniers jours passe sous l'objectif",
                    checked = evening,
                    onChange = { enable(Prefs.EVENING_ENABLED, it) { v -> evening = v } },
                )
                if (evening) {
                    Stepper("Heure", "%02dh00".format(eveningHour)) { step ->
                        eveningHour = (eveningHour + step + 24) % 24
                        persist()
                    }
                }
                Stepper("Objectif de sommeil", formatDuration(Duration.ofMinutes(goal.toLong()))) { step ->
                    goal = (goal + step * 15).coerceIn(300, 600)
                    persist()
                }
            }
        }

        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingSwitch(
                    title = "Résumé du dimanche",
                    subtitle = "Moyenne de la semaine, comparée à la précédente",
                    checked = weekly,
                    onChange = { enable(Prefs.WEEKLY_ENABLED, it) { v -> weekly = v } },
                )
                if (weekly) {
                    Stepper("Heure", "%02dh00".format(weeklyHour)) { step ->
                        weeklyHour = (weeklyHour + step + 24) % 24
                        persist()
                    }
                }
                OutlinedButton(onClick = {
                    if (Reminders.canNotify(context)) {
                        sampleWeekly()
                    } else {
                        pendingSwitch = SAMPLE
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }) {
                    Text("Voir un exemple de résumé", color = Palette.text)
                }
            }
        }

        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Widget", color = Palette.text, fontWeight = FontWeight.SemiBold)
                Text(
                    "Ajoute « Sommeil » depuis l'écran des widgets. Il affiche les dernières " +
                        "semaines de la métrique sélectionnée dans l'app — actuellement " +
                        "« ${Prefs.widgetMetric(context).label.lowercase()} ».",
                    color = Palette.muted,
                    fontSize = 13.sp,
                )
                OutlinedButton(onClick = { updateAllWidgets(context) }) {
                    Text("Rafraîchir le widget", color = Palette.text)
                }
            }
        }

        Text(
            "Les rappels sont calculés sur le téléphone, à partir des données déjà lues. " +
                "Aucune donnée n'est envoyée nulle part.",
            color = Palette.muted.copy(alpha = 0.7f),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.text, fontSize = 15.sp)
            Text(subtitle, color = Palette.muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.bg,
                checkedTrackColor = Palette.levels.last(),
            ),
        )
    }
}

@Composable
private fun Stepper(label: String, value: String, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        ArrowButton("‹", enabled = true) { onStep(-1) }
        Text(value, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        ArrowButton("›", enabled = true) { onStep(1) }
    }
}
