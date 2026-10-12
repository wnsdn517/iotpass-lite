package dev.hoduj.iotpasslite

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A door the server lists for this account. */
data class ServerDoor(val mac: String, val name: String, val group: String, val elevator: Boolean)

/** Direct TCP protocol with the IoTPass server, the same commands the official app sends.
 *
 *  login (cmd 33): 501-byte body: AES-encrypted phone, MD5 of phone, push token, app version, model.
 *  open  (cmd 4):  20-byte body: door id (last 3 MAC bytes, lowercase hex) and phone number.
 *
 *  Frame: 22 EE 22 EE | type (03 request, 01 reply) | cmd | 2 bytes | body length (LE) | body.
 *  One connection per request. Reply body[0] == 0 means success. Verified byte-for-byte against a
 *  capture of the official app. */
object RemoteDoor {

    const val HOST = "iotpass.cns-link.net"
    const val PORT = 15722

    /** Push token sent when the app has none; the official app uses the same fallback value. */
    const val PUSH_TOKEN = "TEST"

    private val MAGIC = byteArrayOf(0x22, 0xEE.toByte(), 0x22, 0xEE.toByte())
    private const val CMD_LOGIN = 33
    private const val CMD_OPEN = 4
    private const val CMD_DOOR_LIST = 64
    private const val CMD_USER_STATUS = 42
    private const val RECORD_LEN = 185
    private const val LOGIN_BODY_LEN = 501
    private const val OPEN_BODY_LEN = 20
    private const val TIMEOUT_MS = 15_000

    /** AES key and IV the official app uses for the phone number in the login body. */
    private const val AES_KEY = "06000256113001060425336044807894"
    private const val AES_IV = "1600025611304575"

    fun buildLoginPacket(phone: String, fcmToken: String, model: String, release: String, appVersion: String): ByteArray {
        val body = ByteArray(LOGIN_BODY_LEN)
        put(body, 0, 33, encryptPhone(phone))
        put(body, 33, 33, md5Hex(phone))
        put(body, 66, 3, "FCM")
        put(body, 71, 250, fcmToken)
        put(body, 321, 150, "_$appVersion")
        put(body, 471, 20, model)
        put(body, 491, 10, release)
        return frame(CMD_LOGIN, body)
    }

    fun buildOpenPacket(doorMac: String, phone: String): ByteArray {
        val parts = doorMac.split(":")
        require(parts.size == 6) { "bad MAC: $doorMac" }
        val doorId = (parts[3] + parts[4] + parts[5]).lowercase()
        val body = ByteArray(OPEN_BODY_LEN)
        put(body, 0, 6, doorId)
        put(body, 7, 13, phone)
        return frame(CMD_OPEN, body)
    }

    /** Reply status byte for login (0 = OK). Blocking; call from a background thread. */
    @Throws(IOException::class)
    fun loginReply(phone: String, model: String, release: String, appVersion: String): Int =
        exchange(buildLoginPacket(phone, PUSH_TOKEN, model, release, appVersion), CMD_LOGIN)[0].toInt() and 0xFF

    /** Reply status byte for the open command (0 = OK). Blocking; call from a background thread. */
    @Throws(IOException::class)
    fun openReply(doorMac: String, phone: String): Int =
        exchange(buildOpenPacket(doorMac, phone), CMD_OPEN)[0].toInt() and 0xFF

