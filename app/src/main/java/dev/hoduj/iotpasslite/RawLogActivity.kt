package dev.hoduj.iotpasslite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.text.SimpleDateFormat
import java.util.Locale

/** RAW 기록: every app state change, Bluetooth scan/advertise traffic and server exchange, as plain lines.
 *  Filter by category, copy the visible lines, or refresh. Phone numbers are masked before they are written. */
class RawLogActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppTheme.load(this)
        setContent {
            MiuixTheme(controller = ThemeController(AppTheme.mode.value)) {
                RawLogScreen()
            }
        }
    }
}

/** Filter tabs: index 0 is all categories, the rest follow [EventLog.Category] order. */
private val FILTER_LABELS = listOf("전체") + EventLog.Category.entries.map { it.label }

private const val RAW_LINE_LIMIT = 500

private val EventLog.Category.label: String
    get() = when (this) {
        EventLog.Category.APP -> "앱"
        EventLog.Category.BLE -> "BLE"
        EventLog.Category.SERVER -> "서버"
        EventLog.Category.OVERLAY -> "오버레이"
    }

private fun clockMsText(millis: Long): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA).format(millis)

@Composable
private fun RawLogScreen() {
    val context = LocalContext.current
    var filterIndex by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    val category = EventLog.Category.entries.getOrNull(filterIndex - 1)
    // Read from the database off the main thread, so a large log does not stall the screen.
    val lines by produceState(initialValue = emptyList<EventLog.RawEntry>(), filterIndex, revision) {
        value = withContext(Dispatchers.IO) { EventLog.rawLogs(context, category, RAW_LINE_LIMIT) }
    }

    Scaffold(topBar = { TopAppBar(title = "RAW 기록") }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TabRow(
                tabs = FILTER_LABELS,
                selectedTabIndex = filterIndex,
                onTabSelected = { filterIndex = it },
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${lines.size}줄 · 최근 ${RAW_LINE_LIMIT}줄 · 전화번호는 뒤 4자리만",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { revision++ }) { Text("새로고침") }
                Button(onClick = { copyLines(context, lines) }) { Text("복사") }
            }

            Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (lines.isEmpty()) {
                    Text(
                        text = "기록 없음",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                        itemsIndexed(lines) { index, e ->
                            if (index > 0) HorizontalDivider()
                            RawRow(e)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RawRow(e: EventLog.RawEntry) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = clockMsText(e.timestampMillis),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote2,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = e.category.label,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                style = MiuixTheme.textStyles.footnote2,
            )
        }
        Text(
            text = e.message,
            style = MiuixTheme.textStyles.footnote1,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun copyLines(context: Context, lines: List<EventLog.RawEntry>) {
    val text = lines.asReversed().joinToString("\n") {
        "${clockMsText(it.timestampMillis)} [${it.category.label}] ${it.message}"
    }
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("RAW", text))
}
