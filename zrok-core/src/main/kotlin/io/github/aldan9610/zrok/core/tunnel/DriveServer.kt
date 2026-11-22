package io.github.aldan9610.zrok.core.tunnel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

// Plano de datos del backend drive: sirve un directorio local como WebDAV sobre
// el stream ziti del share. Para un share publico, el frontend publico de zrok
// termina HTTPS y reenvía el HTTP plano por ziti hasta aquí; para un share
// privado, un access TCP entrega el stream. En ambos casos este host recibe
// HTTP y responde WebDAV.
//
// Implementación mínima pero funcional de WebDAV (RFC 4918 subset): OPTIONS,
// PROPFIND (Depth 0/1/infinity→1), GET, HEAD, PUT, DELETE, MKCOL, MOVE, COPY.
// Suficiente para montar la unidad desde el Explorador de Windows, Cyberduck,
// rclone o Finder. No implementa bloqueos (WebDAV LOCK/UNLOCK): los clientes
// caen a un modo sin bloqueos.

private val HTTP_DATE = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("GMT")
}

private fun httpDate(date: Long): String = HTTP_DATE.format(Date(date))

/**
 * Lado host de un share `drive`: acepta conexiones ziti y sirve el directorio
 * [rootPath] como un servidor WebDAV sobre el stream. Cada conexión HTTP se
 * atiende con keep-alive hasta que el cliente la cierre.
 *
 * @param server canal de escucha ziti del share.
 * @param rootPath directorio local que se expone como raíz WebDAV.
 * @param parentScope ámbito de corrutinas.
 */
