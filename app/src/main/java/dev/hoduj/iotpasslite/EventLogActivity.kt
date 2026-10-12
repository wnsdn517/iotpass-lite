package dev.hoduj.iotpasslite

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 기록: last 7 days as one bar per day (state, reader presence, door-open moments), tap a day for details,
 *  and the server communication log below. */
class EventLogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                LogScreen()
            }
        }
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

private val COLOR_RUNNING = Color(0xFF30D158)
private val COLOR_OFF = Color(0xFF636366)
private val COLOR_UNKNOWN = Color(0xFF2C2C2E)
private val COLOR_PRESENCE = Color(0xFF0A84FF)
private val COLOR_OPEN = Color(0xFFFF9F0A)
private val COLOR_MUTED = Color(0x99FFFFFF)

enum class BarState { RUNNING, OFF, UNKNOWN }

/** A piece of one day's bar. */
data class Segment(val start: Long, val end: Long, val state: BarState)

/** One day: its state segments, the reader presence spans and door-open events inside it. */
data class DayRow(
    val label: String,
    val dayStart: Long,
    val dayEnd: Long,
    val segments: List<Segment>,
    val presence: List<EventLog.PresenceSpan>,
    val opens: List<EventLog.Entry>,
)

object History {

    fun startOfDay(millis: Long): Long = Calendar.getInstance().run {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    /** Watcher state spans from the event log, oldest first. Before the first event nothing is known. */
    fun watcherSpans(events: List<EventLog.Entry>, now: Long): List<Pair<Long, Long>> =
        Uptime.spans(events, now).map { span -> span.startMillis to (span.endMillis ?: now) }

    fun days(context: Context, now: Long = System.currentTimeMillis(), count: Int = 7): List<DayRow> {
        val today = startOfDay(now)
        val since = today - (count - 1) * DAY_MS
        val oldestFirst = EventLog.events(context).asReversed()
        val firstEvent = oldestFirst.firstOrNull()?.timestampMillis ?: now
        val spans = Uptime.spans(oldestFirst, now)
        val presence = EventLog.presence(context, since)
        val opens = oldestFirst.filter { it.type == EventLog.Type.DOOR_OPEN }
        val dayLabel = SimpleDateFormat("M/d (E)", Locale.KOREA)

        return (0 until count).map { back ->
            val dayStart = today - back * DAY_MS
            val dayEnd = minOf(dayStart + DAY_MS, now)
            val segments = ArrayList<Segment>()
            if (firstEvent > dayStart) {
                segments += Segment(dayStart, minOf(firstEvent, dayEnd), BarState.UNKNOWN)
            }
            spans.forEach { span ->
                val s = maxOf(span.startMillis, dayStart)
                val e = minOf(span.endMillis ?: now, dayEnd)
                if (e > s) {
                    segments += Segment(s, e, if (span.running) BarState.RUNNING else BarState.OFF)
                }
            }
            DayRow(
                label = when (back) {
                    0 -> "오늘"
                    1 -> "어제"
                    else -> dayLabel.format(dayStart)
                },
                dayStart = dayStart,
                dayEnd = dayEnd,
                segments = segments.filter { it.end > it.start },
                presence = presence.filter { it.endMillis >= dayStart && it.startMillis <= dayEnd },
                opens = opens.filter { it.timestampMillis in dayStart until dayEnd },
            )
        }
    }
}

/** Watcher up/down spans from the event log. */
data class Span(val startMillis: Long, val endMillis: Long?, val running: Boolean)

object Uptime {
    fun spans(entriesOldestFirst: List<EventLog.Entry>, now: Long): List<Span> {
        val out = ArrayList<Span>()
        var running = false
        var since = entriesOldestFirst.firstOrNull()?.timestampMillis ?: now
        fun change(at: Long, nowRunning: Boolean) {
            if (nowRunning == running) return
            out += Span(since, at, running)
            since = at
            running = nowRunning
        }
        for (e in entriesOldestFirst) {
            when (e.type) {
                EventLog.Type.SCAN_ON, EventLog.Type.REARMED, EventLog.Type.BLUETOOTH_ON -> change(e.timestampMillis, true)
                EventLog.Type.SCAN_OFF, EventLog.Type.BLUETOOTH_OFF -> change(e.timestampMillis, false)
                else -> Unit
            }
        }
        out += Span(since, null, running)
        return out
    }
}

private fun clockText(millis: Long): String = SimpleDateFormat("HH:mm", Locale.KOREA).format(millis)
private fun clockSecText(millis: Long): String = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(millis)

@Composable
private fun LogScreen() {
    val context = LocalContext.current
    var revision by remember { mutableStateOf(0) }
    val days = remember(revision) { History.days(context) }
    val doorNames = remember(revision) { Prefs.getDoors(context).associate { it.mac to it.name } }
    var expanded by remember { mutableStateOf<Long?>(null) }
    val server = remember(revision) {
        EventLog.events(context).filter {
            it.type == EventLog.Type.SERVER ||
                it.type == EventLog.Type.ADVERTISE_FAIL ||
                it.type == EventLog.Type.REARM_FAILED ||
                (it.detail?.startsWith("remote") == true)
        }.take(100)
    }

    fun doorLabel(mac: String?): String = mac?.let { doorNames[it]?.ifEmpty { null } ?: it } ?: "-"

    Scaffold(topBar = { TopAppBar(title = "기록") }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Legend()

            Text(text = "최근 7일", color = COLOR_MUTED, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    days.forEachIndexed { index, day ->
                        if (index > 0) Divider()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expanded = if (expanded == day.dayStart) null else day.dayStart }
                                .padding(vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = day.label, modifier = Modifier.width(78.dp), fontSize = 13.sp)
                                DayBar(day = day, modifier = Modifier.weight(1f))
                            }
                            if (expanded == day.dayStart) {
                                DayDetail(day = day, doorLabel = ::doorLabel)
                            }
                        }
                    }
                }
            }

            Text(text = "서버 통신 · 오류", color = COLOR_MUTED, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (server.isEmpty()) {
                        Text(text = "기록 없음", color = COLOR_MUTED, modifier = Modifier.padding(vertical = 12.dp))
                    }
                    server.forEachIndexed { index, e ->
                        if (index > 0) Divider()
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            Text(
                                text = clockSecText(e.timestampMillis),
                                color = COLOR_MUTED,
                                fontSize = 12.sp,
                                modifier = Modifier.width(72.dp),
                            )
                            Text(text = e.detail ?: e.type.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun Legend() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LegendItem(COLOR_RUNNING, "작동 중")
        LegendItem(COLOR_OFF, "꺼짐")
        LegendItem(COLOR_UNKNOWN, "정보 없음")
        LegendItem(COLOR_PRESENCE, "감지")
        LegendItem(COLOR_OPEN, "열림")
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
        Text(text = " $label", color = COLOR_MUTED, fontSize = 11.sp)
    }
}

/** One day as a bar: state segments on top, reader presence along the bottom, door-open ticks on top. */
@Composable
private fun DayBar(day: DayRow, modifier: Modifier) {
    val length = (day.dayEnd - day.dayStart).coerceAtLeast(1L).toFloat()
    Canvas(
        modifier = modifier
            .height(18.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(COLOR_UNKNOWN),
    ) {
        val h = size.height
        fun x(millis: Long): Float = ((millis - day.dayStart) / length) * size.width

        day.segments.forEach { seg ->
            val color = when (seg.state) {
                BarState.RUNNING -> COLOR_RUNNING
                BarState.OFF -> COLOR_OFF
                BarState.UNKNOWN -> COLOR_UNKNOWN
            }
            val left = x(seg.start)
            drawRect(color, Offset(left, 0f), Size(x(seg.end) - left, h))
        }
        day.presence.forEach { span ->
            val left = x(maxOf(span.startMillis, day.dayStart))
            val right = x(minOf(span.endMillis, day.dayEnd))
            drawRect(COLOR_PRESENCE, Offset(left, h - 4.dp.toPx()), Size(maxOf(right - left, 2f), 4.dp.toPx()))
        }
        day.opens.forEach { e ->
            drawRect(COLOR_OPEN, Offset(x(e.timestampMillis), 0f), Size(2.dp.toPx(), h))
        }
    }
}

@Composable
private fun DayDetail(day: DayRow, doorLabel: (String?) -> String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 78.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (day.presence.isEmpty() && day.opens.isEmpty()) {
            Text(text = "감지 · 열림 기록 없음", color = COLOR_MUTED, fontSize = 12.sp)
        }
        day.presence.forEach { span ->
            Text(
                text = "감지  ${clockText(span.startMillis)} ~ ${clockText(span.endMillis)}  ${doorLabel(span.doorMac)}",
                fontSize = 12.sp,
            )
        }
        day.opens.forEach { e ->
            val how = if (e.detail?.startsWith("remote") == true) "원격" else "블루투스"
            Text(
                text = "열림  ${clockSecText(e.timestampMillis)}  ${doorLabel(e.doorMac)}  $how",
                color = COLOR_OPEN,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun Divider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0x22FFFFFF)))
}
