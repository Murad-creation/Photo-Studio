package com.smartscan.photostudio.core.transfer

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.smartscan.photostudio.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class OutboxItem(
    val id: Int,
    val name: String,
    val file: File,
    val size: Long,
    val sent: Long = 0,
    val delivered: Boolean = false,
    val active: Boolean = false,
)

data class TransferUiState(
    val lan: LanInfo = LanInfo(false, null),
    val running: Boolean = false,
    val starting: Boolean = false,
    val url: String? = null,
    val portNote: String? = null,
    val items: List<OutboxItem> = emptyList(),
    val pcOnline: Boolean = false,
    val pcIp: String? = null,
    val pcAgent: String? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

/**
 * The PC receiver. It keeps one permanent address (stable port + secret token kept in settings).
 * A PC that has the page open polls it; every file the phone sends is downloaded there automatically.
 */
object TransferManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(TransferUiState())
    val state: StateFlow<TransferUiState> = _state.asStateFlow()
    private var server: TransferServer? = null
    private var monitor: Job? = null
    private var appCtx: Context? = null
    private val nextId = AtomicInteger(1)
    @Volatile private var lastPoll = 0L
    @Volatile private var lastActivity = System.currentTimeMillis()
    @Volatile private var token = ""
    @Volatile private var port = 0
    @Volatile private var idleHours = 0

    private fun urlFor(ip: String, p: Int) = "http://$ip:$p/$token/"

    private val host = object : TransferServer.Host {
        override fun items() = _state.value.items.map { ServedItem(it.id, it.name, it.file, it.size, it.delivered) }

        override fun onPoll(ip: String, userAgent: String) {
            val now = System.currentTimeMillis(); lastPoll = now; lastActivity = now
            val agent = describe(userAgent)
            _state.update { if (it.pcIp == ip && it.pcAgent == agent && it.pcOnline) it else it.copy(pcIp = ip, pcAgent = agent, pcOnline = true) }
        }

        override fun onProgress(id: Int, sent: Long) {
            lastActivity = System.currentTimeMillis()
            _state.update { s -> s.copy(items = s.items.map { if (it.id == id) it.copy(sent = sent, active = true) else it }) }
        }

        override fun onDone(id: Int) {
            lastActivity = System.currentTimeMillis()
            _state.update { s ->
                val name = s.items.firstOrNull { it.id == id }?.name ?: "file"
                s.copy(
                    items = s.items.map { if (it.id == id) it.copy(delivered = true, active = false, sent = it.size) else it },
                    message = "Received on the PC: $name", messageIsError = false,
                )
            }
        }

        override fun onAborted(id: Int) {
            _state.update { s -> s.copy(items = s.items.map { if (it.id == id && !it.delivered) it.copy(active = false, sent = 0) else it }) }
        }
    }

    fun refreshNetwork(ctx: Context) { _state.update { it.copy(lan = NetInfo.current(ctx)) } }

    /** Puts files in the outbox. Starts the receiver if it is off. */
    fun send(ctx: Context, files: List<File>) {
        val app = ctx.applicationContext; appCtx = app
        val usable = files.filter { it.isFile && it.canRead() }
        if (usable.isEmpty()) {
            _state.update { it.copy(message = "The selected files could not be read.", messageIsError = true) }
            return
        }
        lastActivity = System.currentTimeMillis()
        _state.update { s ->
            val waiting = s.items.filter { !it.delivered }.map { it.file.path }.toSet()
            val add = usable.filter { it.path !in waiting }.map { OutboxItem(nextId.getAndIncrement(), it.name, it, it.length()) }
            s.copy(items = s.items + add, message = null, messageIsError = false)
        }
        if (!_state.value.running) start(app)
    }

    fun start(ctx: Context) {
        val app = ctx.applicationContext; appCtx = app
        if (_state.value.running || _state.value.starting) return
        _state.update { it.copy(starting = true, message = null, messageIsError = false) }
        scope.launch {
            val repo = SettingsRepository(app)
            val settings = repo.settings.first()
            val lan = NetInfo.current(app)
            val ip = lan.ip
            if (!lan.connected || ip == null) {
                _state.update {
                    it.copy(starting = false, lan = lan, messageIsError = true,
                        message = "Not connected to Wi-Fi. Connect to the same network as your PC, then turn the receiver on.")
                }
                return@launch
            }
            token = repo.ensureTransferToken()
            idleHours = settings.idleStopHours
            val srv = TransferServer(token, host)
            val p = try { srv.start(settings.transferPort) } catch (e: Exception) {
                _state.update { it.copy(starting = false, message = "Could not start the receiver: ${e.message}", messageIsError = true) }
                return@launch
            }
            server = srv; port = p
            val note = if (p != settings.transferPort)
                "Port ${settings.transferPort} was busy, so port $p is used. Open the new address on the PC." else null
            runCatching { ContextCompat.startForegroundService(app, Intent(app, TransferService::class.java)) }
            repo.setReceiverOn(true)
            lastActivity = System.currentTimeMillis()
            _state.update { it.copy(starting = false, running = true, lan = lan, url = urlFor(ip, p), portNote = note) }
            monitor = scope.launch { monitorLoop(app, repo) }
        }
    }

    private suspend fun monitorLoop(app: Context, repo: SettingsRepository) {
        var tick = 0
        while (currentCoroutineContext().isActive) {
            delay(1000); tick++
            val now = System.currentTimeMillis()
            val online = now - lastPoll < 6000
            if (tick % 3 == 0) {
                val lan = NetInfo.current(app)
                _state.update { s ->
                    val ip = lan.ip
                    s.copy(
                        lan = lan, pcOnline = online,
                        url = if (ip != null) urlFor(ip, port) else s.url,
                        message = when {
                            !lan.connected -> "Wi-Fi disconnected. The receiver stays on and works again when you reconnect (the address may change)."
                            s.message?.startsWith("Wi-Fi disconnected") == true -> null
                            else -> s.message
                        },
                        messageIsError = if (!lan.connected) true else s.messageIsError,
                    )
                }
            } else {
                _state.update { if (it.pcOnline == online) it else it.copy(pcOnline = online) }
            }
            if (tick % 30 == 0) idleHours = repo.settings.first().idleStopHours
            if (idleHours > 0 && !online && now - lastActivity > idleHours * 3_600_000L) {
                stop("Receiver stopped after being idle.")
                return
            }
        }
    }

    fun stop(message: String? = "Receiver stopped.") {
        monitor?.cancel(); monitor = null
        server?.stop(); server = null
        appCtx?.let { c ->
            runCatching { c.stopService(Intent(c, TransferService::class.java)) }
            scope.launch { SettingsRepository(c).setReceiverOn(false) }
        }
        _state.update {
            it.copy(
                running = false, starting = false, url = null, pcOnline = false, pcIp = null, pcAgent = null,
                message = message, messageIsError = false,
                items = it.items.map { i -> i.copy(active = false, sent = if (i.delivered) i.size else 0) },
            )
        }
    }

    fun cancelTransfer() {
        server?.cancelActive()
        _state.update { s ->
            s.copy(items = s.items.map { if (!it.delivered) it.copy(active = false, sent = 0) else it },
                message = "Transfer cancelled.", messageIsError = false)
        }
    }

    /** New secret link. The old address stops working; the PC must open the new one once. */
    fun resetAddress(ctx: Context) {
        val app = ctx.applicationContext
        val wasRunning = _state.value.running
        stop("Address reset. Open the new address on the PC.")
        scope.launch {
            SettingsRepository(app).resetTransferToken()
            if (wasRunning) { delay(400); start(app) }
        }
    }

    fun removeItem(id: Int) { _state.update { s -> s.copy(items = s.items.filter { it.id != id }) } }
    fun clearFinished() { _state.update { s -> s.copy(items = s.items.filter { !it.delivered }) } }
    fun dismissMessage() { _state.update { it.copy(message = null) } }

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
