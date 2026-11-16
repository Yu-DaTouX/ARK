package app.ark.share

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class ShareInfo(val running: Boolean = false, val urls: List<String> = emptyList(), val downloads: Int = 0, val idleLeftMin: Int = 30)

/**
 * 离线分享：ServerSocket 手写的极简 HTTP 服务（只有 GET/HEAD，支持 Range 断点续传），无第三方依赖。
 *   /               中文首页（列出可下载的文件）
 *   /apk/ark.apk    本 App 自己的安装包（base.apk）
 *   /pack/<文件名>   资料包      /map/<文件名>   已下载的地图
 * 文件名只和目录列表做“完全相等”匹配，不拼路径，不会读到别的文件。不分享大模型（体积太大）。
 * 30 分钟内没有任何请求（且没有传输在进行）自动关闭。
 */
class ShareServer(private val ctx: Context, private val onIdle: () -> Unit) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val active = AtomicInteger(0)
    @Volatile private var last = System.currentTimeMillis()
    @Volatile private var stopped = false
    private var done = 0

    private class Entry(val path: String, val file: File, val label: String, val mime: String)

    private fun entries(): List<Entry> {
        val app = ctx.applicationContext as app.ark.ArkApp
        val out = ArrayList<Entry>()
        out += Entry("/apk/ark.apk", File(ctx.applicationInfo.sourceDir), "方舟安装包（APK）", "application/vnd.android.package-archive")
        app.storage.packFiles().forEach { out += Entry("/pack/" + enc(it.name), it, "资料包：${it.nameWithoutExtension}", "application/octet-stream") }
        app.storage.mapFiles().forEach { out += Entry("/map/" + enc(it.name), it, "地图：${it.nameWithoutExtension}", "application/octet-stream") }
        return out
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** 返回监听端口；失败抛异常。 */
    fun start(): Int {
        var s: ServerSocket? = null
        for (p in 8080..8099) {
            try { s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(p)) }; break } catch (_: Exception) {}
        }
        val ss = s ?: error("没有可用端口")
        server = ss
        Thread({
            while (!stopped) {
                val c = try { ss.accept() } catch (_: Exception) { break }
                pool.execute { handle(c) }
            }
        }, "ark-share-accept").apply { isDaemon = true }.start()
        Thread({
            while (!stopped) {
                try { Thread.sleep(15_000) } catch (_: InterruptedException) { break }
                if (active.get() > 0) last = System.currentTimeMillis()
                else if (System.currentTimeMillis() - last > IDLE_MS) { onIdle(); break }
                publish()
            }
        }, "ark-share-idle").apply { isDaemon = true }.start()
        port = ss.localPort
        publish()
        return ss.localPort
    }

    private var port = 0

    fun publish() {
        val left = ((IDLE_MS - (System.currentTimeMillis() - last)) / 60_000L).toInt().coerceIn(0, 30) + 1
        state.value = ShareInfo(!stopped, addresses().map { "http://$it:$port/" }, done, minOf(left, 30))
    }

    fun stop() {
        stopped = true
        runCatching { server?.close() }
        pool.shutdownNow()
        state.value = ShareInfo()
    }

    private fun handle(sock: Socket) {
        sock.use {
            try {
                sock.soTimeout = 30_000
                last = System.currentTimeMillis()
                val inp = sock.getInputStream().buffered()
                val line = readLine(inp) ?: return
                var range: String? = null
                while (true) { val h = readLine(inp) ?: break; if (h.isEmpty()) break; if (h.startsWith("Range:", true)) range = h.substring(6).trim() }
                val parts = line.split(' ')
                val out = sock.getOutputStream().buffered()
                if (parts.size < 2 || (parts[0] != "GET" && parts[0] != "HEAD")) { simple(out, 405, "不支持的请求"); return }
                val path = URLDecoder.decode(parts[1].substringBefore('?').replace("+", "%2B"), "UTF-8")
                val head = parts[0] == "HEAD"
                if (path == "/") { page(out, head); return }
                val all = entries()
                val e = all.firstOrNull { URLDecoder.decode(it.path.replace("+", "%2B"), "UTF-8") == path && it.file.isFile }
                if (e == null) { simple(out, 404, "没有这个文件"); return }
                sendFile(out, e, range, head)
            } catch (_: Exception) { }
        }
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = i.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return null
    }

    private fun simple(out: OutputStream, code: Int, msg: String) {
        val b = msg.toByteArray()
        out.write("HTTP/1.1 $code X\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b); out.flush()
    }

    private fun sizeText(b: Long) = if (b >= 1 shl 20) "%.1f MB".format(b / 1048576.0) else "%.0f KB".format(b / 1024.0)

    private fun page(out: OutputStream, head: Boolean) {
        val items = entries().joinToString("") { e ->
            "<a class=f href=\"${e.path}\"><b>${esc(e.label)}</b><span>${sizeText(e.file.length())}</span></a>"
        }
        val html = """<!doctype html><html lang="zh"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>方舟 ARK · 离线分享</title><style>body{font-family:sans-serif;margin:0;padding:16px;background:#f4f4f2;color:#222;max-width:560px;margin:auto}
h1{font-size:22px}a.f{display:flex;justify-content:space-between;gap:8px;background:#fff;border-radius:12px;padding:14px;margin:8px 0;text-decoration:none;color:#0b5cad;box-shadow:0 1px 2px #0002}
a.f span{color:#666}p{color:#555;font-size:14px;line-height:1.6}</style></head><body>
<h1>方舟 ARK · 离线求生手册</h1><p>这是附近的朋友通过本地网络分享给你的。不需要联网。</p>
$items
<p><b>安装方法：</b>下载「方舟安装包」后点开安装；如果提示“禁止安装未知来源应用”，在弹出的设置里允许当前浏览器安装即可。
资料包和地图下载后，在方舟 App 的「资料」页点「从手机文件导入」选择它们。</p>
<p>大文件请保持两部手机距离近、屏幕不要锁，下载中断可以重新点击继续。</p></body></html>"""
        val b = html.toByteArray()
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        if (!head) out.write(b)
        out.flush()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun sendFile(out: OutputStream, e: Entry, range: String?, head: Boolean) {
        val len = e.file.length()
        var from = 0L; var to = len - 1
        var partial = false
        range?.takeIf { it.startsWith("bytes=") }?.removePrefix("bytes=")?.substringBefore(',')?.let { r ->
            val a = r.substringBefore('-').trim(); val b = r.substringAfter('-').trim()
            if (a.isEmpty()) { b.toLongOrNull()?.let { from = maxOf(0, len - it); partial = true } }
            else a.toLongOrNull()?.let { from = it; to = b.toLongOrNull()?.let { x -> minOf(x, len - 1) } ?: (len - 1); partial = true }
        }
        if (from > to || from >= len) {
            out.write("HTTP/1.1 416 X\r\nContent-Range: bytes */$len\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
        }
        val name = enc(e.file.name)
        val hdr = StringBuilder(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            .append("Content-Type: ${e.mime}\r\nAccept-Ranges: bytes\r\nContent-Length: ${to - from + 1}\r\n")
            .append("Content-Disposition: attachment; filename=\"${e.file.name.filter { it.code in 32..126 && it != '"' }.ifBlank { "ark" }}\"; filename*=UTF-8''$name\r\n")
        if (partial) hdr.append("Content-Range: bytes $from-$to/$len\r\n")
        hdr.append("Connection: close\r\n\r\n")
        out.write(hdr.toString().toByteArray())
        if (head) { out.flush(); return }
        active.incrementAndGet()
        var ok = false
        try {
            java.io.RandomAccessFile(e.file, "r").use { f ->
                f.seek(from)
                val buf = ByteArray(1 shl 16)
                var left = to - from + 1
                while (left > 0 && !stopped) {
                    val n = f.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                    last = System.currentTimeMillis()
                }
                out.flush()
                ok = left == 0L
            }
        } finally {
            active.decrementAndGet()
            last = System.currentTimeMillis()
            if (ok) { done++; publish() }
        }
    }

    companion object {
        const val IDLE_MS = 30 * 60_000L
        val state = MutableStateFlow(ShareInfo())

        /** 本机局域网 / 热点 IPv4 地址（排除移动数据、VPN 等别人连不上的接口）。热点开启后一般是 192.168.x.1。 */
        fun addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { ni ->
                ni.isUp && !ni.isLoopback && listOf("rmnet", "ccmni", "tun", "v4-", "dummy", "ppp").none { ni.name.startsWith(it) }
            }.flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && !it.isLinkLocalAddress } }
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
    }
}