class ZrokDriveServer internal constructor(
    private val server: AsynchronousServerSocketChannel,
    private val rootPath: File,
    parentScope: CoroutineScope,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val connections = CopyOnWriteArraySet<AsynchronousSocketChannel>()

    private val acceptJob = scope.launch {
        while (isActive && !closed.get()) {
            val zitiChannel = try {
                server.accept().get() ?: break
            } catch (_: Exception) {
                break
            }
            if (!closed.get()) {
                connections.add(zitiChannel)
                scope.launch { serve(zitiChannel) }
            } else {
                runCatching { zitiChannel.close() }
            }
        }
    }

    private fun serve(channel: AsynchronousSocketChannel) {
        val input = AsyncChannelInputStream(channel)
        val output = AsyncChannelOutputStream(channel)
        try {
            // Una petición HTTP por conexión ziti (estilo HTTP/1.0). Los clientes
            // WebDAV reconectan para cada operación; es más simple y robusto que
            // implementar keep-alive sobre el stream ziti.
            val request = parseRequest(input)
            if (request != null) handle(request, output)
            output.flush()
        } catch (_: IOException) {
            // Conexión cerrada por el cliente o por la red: fin normal.
        } catch (_: Exception) {
            // Cualquier otro fallo termina la conexion.
        } finally {
            connections.remove(channel)
            runCatching { channel.close() }
        }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val close: Boolean,
        val body: ByteArray,
    )

    private fun parseRequest(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 3) return null
        val method = parts[0].uppercase()
        val rawPath = parts[1]
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var off = 0
            while (off < contentLength) {
                val n = input.read(buf, off, contentLength - off)
                if (n < 0) return null
                off += n
            }
            buf
        } else ByteArray(0)
        val close = headers["connection"]?.lowercase()?.contains("close") == true
            || headers["connection"]?.lowercase()?.contains("keep-alive") != true
        return HttpRequest(method, rawPath, headers, close, body)
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.isEmpty()) null else String(bytes.map { it.toInt().toByte() }.toByteArray())
            if (b == '\r'.code) {
                val next = input.read()
                // Consumir '\n' si viene; ignorar.
                if (next != '\n'.code && next >= 0) bytes.add(next.toByte())
                return String(bytes.map { it.toInt().toByte() }.toByteArray(), Charsets.UTF_8)
            }
            if (b == '\n'.code) {
                return String(bytes.map { it.toInt().toByte() }.toByteArray(), Charsets.UTF_8)
            }
            bytes.add(b.toByte())
        }
    }

    private fun handle(req: HttpRequest, output: OutputStream) {
        // El path HTTP puede venir sin decodificar (%20 etc.); se decodifica.
        val decoded = try { java.net.URLDecoder.decode(req.path.substringBefore('?'), "UTF-8") } catch (_: Exception) { req.path }
        val safe = File(rootPath, decoded).canonicalFile
        // Protección contra path traversal: el recurso debe quedar bajo root.
        val rootCanonical = rootPath.canonicalFile
        val insideRoot = safe.path == rootCanonical.path || safe.path.startsWith(rootCanonical.path + File.separator)
        if (!insideRoot) {
            writeResponse(output, 403, "Forbidden", body = "Path outside root".toByteArray())
            return
        }
        when (req.method) {
            "OPTIONS" -> writeOptions(output)
            "GET" -> serveGet(output, safe, head = false)
            "HEAD" -> serveGet(output, safe, head = true)
            "PUT" -> servePut(output, safe, req.body)
            "DELETE" -> serveDelete(output, safe)
            "MKCOL" -> serveMkcol(output, safe)
            "MOVE" -> serveMoveCopy(output, safe, req, copy = false)
            "COPY" -> serveMoveCopy(output, safe, req, copy = true)
            "PROPFIND" -> servePropfind(output, safe, req)
            else -> writeResponse(output, 405, "Method Not Allowed", body = ByteArray(0))
        }
    }

    private fun writeOptions(output: OutputStream) {
        val head = StringBuilder()
            .append("HTTP/1.1 200 OK\r\n")
            .append("DAV: 1, 2\r\n")
            .append("Allow: OPTIONS, PROPFIND, GET, HEAD, PUT, DELETE, MKCOL, MOVE, COPY\r\n")
            .append("MS-Author-Via: DAV\r\n")
            .append("Content-Length: 0\r\n")
            .append("Connection: close\r\n")
            .append("\r\n")
            .toString()
        output.write(head.toByteArray())
    }

    private fun serveGet(output: OutputStream, file: File, head: Boolean) {
        if (!file.isFile) {
            writeResponse(output, if (file.isDirectory) 405 else 404, if (file.isDirectory) "Is a directory" else "Not Found", ByteArray(0))
            return
        }
        val bytes = file.readBytes()
        val headStr = StringBuilder()
            .append("HTTP/1.1 200 OK\r\n")
            .append("Content-Type: ${contentType(file)}\r\n")
            .append("Content-Length: ${bytes.size}\r\n")
            .append("Last-Modified: ${httpDate(file.lastModified())}\r\n")
            .append("ETag: \"${file.name}-${file.lastModified()}-${file.length()}\"\r\n")
            .append("Accept-Ranges: none\r\n")
            .append("Connection: close\r\n")
            .append("\r\n")
            .toString()
        output.write(headStr.toByteArray())
        if (!head) output.write(bytes)
    }

    private fun servePut(output: OutputStream, file: File, body: ByteArray) {
        if (file.isDirectory) { writeResponse(output, 405, "Is a directory", ByteArray(0)); return }
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        try {
            file.writeBytes(body)
            writeResponse(output, if (file.length() == body.size.toLong()) 201 else 204,
                if (file.length() == body.size.toLong()) "Created" else "No Content", ByteArray(0))
        } catch (_: Exception) {
            writeResponse(output, 500, "Internal Server Error", ByteArray(0))
        }
    }

    private fun serveDelete(output: OutputStream, file: File) {
        if (!file.exists()) { writeResponse(output, 404, "Not Found", ByteArray(0)); return }
        try {
            val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
            writeResponse(output, if (ok) 204 else 500, if (ok) "No Content" else "Delete failed", ByteArray(0))
        } catch (_: Exception) {
            writeResponse(output, 500, "Internal Server Error", ByteArray(0))
        }
    }

    private fun serveMkcol(output: OutputStream, file: File) {
        if (file.exists()) { writeResponse(output, 405, "Method Not Allowed", ByteArray(0)); return }
        try {
            if (file.mkdirs()) writeResponse(output, 201, "Created", ByteArray(0))
            else writeResponse(output, 409, "Conflict", ByteArray(0))
        } catch (_: Exception) {
            writeResponse(output, 500, "Internal Server Error", ByteArray(0))
        }
    }

    private fun serveMoveCopy(output: OutputStream, src: File, req: HttpRequest, copy: Boolean) {
        val destHeader = req.headers["destination"] ?: run {
            writeResponse(output, 400, "Bad Request", ByteArray(0)); return
        }
        val destPath = try {
            java.net.URI(destHeader).path
        } catch (_: Exception) { destHeader }
        val decoded = try { java.net.URLDecoder.decode(destPath.substringBefore('?'), "UTF-8") } catch (_: Exception) { destPath }
        val dest = File(rootPath, decoded).canonicalFile
        val rootCanonical = rootPath.canonicalFile
        if (!(dest.path == rootCanonical.path || dest.path.startsWith(rootCanonical.path + File.separator))) {
            writeResponse(output, 403, "Forbidden", ByteArray(0)); return
        }
        if (!src.exists()) { writeResponse(output, 404, "Not Found", ByteArray(0)); return }
        try {
            if (copy) src.copyRecursively(dest, overwrite = true)
            else src.copyRecursively(dest, overwrite = true) && src.deleteRecursively()
            writeResponse(output, 201, "Created", ByteArray(0))
        } catch (_: Exception) {
            writeResponse(output, 500, "Internal Server Error", ByteArray(0))
        }
    }

    private fun servePropfind(output: OutputStream, target: File, req: HttpRequest) {
        if (!target.exists()) { writeResponse(output, 404, "Not Found", ByteArray(0)); return }
        val depth = req.headers["depth"]?.lowercase() ?: "infinity"
        val listChildren = depth != "0"
        val entries = ArrayList<File>()
        entries.add(target)
        if (listChildren && target.isDirectory) target.listFiles()?.toList()?.let { entries.addAll(it) }
        val xml = buildPropfindXml(entries)
        val bytes = xml.toByteArray(Charsets.UTF_8)
        val headStr = StringBuilder()
            .append("HTTP/1.1 207 Multi-Status\r\n")
            .append("Content-Type: application/xml; charset=utf-8\r\n")
            .append("Content-Length: ${bytes.size}\r\n")
            .append("Connection: close\r\n")
            .append("\r\n")
            .toString()
        output.write(headStr.toByteArray())
        output.write(bytes)
    }

    private fun buildPropfindXml(entries: List<File>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        sb.append("<D:multistatus xmlns:D=\"DAV:\">")
        for (f in entries) {
            val href = relativeHref(f)
            val isDir = f.isDirectory
            sb.append("<D:response>")
            sb.append("<D:href>$href</D:href>")
            sb.append("<D:propstat>")
            sb.append("<D:prop>")
            sb.append("<D:resourcetype>${if (isDir) "<D:collection/>" else ""}</D:resourcetype>")
            sb.append("<D:displayname>${escapeXml(f.name)}</D:displayname>")
            if (!isDir) {
                sb.append("<D:getcontentlength>${f.length()}</D:getcontentlength>")
                sb.append("<D:getcontenttype>${contentType(f)}</D:getcontenttype>")
            }
            sb.append("<D:getlastmodified>${httpDate(f.lastModified())}</D:getlastmodified>")
            sb.append("<D:getetag>\"${f.name}-${f.lastModified()}-${f.length()}\"</D:getetag>")
            sb.append("</D:prop>")
            sb.append("<D:status>HTTP/1.1 200 OK</D:status>")
            sb.append("</D:propstat>")
            sb.append("</D:response>")
        }
        sb.append("</D:multistatus>")
        return sb.toString()
    }

    private fun relativeHref(f: File): String {
        val rel = rootPath.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/')
        val encoded = rel.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "/" + encoded
    }

    private fun contentType(file: File): String {
        if (file.isDirectory) return "httpd/unix-directory"
        val name = file.name.lowercase()
        return when {
            name.endsWith(".html") || name.endsWith(".htm") -> "text/html; charset=utf-8"
            name.endsWith(".txt") || name.endsWith(".md") -> "text/plain; charset=utf-8"
            name.endsWith(".png") -> "image/png"
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".json") -> "application/json"
            name.endsWith(".zip") -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun writeResponse(output: OutputStream, code: Int, reason: String, body: ByteArray) {
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray())
        if (body.isNotEmpty()) output.write(body)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        acceptJob.cancel()
        runCatching { server.close() }
        connections.forEach { runCatching { it.close() } }
        connections.clear()
    }
}
