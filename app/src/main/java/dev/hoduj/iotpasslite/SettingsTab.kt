package dev.hoduj.iotpasslite

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 설정 tab: theme, language, overlay position, phone number source, and the RAW log link. */
@Composable
internal fun SettingsTab() {
    val context = LocalContext.current
    var theme by remember { mutableStateOf(Prefs.getTheme(context)) }
    var language by remember { mutableStateOf(Prefs.getLanguage(context)) }
    var overlayTop by remember { mutableStateOf(Prefs.isOverlayTopAttached(context)) }
    var manual by remember { mutableStateOf(Prefs.isPhoneManual(context)) }
    var manualInput by remember { mutableStateOf(Prefs.getPhoneNumber(context)) }
    val watcherOn = remember { Prefs.isEnabled(context) }
    var confirmManual by remember { mutableStateOf(false) }

    if (confirmManual) {
        MiuixDialog(
            title = context.getString(R.string.phone_manual_title),
            summary = context.getString(R.string.phone_manual_warning),
            onDismiss = { confirmManual = false },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { confirmManual = false }, modifier = Modifier.weight(1f)) {
                    Text(text = context.getString(R.string.phone_manual_cancel))
                }
                Button(
                    onClick = {
                        confirmManual = false
                        manual = true
                        Prefs.setPhoneManual(context, true)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = context.getString(R.string.phone_manual_agree))
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(context.getString(R.string.set_theme)) {
            Choice(context.getString(R.string.set_theme_system), theme == Prefs.THEME_SYSTEM) {
                theme = Prefs.THEME_SYSTEM
                AppTheme.set(context, theme)
            }
            HorizontalDivider()
            Choice(context.getString(R.string.set_theme_light), theme == Prefs.THEME_LIGHT) {
                theme = Prefs.THEME_LIGHT
                AppTheme.set(context, theme)
            }
            HorizontalDivider()
            Choice(context.getString(R.string.set_theme_dark), theme == Prefs.THEME_DARK) {
                theme = Prefs.THEME_DARK
                AppTheme.set(context, theme)
            }
        }

        SettingsSection(context.getString(R.string.set_language)) {
            Choice(context.getString(R.string.set_language_system), language == Prefs.LANG_SYSTEM) {
                language = Prefs.LANG_SYSTEM
                Prefs.setLanguage(context, language)
                (context as? Activity)?.recreate()
            }
            HorizontalDivider()
            Choice(context.getString(R.string.set_language_en), language == Prefs.LANG_EN) {
                language = Prefs.LANG_EN
                Prefs.setLanguage(context, language)
                (context as? Activity)?.recreate()
            }
            HorizontalDivider()
            Choice(context.getString(R.string.set_language_ko), language == Prefs.LANG_KO) {
                language = Prefs.LANG_KO
                Prefs.setLanguage(context, language)
                (context as? Activity)?.recreate()
            }
        }

        SettingsSection(context.getString(R.string.set_overlay)) {
            Choice(context.getString(R.string.set_overlay_notch), overlayTop) {
                overlayTop = true
                Prefs.setOverlayStyle(context, Prefs.OVERLAY_STYLE_TOP_ATTACHED)
                previewOverlay(context)
            }
            HorizontalDivider()
            Choice(context.getString(R.string.set_overlay_floating), !overlayTop) {
                overlayTop = false
                Prefs.setOverlayStyle(context, Prefs.OVERLAY_STYLE_FLOATING)
                previewOverlay(context)
            }
        }

        SettingsSection(context.getString(R.string.set_phone)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = context.getString(R.string.set_phone_from_device),
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.body1,
                )
                Switch(
                    checked = !manual,
                    onCheckedChange = { useDevice ->
                        if (useDevice) {
                            manual = false
                            Prefs.setPhoneManual(context, false)
                            DevicePhone.read(context)?.let { Prefs.setPhoneNumber(context, it) }
                        } else {
                            // Manual entry needs an explicit agreement; the switch stays on until it is given.
                            confirmManual = true
                        }
                    },
                    enabled = !watcherOn,
                )
            }

            if (!manual) {
                val number = Prefs.getPhoneNumber(context)
                Text(
                    text = if (number.isEmpty()) {
                        context.getString(R.string.set_phone_unavailable)
                    } else {
                        context.getString(R.string.set_phone_current, maskedPhone(number))
                    },
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            } else {
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = context.getString(R.string.set_phone_warning),
                            color = MiuixTheme.colorScheme.error,
                            style = MiuixTheme.textStyles.footnote1,
                        )
                        TextField(
                            value = manualInput,
                            onValueChange = { manualInput = it.filter { c -> c.isDigit() } },
                            label = context.getString(R.string.set_phone_input_label),
                            enabled = !watcherOn,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { Prefs.setPhoneNumber(context, manualInput) },
                            enabled = !watcherOn && manualInput.length >= 9,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = context.getString(R.string.set_phone_save))
                        }
                    }
                }
            }
            if (watcherOn) {
                Text(
                    text = context.getString(R.string.set_phone_locked),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }

        SettingsSection(context.getString(R.string.set_more)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { context.startActivity(Intent(context, RawLogActivity::class.java)) }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = context.getString(R.string.set_raw_log),
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.body1,
                )
                Text(
                    text = "›",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.title4,
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader(text = title)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { content() }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1)
        RadioButton(selected = selected, onClick = onSelect)
    }
}

/** Shows the chosen overlay style for a moment. Without the overlay permission, say so inside the app. */
private fun previewOverlay(context: Context) {
    if (!SendOverlay.preview(context)) {
        InAppToast.show(context.getString(R.string.overlay_need_permission))
    }
}

/** 010****3829 style, so the full number is not shown on screen. */
private fun maskedPhone(number: String): String =
    if (number.length <= 7) number else number.take(3) + "****" + number.takeLast(4)
