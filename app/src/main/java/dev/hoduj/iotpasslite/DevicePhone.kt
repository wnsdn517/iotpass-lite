package dev.hoduj.iotpasslite

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** The phone number is the access key, so it comes from the SIM instead of being typed. Reading it needs
 *  READ_PHONE_NUMBERS on Android 13+ and READ_PHONE_STATE before that. Many devices expose no number at all. */
object DevicePhone {

    fun permission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_PHONE_NUMBERS else Manifest.permission.READ_PHONE_STATE

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission()) == PackageManager.PERMISSION_GRANTED

    /** The number in domestic form (010...), or null when unavailable. */
    fun read(context: Context): String? {
        if (!hasPermission(context)) return null
        val raw = try {
            context.getSystemService(TelephonyManager::class.java)?.line1Number
        } catch (e: SecurityException) {
            null
        } ?: return null
        return normalize(raw)
    }

    /** "+82 10-1234-5678" becomes "01012345678". Returns null for anything too short to be a number. */
    fun normalize(raw: String): String? {
        val digits = raw.filter { it.isDigit() || it == '+' }
        val local = when {
            digits.startsWith("+82") -> "0" + digits.removePrefix("+82")
            digits.startsWith("82") && digits.length > 10 -> "0" + digits.removePrefix("82")
            else -> digits
        }
        return local.filter { it.isDigit() }.takeIf { it.length >= 9 }
    }
}
