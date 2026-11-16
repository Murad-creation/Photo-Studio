package com.smartscan.photostudio.core.transfer

import android.net.Uri
import com.smartscan.photostudio.core.ShareUtil
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ServedItem(val id: Int, val name: String, val file: File, val size: Long, val delivered: Boolean)

/**
 * Small HTTP server for the permanent PC receiver. It only answers under /<token>/ and serves only
 * the files the phone has put in its outbox. Everything else is a 404.
 *
 *   GET /<token>/            the PC page (keeps polling and downloads new files automatically)
 *   GET /<token>/api/list    outbox as JSON (also tells the phone that a PC is online)
 *   GET /<token>/f/<id>      one file
 */
class TransferServer(private val token: String, private val host: Host) {
    interface Host {
        fun items(): List<ServedItem>
        fun onPoll(ip: String, userAgent: String)
        fun onProgress(id: Int, sent: Long)
        fun onDone(id: Int)
        fun onAborted(id: Int)
    }

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "transfer-worker").apply { isDaemon = true } }
    @Volatile private var running = false
    private val cancelGen = AtomicInteger(0)

    private fun openSocket(port: Int): ServerSocket {
        val s = ServerSocket()
        return try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(port), 50)
            s
        } catch (e: IOException) {
            runCatching { s.close() }
            ServerSocket(0, 50)
        }
    }

    /** Binds the preferred port (so the address stays the same); falls back to any free port. */
    fun start(preferredPort: Int): Int {
        val s = openSocket(preferredPort)
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
                s.soTimeout = 20_000
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

                // First path segment must equal the secret token (constant-time compare).
                val segs = path.removePrefix("/").split("/", limit = 2)
                val given = segs[0]
                if (!MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) { text(out, 404, "Not Found", "Not found"); return }
                val rest = if (segs.size > 1) "/" + segs[1] else ""
                val ip = s.inetAddress?.hostAddress ?: "unknown"
                val ua = headers["user-agent"] ?: ""
                when {
                    rest.isEmpty() -> respond(out, 302, "Found", "text/plain", ByteArray(0), "Location: /$token/\r\n")
                    rest == "/" -> { host.onPoll(ip, ua); respond(out, 200, "OK", "text/html; charset=utf-8", page().toByteArray(Charsets.UTF_8)) }
                    rest == "/api/list" -> {
                        host.onPoll(ip, ua)
                        respond(out, 200, "OK", "application/json; charset=utf-8", json(host.items()).toByteArray(Charsets.UTF_8))
                    }
                    rest.startsWith("/f/") -> {
                        val id = rest.removePrefix("/f/").toIntOrNull()
                        val item = host.items().firstOrNull { it.id == id }
                        if (item == null || !item.file.isFile) text(out, 404, "Not Found", "Not found")
                        else { host.onPoll(ip, ua); stream(out, item, method == "HEAD") }
                    }
                    else -> text(out, 404, "Not Found", "Not found")
                }
            } catch (e: Exception) {
                // client went away or timed out
            }
        }
    }

    private fun stream(out: OutputStream, item: ServedItem, headOnly: Boolean) {
        val size = item.file.length()
        val gen = cancelGen.get()
        val header = "HTTP/1.1 200 OK\r\nContent-Type: ${ShareUtil.mimeOf(item.file)}\r\nContent-Length: $size\r\n" +
            "Content-Disposition: attachment; filename=\"${asciiName(item.name)}\"; filename*=UTF-8''${Uri.encode(item.name)}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        if (headOnly) { out.flush(); return }
        var sent = 0L
        var reported = 0L
        try {
            item.file.inputStream().use {
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (cancelGen.get() != gen || !running) { host.onAborted(item.id); return }
                    val r = it.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    sent += r
                    if (sent - reported >= 256 * 1024) { reported = sent; host.onProgress(item.id, sent) }
                }
            }
            out.flush()
            if (sent >= size) host.onDone(item.id) else host.onAborted(item.id)
        } catch (e: IOException) {
            host.onAborted(item.id)
            throw e
        }
    }

    private fun json(items: List<ServedItem>): String =
        "{\"items\":[" + items.joinToString(",") {
            "{\"id\":${it.id},\"name\":${q(it.name)},\"size\":${it.size},\"delivered\":${it.delivered}}"
        } + "]}"

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ") + "\""

    private fun page(): String = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Photo Studio to PC</title>