    /** Sends one request and returns the reply body. Throws if the reply header does not match [cmd]. */
    @Throws(IOException::class)
    private fun exchange(request: ByteArray, cmd: Int): ByteArray {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(HOST, PORT), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            socket.getOutputStream().apply {
                write(request)
                flush()
            }
            return readReply(socket.getInputStream(), cmd)
        }
    }

    @Throws(IOException::class)
    internal fun readReply(input: InputStream, cmd: Int): ByteArray {
        val header = readFully(input, 12)
        if (!header.copyOfRange(0, 4).contentEquals(MAGIC) || header[4] != 0x01.toByte() ||
            (header[5].toInt() and 0xFF) != cmd
        ) {
            throw IOException("unexpected reply header")
        }
        val bodyLen = (header[8].toInt() and 0xFF) or
            ((header[9].toInt() and 0xFF) shl 8) or
            ((header[10].toInt() and 0xFF) shl 16) or
            ((header[11].toInt() and 0xFF) shl 24)
        if (bodyLen <= 0 || bodyLen > 1 shl 20) throw IOException("bad reply length $bodyLen")
        return readFully(input, bodyLen)
    }

    /** Door list for the logged-in phone number (cmd 64). Blocking; call from a background thread. */
    @Throws(IOException::class)
    fun doorList(phone: String): List<ServerDoor> {
        val body = exchange(buildDoorListPacket(phone), CMD_DOOR_LIST)
        if (body[0] != 0.toByte()) throw IOException("door list code ${body[0].toInt() and 0xFF}")
        return parseDoorList(body)
    }

    /** User status (cmd 42), sent by the official app before login. Reply body[0] == 0 is OK. */
    @Throws(IOException::class)
    fun userStatus(phone: String): Int =
        exchange(buildUserStatusPacket(phone), CMD_USER_STATUS)[0].toInt() and 0xFF

    fun buildUserStatusPacket(phone: String): ByteArray {
        val body = ByteArray(13)
        put(body, 0, 13, phone)
        return frame(CMD_USER_STATUS, body)
    }

    fun buildDoorListPacket(phone: String): ByteArray {
        val body = ByteArray(16)
        put(body, 0, 11, phone)
        body[13] = 2
        body[14] = 0
        body[15] = 1
        return frame(CMD_DOOR_LIST, body)
    }

    /** Reply layout: [0] status, [1..3) door count (LE), then 185-byte records from offset 3:
     *  group name (61), door name (64), 6 hex digits of the MAC after "D0:BE:2C:" (7), and more fields
     *  not used here. Verified against a capture of the official app. */
    fun parseDoorList(body: ByteArray): List<ServerDoor> {
        if (body.size < 3) return emptyList()
        val count = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
        val doors = ArrayList<ServerDoor>(count)
        for (i in 0 until count) {
            val base = 3 + i * RECORD_LEN
            if (base + RECORD_LEN > body.size) break
            val group = text(body, base, 61)
            val name = text(body, base + 61, 64)
            val elevator = body[base + 178] == 'Y'.code.toByte()
            val hex = text(body, base + 125, 7).lowercase()
            if (hex.length != 6) continue
            val mac = "D0:BE:2C:%s:%s:%s".format(
                hex.substring(0, 2).uppercase(),
                hex.substring(2, 4).uppercase(),
                hex.substring(4, 6).uppercase(),
            )
            doors += ServerDoor(mac = mac, name = name, group = group, elevator = elevator)
        }
        return doors
    }

    private fun text(body: ByteArray, offset: Int, len: Int): String {
        val end = (offset until offset + len).firstOrNull { body[it] == 0.toByte() } ?: (offset + len)
        return String(body, offset, end - offset, StandardCharsets.UTF_8).trim()
    }

    private fun frame(cmd: Int, body: ByteArray): ByteArray {
        val len = body.size
        val packet = ByteArray(12 + len)
        MAGIC.copyInto(packet, 0)
        packet[4] = 0x03
        packet[5] = cmd.toByte()
        packet[6] = 0x01
        packet[7] = 0x00
        packet[8] = len.toByte()
        packet[9] = (len ushr 8).toByte()
        packet[10] = (len ushr 16).toByte()
        packet[11] = (len ushr 24).toByte()
        body.copyInto(packet, 12)
        return packet
    }

    /** Writes text at offset, truncated to maxLen bytes. The rest of the field stays zero. */
    private fun put(body: ByteArray, offset: Int, maxLen: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        bytes.copyInto(body, offset, 0, minOf(bytes.size, maxLen))
    }

    @Throws(IOException::class)
    private fun encryptPhone(phone: String): String = try {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(AES_KEY.toByteArray(StandardCharsets.UTF_8), "AES"),
            IvParameterSpec(AES_IV.toByteArray(StandardCharsets.UTF_8)),
        )
        Base64.getEncoder().encodeToString(cipher.doFinal(phone.toByteArray(StandardCharsets.UTF_8)))
    } catch (e: Exception) {
        throw IOException("phone encryption failed", e)
    }

    private fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Throws(IOException::class)
    private fun readFully(input: InputStream, len: Int): ByteArray {
        val buf = ByteArrayOutputStream(len)
        val chunk = ByteArray(len)
        while (buf.size() < len) {
            val n = input.read(chunk, 0, len - buf.size())
            if (n < 0) throw IOException("connection closed")
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }
}
