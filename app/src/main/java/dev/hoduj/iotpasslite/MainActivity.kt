package dev.hoduj.iotpasslite

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class MainActivity : ComponentActivity() {

    private val resumeCount = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                MainScreen(resumeCount.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount.intValue++
        PresenceScan.start(this)
    }

    override fun onPause() {
        super.onPause()
        PresenceScan.stop()
    }
}

private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
private val mainHandler = Handler(Looper.getMainLooper())

private fun hasPermission(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun hasRequiredPermissions(context: Context): Boolean =
    REQUIRED_PERMISSIONS.all { hasPermission(context, it) }


private fun isBluetoothOn(context: Context): Boolean =
    context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

/** While the main screen is visible, listens for reader advertisements so each door's Bluetooth state is
 *  known. Results go to [DoorPresence]; the door buttons use it to choose Bluetooth or remote open. */
private object PresenceScan {
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null

    private var appContext: Context? = null

    fun start(context: Context) {
        if (callback != null) return
        appContext = context.applicationContext
        if (!hasPermission(context, Manifest.permission.BLUETOOTH_SCAN) || !isBluetoothOn(context)) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val mac = result.device.address
                val rssi = result.rssi
                mainHandler.post {
                    DoorPresence.markSeen(mac, rssi)
                    if (DoorPresence.isNearby(mac)) appContext?.let { EventLog.touchPresence(it, mac) }
                }
            }
        }
        try {
            adapter.bluetoothLeScanner?.startScan(
                DoorProtocol.discoveryScanFilters(), DoorProtocol.foregroundDiscoverySettings(), cb,
            )
            scanner = adapter.bluetoothLeScanner
            callback = cb
        } catch (ignored: SecurityException) {
            // Permission revoked; the screen still works, every door will fall back to remote open.
        }
    }

    fun stop() {
        val cb = callback ?: return
        try {
            scanner?.stopScan(cb)
        } catch (ignored: SecurityException) {
            // Nothing to clean up.
        }
        callback = null
        scanner = null
    }
}

/** Runs [work] on a background thread, then [done] on the main thread. */
private fun <T> background(work: () -> T, done: (T) -> Unit) {
    Thread {
        val value = work()
        mainHandler.post { done(value) }
    }.start()
}

