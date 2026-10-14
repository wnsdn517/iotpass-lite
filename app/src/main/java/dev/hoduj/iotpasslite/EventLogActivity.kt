package dev.hoduj.iotpasslite

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 기록: every retained door open and failure, folded by hour of day, with the door list and recent events. */
// Data colors for the bars and status dots. Layout and text use Miuix tokens.
private val COLOR_OPEN = Color(0xFFFF9F0A)
private val COLOR_SERVER = Color(0xFFBF5AF2)
private val COLOR_ERROR = Color(0xFFFF453A)

/** A failed door open: the Bluetooth advertise did not start, or the server open was refused or unreachable.
 *  Login and app-setup failures are not door opens, so they are not counted here. */
fun isFailure(e: EventLog.Entry): Boolean = e.type == EventLog.Type.ADVERTISE_FAIL

/** A door open that went through the server (cmd 4) rather than a local Bluetooth advertisement. */
fun isServerOpen(e: EventLog.Entry): Boolean = e.detail?.startsWith("remote") == true

private fun hourOf(millis: Long): Int = Calendar.getInstance().run {
    timeInMillis = millis
    get(Calendar.HOUR_OF_DAY)
}

/** One column of the chart: opens split by route, and failures, for one hour of the day. */
data class Bin(
    val label: String,
    val bluetoothOpens: Int,
    val serverOpens: Int,
    val fails: Int,
)

/** Everything the screen shows, read from the log off the main thread. */
data class LogData(
    val lastSeen: Map<String, Long>,
    val timeline: List<EventLog.Entry>,
    val all: List<EventLog.Entry>,
) {
    companion object {
        val EMPTY = LogData(emptyMap(), emptyList(), emptyList())

        fun load(context: Context): LogData {
            val since = System.currentTimeMillis() - 7 * 24L * 60 * 60 * 1000
            val lastSeen = EventLog.presence(context, since)
                .groupBy { it.doorMac.uppercase() }
                .mapValues { (_, spans) -> spans.maxOf { it.endMillis } }
            val all = EventLog.events(context)
            // Successful server logins are not listed: only door opens and failures.
            val timeline = all.filter { it.type == EventLog.Type.DOOR_OPEN || isFailure(it) }.take(60)
            return LogData(lastSeen, timeline, all)
        }
    }

    /** Hour-of-day columns over the whole retained period. */
    fun bins(): List<Bin> {
        val opens = all.filter { it.type == EventLog.Type.DOOR_OPEN }
        val fails = all.filter(::isFailure)
        return (0 until 24).map { h ->
            val opensHere = opens.filter { hourOf(it.timestampMillis) == h }
            Bin(
                label = if (h % 6 == 0) "${h}시" else "",
                bluetoothOpens = opensHere.count { !isServerOpen(it) },
                serverOpens = opensHere.count { isServerOpen(it) },
                fails = fails.count { hourOf(it.timestampMillis) == h },
            )
        }
    }
}

/** One row of the recent-events list. */
private data class TimelineItem(val time: Long, val color: Color, val title: String, val detail: String?)

private fun dateTimeText(millis: Long): String = SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(millis)

@Composable
internal fun LogTab() {
    val context = LocalContext.current
    // Reading the log happens off the main thread; the screen fills in when it is ready.
    val data by produceState(initialValue = LogData.EMPTY) {
        value = withContext(Dispatchers.IO) { LogData.load(context) }
    }
    val doors = remember { Prefs.getDoors(context) }
    val bins = remember(data) { data.bins() }
    val opens = data.all.filter { it.type == EventLog.Type.DOOR_OPEN }
    val fails = data.all.filter(::isFailure)

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        CategoryStat("블루투스", "${opens.count { !isServerOpen(it) }}회", COLOR_OPEN)
                        CategoryStat("서버", "${opens.count { isServerOpen(it) }}회", COLOR_SERVER)
                        CategoryStat("실패", "${fails.size}회", COLOR_ERROR)
                    }
                    EventBars(bins = bins, modifier = Modifier.fillMaxWidth().height(120.dp))
                }
            }

            SectionLabel("문")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    if (doors.isEmpty()) {
                        Text(
                            text = "등록된 문이 없습니다",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                    doors.forEachIndexed { index, door ->
                        if (index > 0) HorizontalDivider()
                        DoorRow(
                            name = door.name.ifEmpty { door.mac },
                            nearby = DoorPresence.isNearby(door.mac),
                            lastSeen = data.lastSeen[door.mac.uppercase()],
                        )
                    }
                }
            }

            SectionLabel("최근 이벤트")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    val doorNames = doors.associate { it.mac to it.name }
                    val items = data.timeline.mapNotNull { timelineItem(it, doorNames) }
                    if (items.isEmpty()) {
                        Text(
                            text = "기록 없음",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                    items.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider()
                        TimelineRow(item)
                    }
                }
            }

            RawLinkCard()
        }
    }
}

/** Hour-of-day bars on a light grid. Dashed lines mark half and full scale, with the scale on the left. Hairlines
 *  split the day into 6-hour blocks. Each column stacks Bluetooth opens (orange), server opens (purple) and
 *  failures (red) from the bottom. */
