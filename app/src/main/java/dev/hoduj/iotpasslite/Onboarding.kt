package dev.hoduj.iotpasslite

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** First launch: explains what the app needs and asks for it. Bluetooth and the phone number are required;
 *  the rest can be skipped. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // Permissions are granted in system screens, so re-read them whenever this page comes back to the front.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
    }
    val requiredMissing = requiredPermissions().filter { !granted(context, it) }
    val ready = requiredMissing.isEmpty() && tick >= 0

    Scaffold(topBar = { TopAppBar(title = context.getString(R.string.ob_title)) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = context.getString(R.string.ob_intro), style = MiuixTheme.textStyles.body1)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    PermissionLine(
                        title = context.getString(R.string.ob_bluetooth),
                        required = true,
                        granted = requiredPermissions().filter { it != DevicePhone.permission() }.all { granted(context, it) },
                    )
                    HorizontalDivider()
                    PermissionLine(
                        title = context.getString(R.string.ob_phone),
                        required = true,
                        granted = granted(context, DevicePhone.permission()),
                    )
                    HorizontalDivider()
                    PermissionLine(
                        title = context.getString(R.string.ob_notification),
                        required = false,
                        granted = Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS),
                    )
                    HorizontalDivider()
                    PermissionLine(
                        title = context.getString(R.string.ob_overlay),
                        required = false,
                        granted = SendOverlay.canOverlay(context),
                    )
                    HorizontalDivider()
                    PermissionLine(
                        title = context.getString(R.string.ob_battery),
                        required = false,
                        granted = batteryExempt(context),
                    )
                }
            }

            Button(
                onClick = { launcher.launch(requiredMissing.toTypedArray()) },
                enabled = requiredMissing.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = context.getString(R.string.ob_allow))
            }
            Button(
                onClick = {
                    if (!SendOverlay.canOverlay(context)) {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = context.getString(R.string.ob_open_overlay))
            }
            Button(
                onClick = onDone,
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = context.getString(R.string.ob_start))
            }
            Text(
                text = context.getString(R.string.ob_optional_note),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
            )
        }
    }
}

@Composable
private fun PermissionLine(title: String, required: Boolean, granted: Boolean) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1)
        Text(
            text = when {
                granted -> context.getString(R.string.ob_status_granted)
                required -> context.getString(R.string.ob_status_required)
                else -> context.getString(R.string.ob_status_optional)
            },
            color = if (granted) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote1,
        )
    }
}

/** Runtime permissions that must be granted before the app can work. */
private fun requiredPermissions(): List<String> = listOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_ADVERTISE,
    DevicePhone.permission(),
)

private fun granted(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun batteryExempt(context: Context): Boolean =
    context.getSystemService(android.os.PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true
