package dev.hoduj.iotpasslite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Result messages shown inside the app, above the bottom edge. Set from the main thread. */
object InAppToast {
    val message = mutableStateOf<String?>(null)

    fun show(text: String) {
        message.value = text
    }
}

/** Draws the current [InAppToast] message and clears it after a short time. Place it over the screen content. */
@Composable
fun InAppToastHost(modifier: Modifier = Modifier) {
    val text = InAppToast.message.value
    LaunchedEffect(text) {
        if (text != null) {
            delay(2_500)
            InAppToast.message.value = null
        }
    }
    if (text != null) {
        Box(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)) {
            Card(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    style = MiuixTheme.textStyles.body1,
                )
            }
        }
    }
}