@Composable
private fun MainScreen(resumeTick: Int) {
    val context = LocalContext.current

    var enabled by remember { mutableStateOf(Prefs.isEnabled(context)) }
    var phone by remember { mutableStateOf(Prefs.getPhoneNumber(context)) }
    var doors by remember { mutableStateOf(Prefs.getDoors(context)) }
    var overlayTop by remember { mutableStateOf(Prefs.isOverlayTopAttached(context)) }
    var serverMessage by remember { mutableStateOf("") }
    var actionMessage by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var permRevision by remember { mutableIntStateOf(0) }
    var pendingRemote by remember { mutableStateOf<ServerDoor?>(null) }

    // Automatic login only while the watcher is on. When it is off, login happens on demand (list refresh or open).
    LaunchedEffect(Unit) {
        if (enabled && phone.trim().isNotEmpty()) {
            serverMessage = "서버 로그인 중…"
            background({ describe(ServerSession.login(context, phone.trim())) }) { serverMessage = it }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permRevision++
        PresenceScan.start(context)
    }

    fun setWatcher(on: Boolean) {
        if (!on) {
            DoorScanner.stop(context)
            EventLog.log(context, EventLog.Type.SCAN_OFF, null, null)
            Prefs.setEnabled(context, false)
            enabled = false
            return
        }
        if (doors.isEmpty() || phone.trim().isEmpty()) {
            EventLog.log(context, EventLog.Type.REARM_FAILED, null, "문 목록과 전화번호가 필요합니다.")
            return
        }
        if (!isBluetoothOn(context)) {
            EventLog.log(context, EventLog.Type.REARM_FAILED, null, "블루투스를 켜 주세요.")
            return
        }
        if (!hasRequiredPermissions(context)) {
            EventLog.log(context, EventLog.Type.REARM_FAILED, null, "블루투스 권한을 허용해 주세요.")
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
            return
        }
        Prefs.save(context, phone.trim(), true)
        if (!DoorScanner.start(context, doors.map { it.mac })) {
            EventLog.log(context, EventLog.Type.REARM_FAILED, null, "감시 시작 실패")
            EventLog.log(context, EventLog.Type.REARM_FAILED, null, "블루투스 감시를 시작하지 못했습니다.")
            return
        }
        EventLog.log(context, EventLog.Type.SCAN_ON, null, doors.joinToString(", ") { it.mac })
        enabled = true
    }

    fun askRemote(door: ServerDoor) {
        busy = false
        actionMessage = ""
        pendingRemote = door
    }

    fun confirmRemote(door: ServerDoor) {
        val number = phone.trim()
        pendingRemote = null
        busy = true
        actionMessage = "원격 문열기 중…"
        background({ describe(ServerSession.remoteOpen(context, number, door.mac)) }) {
            busy = false
            actionMessage = it
        }
    }

    fun openDoor(door: ServerDoor) {
        val number = phone.trim()
        if (number.isEmpty()) {
            actionMessage = "전화번호를 입력해 주세요."
            return
        }
        val name = door.name.ifEmpty { door.mac }
        val connected = DoorPresence.isNearby(door.mac)
        if (connected && isBluetoothOn(context) && hasRequiredPermissions(context)) {
            pendingRemote = null
            val started = DoorOpener.send(context, number, door.mac, "button") {
                busy = false
                actionMessage = "$name 블루투스 신호 전송 완료"
            }
            if (started) {
                busy = true
                actionMessage = "$name 블루투스 신호 전송 중…"
            } else {
                askRemote(door)
            }
            return
        }
        askRemote(door)
    }

    fun refreshDoors() {
        busy = true
        serverMessage = "서버에서 문 목록을 가져오는 중…"
        background({ ServerSession.refreshDoors(context, phone.trim()) }) { result ->
            busy = false
            if (result is ServerSession.Result.Doors) {
                Prefs.setDoors(context, result.doors)
                doors = result.doors
                serverMessage = if (result.doors.isEmpty()) {
                    "서버에 등록된 출입문이 없습니다."
                } else {
                    "출입문 ${result.doors.size}개를 갱신했습니다."
                }
            } else {
                serverMessage = describe(result)
            }
        }
    }

    val permissionRows = listOf(
        PermRow(
            label = context.getString(R.string.perm_bt),
            granted = hasRequiredPermissions(context),
        ) { permissionLauncher.launch(REQUIRED_PERMISSIONS) },
        PermRow(
            label = context.getString(R.string.perm_noti),
            granted = hasPermission(context, Manifest.permission.POST_NOTIFICATIONS),
        ) {
            if (Build.VERSION.SDK_INT >= 33) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        },
        PermRow(
            label = context.getString(R.string.perm_battery),
            granted = context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true,
        ) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
            )
        },
        PermRow(
            label = context.getString(R.string.perm_overlay),
            granted = SendOverlay.canOverlay(context),
        ) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            )
        },
    )

    val missingPermissions = permissionRows.filter { !it.granted }
    val accentRunning = Color(0xFF30D158)
    val mutedText = Color(0x99FFFFFF)
    val warning = Color(0xFFFF9F0A)

    val pending = pendingRemote
    pendingRemote?.let { door ->
        MiuixDialog(
            title = "원격 문열기",
            summary = "${door.name.ifEmpty { door.mac }} 문을 서버로 열까요?\n근처에서 블루투스 신호가 없습니다.",
            onDismiss = { pendingRemote = null },
        ) {
            Button(
                onClick = { confirmRemote(door) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "원격 열기")
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = context.getString(R.string.title_main)) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Watcher switch and the phone number in one card. The phone field folds away while the watcher runs.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp).animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "자동 출입", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = { setWatcher(it) })
                    }
                    if (!enabled) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextField(
                                value = phone,
                                onValueChange = {
                                    phone = it.trim()
                                    Prefs.setPhoneNumber(context, phone)
                                },
                                label = "전화번호",
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        Text(text = "전화번호  " + maskPhone(phone), color = mutedText, fontSize = 13.sp)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "출입문 ${doors.size}",
                    color = Color(0x99FFFFFF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    icon = R.drawable.ic_refresh,
                    enabled = !enabled && !busy && phone.trim().isNotEmpty(),
                    onClick = { refreshDoors() },
                )
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                    if (doors.isEmpty()) {
                        Text(
                            text = "목록을 갱신하면 출입문이 나타납니다.",
                            color = mutedText,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    doors.forEachIndexed { index, door ->
                        if (index > 0) Divider()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = door.name.ifEmpty { door.mac }, fontWeight = FontWeight.Medium)
                                Text(text = door.mac, color = mutedText, fontSize = 12.sp)
                            }
                            IconButton(
                                icon = R.drawable.ic_door,
                                enabled = !busy,
                                onClick = { openDoor(door) },
                            )
                        }
                    }
                }
            }

            if (missingPermissions.isNotEmpty()) {
                SectionTitle("권한")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                        key(permRevision, resumeTick) {
                            missingPermissions.forEachIndexed { index, row ->
                                if (index > 0) Divider()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { row.onClick() }
                                        .padding(vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(text = row.label, color = warning, modifier = Modifier.weight(1f))
                                    Image(
                                        painter = painterResource(R.drawable.ic_chevron),
                                        contentDescription = null,
                                        colorFilter = ColorFilter.tint(mutedText),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SectionTitle("설정")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                    // Tap to switch between the notch and the floating pill.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                overlayTop = !overlayTop
                                Prefs.setOverlayStyle(
                                    context,
                                    if (overlayTop) Prefs.OVERLAY_STYLE_TOP_ATTACHED else Prefs.OVERLAY_STYLE_FLOATING,
                                )
                            }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "오버레이", modifier = Modifier.weight(1f))
                        Text(text = if (overlayTop) "노치" else "플로팅", color = mutedText, fontSize = 14.sp)
                    }
                    Divider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { context.startActivity(Intent(context, EventLogActivity::class.java)) }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "통신 기록", modifier = Modifier.weight(1f))
                        Image(
                            painter = painterResource(R.drawable.ic_chevron),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(mutedText),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            if (BuildConfig.DEBUG) {
                Text(
                    text = "오버레이 테스트",
                    color = mutedText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp).clickable { SendOverlay.show(context) },
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = Color(0x99FFFFFF),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun Divider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0x22FFFFFF)))
}

