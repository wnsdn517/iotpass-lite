package dev.hoduj.iotpasslite

import android.content.Context
import android.os.Build
import java.io.IOException

/** Server-side actions. Each takes the phone number from the screen, so it works before the watcher has
 *  saved anything. Login (cmd 33) always comes before a remote request. */
object ServerSession {

    sealed interface Result {
        data object Success : Result
        data object NoPhoneNumber : Result
        data class LoginRejected(val code: Int) : Result
        data class OpenRejected(val code: Int) : Result
        data class Network(val message: String) : Result
        data class Doors(val doors: List<ServerDoor>, val statusCode: Int) : Result
        data class Limited(val secondsLeft: Int) : Result
    }

    /** Minimum gap between server actions, so repeated taps cannot flood the server. */
    object RateLimit {
        const val COOLDOWN_MS = 5_000L
        private var lastActionMillis = 0L

        /** 0 if an action may run now (and is recorded), otherwise seconds until the cooldown ends. */
        @Synchronized
        fun tryAcquire(now: Long = System.currentTimeMillis()): Int {
            val wait = COOLDOWN_MS - (now - lastActionMillis)
            if (wait > 0) return ((wait + 999) / 1000).toInt()
            lastActionMillis = now
            return 0
        }
    }

    private fun limited(context: Context, seconds: Int): Result {
        EventLog.log(context, EventLog.Type.SERVER, null, "요청 제한: ${seconds}초 뒤 재시도")
        return Result.Limited(seconds)
    }

    /** Blocking; call from a background thread. */
    fun login(context: Context, phone: String): Result {
        if (phone.isEmpty()) return Result.NoPhoneNumber
        val wait = RateLimit.tryAcquire()
        if (wait > 0) return limited(context, wait)
        return loginOnce(context, phone)
    }

    /** One login attempt, without counting against the limit (used inside a user action). */
    private fun loginOnce(context: Context, phone: String): Result {
        val result = try {
            val reply = RemoteDoor.loginReply(phone, Build.MODEL, Build.VERSION.RELEASE, appVersion(context))
            if (reply == 0) Result.Success else Result.LoginRejected(reply)
        } catch (e: IOException) {
            Result.Network(e.message ?: "network error")
        }
        EventLog.raw(context, EventLog.Category.SERVER, "login cmd=33 -> ${describeShort(result)} (${result::class.simpleName})")
        EventLog.log(context, EventLog.Type.SERVER, null, "로그인 " + describeShort(result))
        return result
    }

    private fun describeShort(result: Result): String = when (result) {
        Result.Success -> "성공"
        Result.NoPhoneNumber -> "번호 없음"
        is Result.LoginRejected -> "거부 코드 ${result.code}"
        is Result.OpenRejected -> "열기 거부 코드 ${result.code}"
        is Result.Network -> "네트워크 오류"
        is Result.Doors -> "목록 ${result.doors.size}개"
        is Result.Limited -> "잠시 뒤 다시 시도 (${result.secondsLeft}초)"
    }

    /** Blocking; call from a background thread. Logs in first, then sends the open request. */
    fun remoteOpen(context: Context, phone: String, doorMac: String): Result {
        if (phone.isEmpty()) return Result.NoPhoneNumber
        val wait = RateLimit.tryAcquire()
        if (wait > 0) return limited(context, wait)
        val login = loginOnce(context, phone)
        if (login !is Result.Success) return login
        return try {
            val reply = RemoteDoor.openReply(doorMac, phone)
            EventLog.raw(context, EventLog.Category.SERVER, "open cmd=4 mac=$doorMac -> reply=$reply")
            if (reply == 0) {
                EventLog.log(context, EventLog.Type.DOOR_OPEN, doorMac, "remote")
                Result.Success
            } else {
                EventLog.log(context, EventLog.Type.ADVERTISE_FAIL, doorMac, "remote code=$reply")
                Result.OpenRejected(reply)
            }
        } catch (e: IOException) {
            EventLog.raw(context, EventLog.Category.SERVER, "open cmd=4 mac=$doorMac -> network error: ${e.message}")
            EventLog.log(context, EventLog.Type.ADVERTISE_FAIL, doorMac, "remote network error")
            Result.Network(e.message ?: "network error")
        }
    }

    /** Status (cmd 42), then login (cmd 33), then the door list (cmd 64), in the official app's order. */
    fun refreshDoors(context: Context, phone: String): Result {
        if (phone.isEmpty()) return Result.NoPhoneNumber
        val wait = RateLimit.tryAcquire()
        if (wait > 0) return limited(context, wait)
        return try {
            val status = RemoteDoor.userStatus(phone)
            val login = loginOnce(context, phone)
            if (login !is Result.Success) return login
            val doors = RemoteDoor.doorList(phone)
            EventLog.log(context, EventLog.Type.SERVER, null, "문 목록 ${doors.size}개 (상태 $status)")
            Result.Doors(doors, status)
        } catch (e: IOException) {
            EventLog.log(context, EventLog.Type.SERVER, null, "문 목록 실패: ${e.message}")
            Result.Network(e.message ?: "network error")
        }
    }

    fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        "0.0.0"
    }
}
