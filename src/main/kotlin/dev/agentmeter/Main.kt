package dev.agentmeter

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import dev.agentmeter.importers.Importers
import dev.agentmeter.ui.theme.AgentMeterTheme
import dev.agentmeter.ui.DashboardScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.logging.FileHandler
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.random.Random

private const val SCAN_INTERVAL_MS = 5_000L

fun main(args: Array<String>) {
    val shot = args.indexOf("--screenshot")
    if (shot >= 0) return renderScreenshot(Path.of(args.getOrElse(shot + 1) { "docs/screenshot.png" }))

    val home = Path.of(System.getProperty("user.home"))
    val dataDir = System.getenv("AGENT_METER_HOME")?.let { Path.of(it) } ?: home.resolve(".agent-meter")
    dataDir.createDirectories()
    Logger.getLogger("").addHandler(
        FileHandler(dataDir.resolve("agent-meter.log").toString(), 1_000_000, 1, true).apply { formatter = SimpleFormatter() },
    )
    val store = Store(dataDir.resolve("agent-meter.db"))
    val scanner = Scanner(store, Importers.all, home)
    val pricesFile = dataDir.resolve("prices.json")
    val settingsFile = dataDir.resolve("settings.json")

    val initialSettings = Settings.load(settingsFile)
    val log = Logger.getLogger("main")
    if (!isTraySupported) log.info("System tray not supported on this desktop; running as a normal window")

    application {
        var rows by remember { mutableStateOf(emptyList<UsageRow>()) }
        var prices by remember { mutableStateOf(PriceTable()) }
        var settings by remember { mutableStateOf(initialSettings) }
        var rateLimits by remember { mutableStateOf(emptyList<RateLimitSnapshot>()) }
        var range by remember { mutableStateOf(Range.MONTH) }
        var status by remember { mutableStateOf("Scanning…") }
        var loaded by remember { mutableStateOf(false) }
        val policy = windowPolicy(settings, isTraySupported)
        var windowVisible by remember { mutableStateOf(windowPolicy(initialSettings, isTraySupported).startVisible) }

        LaunchedEffect(Unit) {
            while (true) {
                withContext(Dispatchers.IO) {
                    val r = scanner.scanOnce()
                    prices = PriceTable.load(pricesFile)
                    settings = Settings.load(settingsFile)
                    rows = store.rows(0, prices.timeDependentModels(store.distinctModels()))
                    rateLimits = store.latestRateLimits()
                    val errs = if (r.errors.isEmpty()) "" else " · ${r.errors.size} parse errors (see agent-meter.log)"
                    status = "Updated ${LocalTime.now().withNano(0)} · ${r.events} new/updated events$errs · data in ${tildePath(dataDir, home)}"
                    loaded = true
                }
                delay(SCAN_INTERVAL_MS)
            }
        }

        val dash = remember(rows, prices, range, rateLimits, settings) {
            buildDashboard(rows, prices, LocalDate.now(), range, rateLimits = rateLimits, settings = settings)
        }

        if (isTraySupported) {
            val trayState = rememberTrayState()
            val tracker = remember { NotificationTracker() }
            val level = trayLevel(dash.limitWarnings)
            val icon = remember(level) { BitmapPainter(trayIconImage(level).toComposeImageBitmap()) }
            val lines = trayLines(dash)

            // Only after the first scan, so limits that were already exceeded before launch stay silent.
            LaunchedEffect(dash.limitWarnings, loaded) {
                if (!loaded) return@LaunchedEffect
                for (w in tracker.update(dash.limitWarnings)) {
                    val (title, body) = notificationText(w)
                    trayState.sendNotification(
                        Notification(title, body, if (w.level == LimitWarningLevel.CRITICAL) Notification.Type.Error else Notification.Type.Warning),
                    )
                }
            }

            Tray(
                icon = icon,
                state = trayState,
                tooltip = trayTooltip(lines),
                onAction = { windowVisible = true },
                menu = {
                    lines.forEach { Item(it, enabled = false, onClick = {}) }
                    Separator()
                    Item("Open dashboard", onClick = { windowVisible = true })
                    Item("Quit", onClick = ::exitApplication)
                },
            )
        }

        Window(
            visible = windowVisible,
            onCloseRequest = { if (policy.closeToTray) windowVisible = false else exitApplication() },
            title = "Agent Meter",
            state = rememberWindowState(width = 1200.dp, height = 900.dp),
        ) {
            LaunchedEffect(windowVisible) {
                if (windowVisible) {
                    window.isMinimized = false
                    window.toFront()
                }
            }
            AgentMeterTheme { DashboardScreen(dash, range, { range = it }, status, loading = !loaded) }
        }
    }
}

/** Renders the dashboard with synthetic data to a PNG (used for the README screenshot). */
private fun renderScreenshot(out: Path) {
    Locale.setDefault(Locale.US) // reproducible README image regardless of who renders it
    val today = LocalDate.now()
    val now = System.currentTimeMillis()
    val demoLimits = listOf(
        RateLimitSnapshot("codex", "5h", 32.0, now / 1000L + 14400L, now - 300_000L),
        RateLimitSnapshot("codex", "7d", 28.0, now / 1000L + 86400L * 5, now - 300_000L),
    )
    val dash = buildDashboard(demoRows(today), PriceTable.bundled(), today, Range.MONTH, rateLimits = demoLimits)
    // Dark and light, with animations at their final value. Dark goes to [out], light next to it as <name>-light.png.
    val hoverX = 576f
    val hoverY = 640f
    for ((dark, file) in listOf(true to out, false to out.resolveSibling(out.fileName.toString().replace(".png", "-light.png")))) {
        val scene = ImageComposeScene(1200, 1720, Density(1f)) {
            AgentMeterTheme(dark = dark, motion = false) { DashboardScreen(dash, Range.MONTH, {}, "Demo data · Updated 14:32:05") }
        }
        scene.render()
        if (dark) { // hover the busiest day so the README shows the chart tooltip
            scene.sendPointerEvent(PointerEventType.Move, Offset(hoverX, hoverY))
            scene.render(); scene.render()
        }
        val png = scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
        scene.close()
        file.toAbsolutePath().parent.createDirectories()
        file.writeBytes(png)
        println("Wrote $file")
    }
}

private fun demoRows(today: LocalDate): List<UsageRow> {
    val rnd = Random(11)
    val projects = listOf("/home/dev/webshop", "/home/dev/api-gateway", "/home/dev/mobile-app", "/home/dev/infra")
    val setups = listOf("claude-code" to "claude-opus-5-5", "claude-code" to "claude-sonnet-5-5", "codex" to "gpt-6.1-sol", "codex" to "gpt-6-luna",
        "opencode" to "grok-4.6", "deepseek-harness" to "deepseek-flash", "antigravity" to "gemini-3.8-flash")
    return (0 until 70).flatMap { back -> // two months, so the month-over-month change has something to compare with
        val day = today.minusDays(back.toLong())
        val ts = day.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        (0 until rnd.nextInt(1, 5)).map { s ->
            val (agent, model) = setups[rnd.nextInt(setups.size)].let { if (back == 0 && s == 0) setups[0] else it }
            val scale = if (back == 0) rnd.nextLong(7, 12) else rnd.nextLong(1, 12)
            UsageRow(
                agent, "demo-$back-$s", model, projects[rnd.nextInt(projects.size)], day, ts + s * 3_600_000L, ts + s * 3_600_000L + 1_800_000L,
                Tokens(input = 20_000 * scale, cacheRead = 900_000 * scale, cacheWrite = 60_000 * scale, output = 25_000 * scale),
            )
        }
    }
}