/** Miuix-styled dialog: title, summary, and the given buttons. Tapping outside closes it. */
@Composable
private fun MiuixDialog(
    title: String,
    summary: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(text = summary, color = Color(0x99FFFFFF), fontSize = 14.sp)
                content()
            }
        }
    }
}

/** Round icon button with a drawable glyph; dims when disabled. */
@Composable
private fun IconButton(icon: Int, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(if (enabled) Color.White else Color(0x66FFFFFF)),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Shows the number with the middle digits hidden, e.g. 010****3829. */
private fun maskPhone(number: String): String =
    if (number.length >= 8) number.take(3) + "****" + number.takeLast(4) else number

private class PermRow(val label: String, val granted: Boolean, val onClick: () -> Unit)

private fun describe(result: ServerSession.Result): String = when (result) {
    ServerSession.Result.Success -> "완료"
    is ServerSession.Result.Doors -> "출입문 ${result.doors.size}개 (상태 코드 ${result.statusCode})"
    ServerSession.Result.NoPhoneNumber -> "전화번호를 입력해 주세요."
    is ServerSession.Result.LoginRejected -> "로그인 거부 (서버 코드 ${result.code})"
    is ServerSession.Result.OpenRejected -> "문열기 거부 (서버 코드 ${result.code})"
    is ServerSession.Result.Network -> "네트워크 오류: ${result.message}"
    is ServerSession.Result.Limited -> "요청이 너무 빠릅니다. ${result.secondsLeft}초 뒤 다시 시도하세요."
}
