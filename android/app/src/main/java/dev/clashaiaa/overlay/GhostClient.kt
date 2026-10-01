package dev.clashaiaa.overlay

import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

sealed class GhostProbeEvent {
    data class Snapshot(val feed: GhostFeed, val receivedAtMs: Long) : GhostProbeEvent()
    data class Disconnected(val reason: String) : GhostProbeEvent()
}

/** Fast, independent poller for the probe's tiny GHOST response. */
class GhostClient(
    private val host: String,
    private val port: Int,
    private val executor: Executor,
    private val listener: (GhostProbeEvent) -> Unit,
    private val pollIntervalMs: Long = 50L,
) {
    private val running = AtomicBoolean(false)
    @Volatile private var active: Socket? = null
    @Volatile private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ loop() }, "clashaiaa-ghost-client").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { active?.close() }
        worker?.interrupt()
        worker = null
    }

    private fun loop() {
        var announcedFailure = false
        while (running.get()) {
            try {
                val feed = GhostJson.parse(request())
                announcedFailure = false
                emit(GhostProbeEvent.Snapshot(feed, System.currentTimeMillis()))
                if (!sleep(pollIntervalMs)) break
            } catch (exc: Exception) {
                if (!running.get()) break
                if (!announcedFailure) {
                    emit(GhostProbeEvent.Disconnected(exc.message ?: exc.javaClass.simpleName))
                    announcedFailure = true
                }
                if (!sleep(250L)) break
            }
        }
    }

    private fun request(): String {
        val socket = Socket()
        active = socket
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), 350)
            socket.soTimeout = 350
            socket.getOutputStream().apply {
                write("GHOST\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            return readLine(socket.getInputStream())
                ?: throw IOException("probe closed without a GHOST response")
        } finally {
            active = null
            runCatching { socket.close() }
        }
    }

    private fun readLine(stream: InputStream): String? {
        val bytes = ByteArray(16_384)
        val out = StringBuilder()
        while (out.length <= 65_536) {
            val count = stream.read(bytes)
            if (count < 0) break
            for (index in 0 until count) {
                val byte = bytes[index]
                if (byte == '\n'.code.toByte()) return out.toString()
                out.append(byte.toInt().toChar())
            }
        }
        if (out.isEmpty()) return null
        throw IOException("GHOST response too large or unterminated")
    }

    private fun emit(event: GhostProbeEvent) = executor.execute { listener(event) }

    private fun sleep(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (_: InterruptedException) {
        false
    }
}
