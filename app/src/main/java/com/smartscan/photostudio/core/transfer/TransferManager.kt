package com.smartscan.photostudio.core.transfer

import android.content.Context
import android.net.Uri
import com.smartscan.photostudio.core.ShareUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom

/** Files that other screens (exports, My Files) want to send; read by the Send to PC screen. */
object TransferQueue {
    private val _files = MutableStateFlow<List<File>>(emptyList())
    val files: StateFlow<List<File>> = _files.asStateFlow()
    fun set(list: List<File>) { _files.value = list }
}

data class TransferUiState(
    val lan: LanInfo = LanInfo(false, null),
    val running: Boolean = false,
    val url: String? = null,
    val names: List<String> = emptyList(),
    val sizes: List<Long> = emptyList(),
    val doneIdx: Set<Int> = emptySet(),
    val activeIdx: Int? = null,
    val sent: Long = 0,
    val total: Long = 0,
    val clientIp: String? = null,
    val clientAgent: String? = null,
    val secondsLeft: Int = 0,
    val allDone: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

/** Owns the single temporary transfer session. It survives screen changes; it never outlives its timeout. */
object TransferManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(TransferUiState())
    val state: StateFlow<TransferUiState> = _state.asStateFlow()
    private var server: TransferServer? = null
    private var timer: Job? = null

    fun refreshNetwork(ctx: Context) { _state.update { it.copy(lan = NetInfo.current(ctx)) } }

    /** Returns null on success, otherwise a message that explains what went wrong. */
    fun start(ctx: Context, files: List<File>, timeoutMin: Int): String? {
        stopInternal()
        val usable = files.filter { it.isFile && it.canRead() }
        if (usable.isEmpty()) return "The selected files could not be read."
        val lan = NetInfo.current(ctx)
        _state.update { it.copy(lan = lan) }
        val ip = lan.ip
        if (!lan.connected || ip == null) return "Not connected to Wi-Fi. Connect to the same network as your PC and try again."
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        val items = usable.map { TransferItem(Uri.fromFile(it), it.name, it.length(), ShareUtil.mimeOf(it)) }
        val srv = TransferServer(ctx.applicationContext, items, token, listener(items.size))
        val port = try { srv.start(ip) } catch (e: Exception) { return "Could not start the transfer server: ${e.message}" }
        server = srv
        val seconds = timeoutMin.coerceIn(1, 60) * 60
        _state.value = TransferUiState(
            lan = lan, running = true, url = "http://$ip:$port/$token/",
            names = items.map { it.name }, sizes = items.map { it.size }, secondsLeft = seconds,
        )
        timer = scope.launch {
            var left = seconds
            while (left > 0) {
                delay(1000); left--
                _state.update { it.copy(secondsLeft = left) }
            }
            stop("Session expired. The server was stopped.")
        }
        return null
    }

    private fun listener(count: Int) = object : TransferServer.Listener {
        override fun onClient(ip: String, userAgent: String) {
            _state.update { it.copy(clientIp = ip, clientAgent = describe(userAgent)) }
        }
        override fun onProgress(index: Int, sent: Long) {
            _state.update { it.copy(activeIdx = index, sent = sent, total = it.sizes.getOrElse(index) { 0L }) }
        }
        override fun onFileDone(index: Int) {
            var all = false
            _state.update {
                val done = it.doneIdx + index
                all = done.size >= count
                it.copy(doneIdx = done, activeIdx = null, sent = 0, total = 0, allDone = all)
            }
            if (all) scope.launch { delay(5000); stop("All files downloaded. The server was stopped.") }
        }
        override fun onFileAborted(index: Int) {
            _state.update { if (it.activeIdx == index) it.copy(activeIdx = null, sent = 0, total = 0) else it }
        }
    }

    fun cancelTransfer() {
        server?.cancelActive()
        _state.update { it.copy(activeIdx = null, sent = 0, total = 0, message = "Transfer cancelled.", messageIsError = false) }
    }

    fun stop(message: String? = null) {
        stopInternal()
        _state.update {
            it.copy(running = false, url = null, activeIdx = null, sent = 0, total = 0, secondsLeft = 0,
                message = message ?: "Server stopped.", messageIsError = false)
        }
    }

    fun dismissMessage() { _state.update { it.copy(message = null) } }

    private fun stopInternal() {
        timer?.cancel(); timer = null
        server?.stop(); server = null
    }

    private fun describe(ua: String): String {
        val os = when {
            ua.contains("Windows") -> "Windows"; ua.contains("Mac OS") -> "macOS"; ua.contains("Android") -> "Android"
            ua.contains("iPhone") || ua.contains("iPad") -> "iOS"; ua.contains("Linux") -> "Linux"; else -> "unknown system"
        }
        val br = when {
            ua.contains("Edg/") -> "Edge"; ua.contains("Firefox/") -> "Firefox"; ua.contains("Chrome/") -> "Chrome"
            ua.contains("Safari/") -> "Safari"; else -> "Browser"
        }
        return "$br on $os"
    }
}
