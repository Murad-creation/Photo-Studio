package com.smartscan.photostudio.core.transfer

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class TransferItem(val uri: Uri, val name: String, val size: Long, val mime: String)

/**
 * Tiny temporary HTTP server. It only ever serves the files in [items], only under /<token>/,
 * and everything else answers 404. Stop it with [stop].
 */
class TransferServer(
    private val context: Context,
    private val items: List<TransferItem>,
    private val token: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onClient(ip: String, userAgent: String)
        fun onProgress(index: Int, sent: Long)
        fun onFileDone(index: Int)
        fun onFileAborted(index: Int)
    }

    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(6)
    @Volatile private var running = false
    private val cancelGen = AtomicInteger(0)

    fun start(bindIp: String? = null): Int {
        val s = if (bindIp != null) ServerSocket(0, 20, InetAddress.getByName(bindIp)) else ServerSocket(0, 20)
        server = s; running = true
        thread(name = "transfer-accept", isDaemon = true) {
            while (running) {
                val c = try { s.accept() } catch (e: Exception) { break }
                try { pool.execute { handle(c) } } catch (e: Exception) { runCatching { c.close() } }
            }
        }
        return s.localPort
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        pool.shutdownNow()
    }

    /** Aborts downloads that are in progress; the server keeps running. */
    fun cancelActive() { cancelGen.incrementAndGet() }

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 15_000
                val input = BufferedInputStream(s.getInputStream())
                val reqLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                var n = 0
                while (true) {
                    val l = readLine(input) ?: break
                    if (l.isEmpty()) break
                    if (++n > 50) return
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase(Locale.US)] = l.substring(i + 1).trim()
                }
                val parts = reqLine.split(" ")
                if (parts.size < 3) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                val out = s.getOutputStream()
                if (method != "GET" && method != "HEAD") { text(out, 405, "Method Not Allowed", "Not allowed"); return }
                val prefix = "/$token"
                if (path != prefix && !path.startsWith("$prefix/")) { text(out, 404, "Not Found", "Not found"); return }
                val rest = path.removePrefix(prefix)
                val ip = s.inetAddress?.hostAddress ?: "unknown"
                val ua = headers["user-agent"] ?: ""
                when {
                    rest.isEmpty() -> respond(out, 302, "Found", "text/plain", ByteArray(0), "Location: $prefix/\r\n")
                    rest == "/" -> { listener.onClient(ip, ua); respond(out, 200, "OK", "text/html; charset=utf-8", page().toByteArray()) }
                    rest.startsWith("/f/") -> {
                        val idx = rest.removePrefix("/f/").toIntOrNull()
                        if (idx == null || idx !in items.indices) text(out, 404, "Not Found", "Not found")
                        else { listener.onClient(ip, ua); stream(out, idx, method == "HEAD") }
                    }
                    else -> text(out, 404, "Not Found", "Not found")
                }
            } catch (e: Exception) {
                // client disconnected or timed out
            }
        }
    }

    private fun stream(out: OutputStream, idx: Int, headOnly: Boolean) {
        val item = items[idx]
        val gen = cancelGen.get()
        val header = "HTTP/1.1 200 OK\r\nContent-Type: ${item.mime}\r\nContent-Length: ${item.size}\r\n" +
            "Content-Disposition: attachment; filename=\"${asciiName(item.name)}\"; filename*=UTF-8''${Uri.encode(item.name)}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        if (headOnly) { out.flush(); return }
        val ins = context.contentResolver.openInputStream(item.uri) ?: throw IOException("Cannot open ${item.name}")
        var sent = 0L
        try {
            ins.use {
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (cancelGen.get() != gen || !running) { listener.onFileAborted(idx); return }
                    val r = it.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    sent += r
                    listener.onProgress(idx, sent)
                }
            }
            out.flush()
            if (sent >= item.size) listener.onFileDone(idx) else listener.onFileAborted(idx)
        } catch (e: IOException) {
            listener.onFileAborted(idx)
            throw e
        }
    }

    private fun page(): String {
        val rows = items.mapIndexed { i, it ->
            """<li><div class="n">${esc(it.name)}<span>${fmt(it.size)}</span></div><a class="b" href="f/$i">Download</a></li>"""
        }.joinToString("")
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Photo Studio Transfer</title>
<style>body{font-family:system-ui,sans-serif;background:#f5f8f6;color:#172820;margin:0;padding:24px}main{max-width:560px;margin:auto}h1{font-size:22px}ul{list-style:none;padding:0}li{background:#fff;border-radius:16px;padding:16px;margin:12px 0;display:flex;align-items:center;gap:12px;box-shadow:0 1px 3px #0001}.n{flex:1;word-break:break-all}.n span{display:block;color:#5b6f64;font-size:13px}.b{background:#087c43;color:#fff;text-decoration:none;padding:12px 18px;border-radius:12px;font-weight:600}</style></head>
<body><main><h1>Photo Studio</h1><p>Files shared from your phone. This link works only while the transfer session is active.</p><ul>$rows</ul></main></body></html>"""
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun fmt(b: Long) = String.format(Locale.US, "%.2f MB", b / 1_048_576.0)
    private fun asciiName(n: String) = n.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")

    private fun text(out: OutputStream, code: Int, reason: String, body: String) =
        respond(out, code, reason, "text/plain; charset=utf-8", body.toByteArray())

    private fun respond(out: OutputStream, code: Int, reason: String, type: String, body: ByteArray, extra: String = "") {
        out.write("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n$extra\r\n".toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = ins.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }
}
