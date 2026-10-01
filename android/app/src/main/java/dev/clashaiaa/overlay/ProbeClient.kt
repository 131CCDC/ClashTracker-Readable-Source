package dev.clashaiaa.overlay

import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Edge-triggered events published from the probe worker thread. */
sealed class ProbeEvent {
    /** First successful round trip after a failure (or after start). */
    object Connected : ProbeEvent()

    data class Snapshot(
        val state: BattleState,
        val receivedAtMs: Long,
        /**
         * The probe's response line, verbatim.
         *
         * Additive: the HUD keeps reading [state] and ignores this. The battle
         * history recorder parses the raw line itself so a change to what the
         * HUD understands can never change what gets written to the database.
         */
        val raw: String = "",
    ) : ProbeEvent()

    data class Disconnected(val reason: String) : ProbeEvent()
}

/**
 * Minimal client for the existing Clashaiaa probe endpoint.
 *
 * The probe accepts one command per connection and closes it, exactly like the
 * PC bridge client does, so every poll is a short-lived socket. The loop is
 * bounded: a successful poll waits [pollIntervalMs], a failed one backs off
 * exponentially up to [maxBackoffMs]. No Android API is used here, which keeps
 * the class unit-testable against a real loopback server.
 */
class ProbeClient(
    private val host: String = "127.0.0.1",
    private val port: Int = ProbeJson.DEFAULT_PORT,
    private val executor: Executor,
    private val listener: (ProbeEvent) -> Unit,
    private val pollIntervalMs: Long = 200L,
    private val connectTimeoutMs: Int = 400,
    private val readTimeoutMs: Int = 400,
    private val maxBackoffMs: Long = 2_000L,
    private val armIntervalMs: Long = 5_000L,
) {
    private val running = AtomicBoolean(false)

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var active: Socket? = null

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val thread = Thread({ loop() }, "clashaiaa-probe-client")
        thread.isDaemon = true
        worker = thread
        thread.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        closeActive()
        worker?.interrupt()
        worker = null
    }

    /** One-shot `PING` health check against the existing probe protocol. */
    fun ping(): Boolean = try {
        request("PING").trim() == "PONG"
    } catch (exc: IOException) {
        false
    }

    private fun loop() {
        var failures = 0
        var connected = false
        var lastArmMs = 0L
        while (running.get()) {
            var nextDelayMs: Long
            try {
                val raw = request(GET_COMMAND)
                val state = ProbeJson.parse(raw)
                val now = System.currentTimeMillis()
                if (!connected) {
                    connected = true
                    emit(ProbeEvent.Connected)
                    lastArmMs = 0L
                }
                // The probe gates every battle frame behind a lifecycle
                // barrier: until it is armed it answers {"in_battle":false}
                // forever, even mid-battle. ARM only flips probe-owned flags
                // and never touches the game or queues an input, so a read-only
                // overlay is allowed to drive it. It is sent on connect and
                // then throttled while no battle is running, which is what
                // arms the next battle.
                if (lastArmMs == 0L || (!state.inBattle && now - lastArmMs >= armIntervalMs)) {
                    lastArmMs = now
                    arm()
                }
                emit(ProbeEvent.Snapshot(state, now, raw))
                failures = 0
                nextDelayMs = pollIntervalMs
            } catch (exc: Exception) {
                if (!running.get()) break
                if (connected || failures == 0) {
                    connected = false
                    emit(ProbeEvent.Disconnected(exc.message ?: exc.javaClass.simpleName))
                }
                failures++
                nextDelayMs = backoffMs(failures)
            }
            if (!sleep(nextDelayMs)) break
        }
        if (connected) emit(ProbeEvent.Disconnected("stopped"))
    }

    /** Best-effort lifecycle arm; a failure is retried on the next poll. */
    private fun arm() {
        try {
            request(ARM_COMMAND)
        } catch (exc: Exception) {
            // GET already reports a dead probe; ignoring keeps the poll cadence.
        }
    }

    private fun backoffMs(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, 8)
        val delay = pollIntervalMs shl shift
        return if (delay <= 0 || delay > maxBackoffMs) maxBackoffMs else delay
    }

    private fun sleep(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (exc: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private fun emit(event: ProbeEvent) {
        executor.execute { listener(event) }
    }

    private fun request(command: String): String {
        val socket = Socket()
        active = socket
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val out = socket.getOutputStream()
            out.write((command + "\n").toByteArray(Charsets.US_ASCII))
            out.flush()
            return readLine(socket.getInputStream())
                ?: throw IOException("probe closed the connection without a response")
        } finally {
            active = null
            try {
                socket.close()
            } catch (ignored: IOException) {
                // Closing a socket that the probe already tore down is expected.
            }
        }
    }

    private fun closeActive() {
        try {
            active?.close()
        } catch (ignored: IOException) {
            // Best effort shutdown; the worker exits on the failed read.
        }
    }

    /** Reads one newline-terminated response, bounded like the Python client. */
    private fun readLine(stream: InputStream): String? {
        val buffer = ByteArray(65536)
        val line = StringBuilder()
        while (line.length <= MAX_RESPONSE_BYTES) {
            val read = stream.read(buffer)
            if (read < 0) break
            for (i in 0 until read) {
                val byte = buffer[i]
                if (byte == '\n'.code.toByte()) return line.toString()
                line.append(byte.toInt().toChar())
            }
        }
        if (line.isEmpty()) return null
        throw IOException("probe response exceeds $MAX_RESPONSE_BYTES bytes or is unterminated")
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1 shl 20
        const val GET_COMMAND = "GET"
        const val ARM_COMMAND = "ARM"
    }
}