<style>body{font-family:system-ui,sans-serif;background:#f5f8f6;color:#172820;margin:0;padding:24px}main{max-width:620px;margin:auto}h1{font-size:22px;margin:0 0 8px}.st{padding:10px 14px;border-radius:12px;background:#e6efe9;margin:12px 0}.st.ok{background:#d9f0e2}.st.bad{background:#fbe0e0}.opt{display:block;margin:8px 0 16px;font-size:14px}ul{list-style:none;padding:0}li{background:#fff;border-radius:16px;padding:16px;margin:12px 0;display:flex;align-items:center;gap:12px;box-shadow:0 1px 3px #0001}.n{flex:1;word-break:break-all}.n span{display:block;color:#5b6f64;font-size:13px}.b{background:#087c43;color:#fff;text-decoration:none;padding:10px 16px;border-radius:12px;font-weight:600}.hint{color:#5b6f64;font-size:13px}</style></head>
<body><main><h1>Photo Studio</h1>
<div id="st" class="st">Connecting to your phone...</div>
<label class="opt"><input type="checkbox" id="auto" checked> Download new files automatically</label>
<ul id="list"></ul>
<p class="hint">Keep this page open or bookmark it. Files you send from the phone arrive here by themselves. Your browser may ask once to allow multiple downloads.</p>
</main>
<script>
var base = location.pathname.replace(/\/+$/, '');
var requested = {}; var queue = []; var busy = false;
function fmt(n){ return n > 1048576 ? (n/1048576).toFixed(2) + ' MB' : Math.max(1, Math.round(n/1024)) + ' KB'; }
function startDownload(it){
  var a = document.createElement('a'); a.href = base + '/f/' + it.id; a.download = it.name; a.style.display = 'none';
  document.body.appendChild(a); a.click(); document.body.removeChild(a);
}
function pump(){
  if (busy || !queue.length) return; busy = true;
  startDownload(queue.shift());
  setTimeout(function(){ busy = false; pump(); }, 800);
}
function render(items){
  var ul = document.getElementById('list'); ul.innerHTML = '';
  items.slice().reverse().forEach(function(it){
    var li = document.createElement('li');
    var n = document.createElement('div'); n.className = 'n'; n.textContent = it.name;
    var sp = document.createElement('span'); sp.textContent = fmt(it.size) + (it.delivered ? ' - received' : ' - waiting'); n.appendChild(sp);
    var a = document.createElement('a'); a.className = 'b'; a.href = base + '/f/' + it.id; a.textContent = 'Download'; a.setAttribute('download', it.name);
    li.appendChild(n); li.appendChild(a); ul.appendChild(li);
  });
}
function tick(){
  fetch(base + '/api/list', {cache: 'no-store'}).then(function(r){ return r.json(); }).then(function(j){
    var st = document.getElementById('st');
    st.textContent = j.items.length ? 'Connected. Files from your phone:' : 'Connected. Waiting for files from your phone...';
    st.className = 'st ok';
    render(j.items);
    if (document.getElementById('auto').checked) {
      j.items.forEach(function(it){ if (!it.delivered && !requested[it.id]) { requested[it.id] = true; queue.push(it); } });
      pump();
    }
  }).catch(function(){
    var st = document.getElementById('st'); st.textContent = 'Cannot reach the phone. Retrying...'; st.className = 'st bad';
  }).then(function(){ setTimeout(tick, 1500); });
}
tick();
</script></body></html>"""

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