@Composable
private fun EventBars(bins: List<Bin>, modifier: Modifier) {
    val peak = bins.maxOfOrNull { it.bluetoothOpens + it.serverOpens + it.fails }?.coerceAtLeast(1) ?: 1
    val gridColor = MiuixTheme.colorScheme.dividerLine
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(
                modifier = Modifier.width(28.dp).fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                Text(text = "$peak", color = muted, style = MiuixTheme.textStyles.footnote2)
                Text(text = "${peak / 2}", color = muted, style = MiuixTheme.textStyles.footnote2)
                Text(text = "0", color = muted, style = MiuixTheme.textStyles.footnote2)
            }
            Canvas(modifier = Modifier.padding(start = 6.dp, end = 16.dp).fillMaxSize()) {
                val w = size.width
                val h = size.height
                val slot = w / bins.size.coerceAtLeast(1)
                val barW = slot * 0.6f
                val radius = CornerRadius(barW / 2)
                val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
                val line = 1.dp.toPx()

                // Scale lines: top, half, and the baseline.
                listOf(0f, 0.5f, 1f).forEach { f ->
                    val y = h * (1f - f)
                    drawLine(
                        gridColor,
                        Offset(0f, y),
                        Offset(w, y),
                        strokeWidth = line,
                        pathEffect = if (f == 0f) null else dash,
                    )
                }
                // 6-hour block dividers.
                listOf(6, 12, 18).forEach { hour ->
                    val x = hour * slot
                    drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = line)
                }

                bins.forEachIndexed { i, bin ->
                    val left = i * slot + (slot - barW) / 2
                    var bottom = h
                    listOf(
                        bin.bluetoothOpens to COLOR_OPEN,
                        bin.serverOpens to COLOR_SERVER,
                        bin.fails to COLOR_ERROR,
                    ).forEach { (count, color) ->
                        if (count > 0) {
                            val segH = h * count / peak
                            drawRoundRect(
                                color,
                                topLeft = Offset(left, bottom - segH),
                                size = Size(barW, segH),
                                cornerRadius = radius,
                            )
                            bottom -= segH
                        }
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(start = 34.dp, end = 16.dp)) {
            bins.forEach { bin ->
                // Each label starts at its slot's left edge, the same spot as the 6-hour divider line.
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    Text(
                        text = bin.label,
                        color = muted,
                        style = MiuixTheme.textStyles.footnote2,
                        softWrap = false,
                        modifier = Modifier.padding(start = 3.dp).wrapContentWidth(Alignment.Start, unbounded = true),
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryStat(title: String, value: String, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title, color = color, style = MiuixTheme.textStyles.footnote1)
        Text(text = value, style = MiuixTheme.textStyles.body1)
    }
}

@Composable
private fun SectionLabel(text: String) {
    SectionHeader(text = text)
}

@Composable
private fun DoorRow(name: String, nearby: Boolean, lastSeen: Long?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_bluetooth),
            contentDescription = null,
            colorFilter = ColorFilter.tint(if (nearby) COLOR_OPEN else MiuixTheme.colorScheme.onSurfaceVariantSummary),
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = name, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1)
        Text(
            text = when {
                nearby -> "지금 감지됨"
                lastSeen != null -> "마지막 ${dateTimeText(lastSeen)}"
                else -> "감지 기록 없음"
            },
            color = if (nearby) COLOR_OPEN else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote1,
        )
    }
}

private fun timelineItem(e: EventLog.Entry, doorNames: Map<String, String>): TimelineItem? {
    fun doorLabel(mac: String?): String = mac?.let { doorNames[it]?.ifEmpty { null } ?: it } ?: "-"
    return when {
        e.type == EventLog.Type.DOOR_OPEN -> TimelineItem(
            time = e.timestampMillis,
            color = if (isServerOpen(e)) COLOR_SERVER else COLOR_OPEN,
            title = "문 열림 · ${doorLabel(e.doorMac)}",
            detail = if (isServerOpen(e)) "서버" else "블루투스",
        )
        isFailure(e) -> TimelineItem(
            time = e.timestampMillis,
            color = COLOR_ERROR,
            title = "문 열기 실패 · ${doorLabel(e.doorMac)}",
            detail = e.detail,
        )
        else -> null
    }
}

@Composable
private fun TimelineRow(item: TimelineItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .background(item.color, CircleShape),
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = item.title, style = MiuixTheme.textStyles.body2)
            item.detail?.let {
                Text(
                    text = it,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                )
            }
        }
        Text(
            text = dateTimeText(item.time),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote2,
        )
    }
}

@Composable
private fun RawLinkCard() {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { context.startActivity(Intent(context, RawLogActivity::class.java)) },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = "RAW 기록", style = MiuixTheme.textStyles.body2)
                Text(
                    text = "앱 상태, BLE 송수신, 서버 요청 원문",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                )
            }
            Text(
                text = "›",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.title4,
            )
        }
    }
}
