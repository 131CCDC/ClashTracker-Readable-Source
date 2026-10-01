package dev.clashaiaa.overlay

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeClientTest {

    private val sameThread = Executor { command -> command.run() }

    /** Answers one command per connection, exactly like `nulls_probe.cpp`. */
    private class StubProbe(response: String, port: Int = 0) : AutoCloseable {
        private val response: String = response
        val server = ServerSocket(port)
        val port: Int get() = server.localPort
        val commands: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        private val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val request = reader.readLine() ?: return@use
                        commands.add(request)
                        val body = when {
                            request.startsWith("PING") -> "PONG\n"
                            request.startsWith("ARM") -> "{\"ok\":true,\"armed\":true}\n"
                            request.startsWith("RESET") -> "{\"ok\":true,\"reset\":true}\n"
                            else -> response
                        }
                        socket.getOutputStream().write(body.toByteArray())
                        socket.getOutputStream().flush()
                    }
                } catch (ignored: Exception) {
                    return@thread
                }
            }
        }

        override fun close() {
            server.close()
            worker.interrupt()
        }
    }

    @Test
    fun `reads and publishes a snapshot from a reachable probe`() {
        val frame = "{\"in_battle\":true,\"tick\":77,\"players\":[" +
            "{\"owner\":0,\"elixir\":2.0,\"hand\":[]}," +
            "{\"owner\":1,\"elixir\":8.25,\"hand\":[" +
            "{\"slot\":0,\"card_id\":1,\"name\":\"A\"},{\"slot\":1,\"card_id\":2,\"name\":\"B\"}," +
            "{\"slot\":2,\"card_id\":3,\"name\":\"C\"},{\"slot\":3,\"card_id\":4,\"name\":\"D\"}]}]}\n"
        StubProbe(frame).use { stub ->
            val snapshots = java.util.Collections.synchronizedList(ArrayList<BattleState>())
            val connected = CountDownLatch(1)
            val client = ProbeClient(
                host = "127.0.0.1",
                port = stub.port,
                executor = sameThread,
                listener = { event ->
                    when (event) {
                        is ProbeEvent.Connected -> connected.countDown()
                        is ProbeEvent.Snapshot -> snapshots.add(event.state)
                        is ProbeEvent.Disconnected -> Unit
                    }
                },
                pollIntervalMs = 20L,
            )
            client.start()
            assertTrue("client never connected", connected.await(5, TimeUnit.SECONDS))
            val deadline = System.currentTimeMillis() + 5_000
            while (snapshots.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            client.stop()

            assertFalse(snapshots.isEmpty())
            val state = snapshots.first()
            val opponent = state.opponent(localOwner = 0, localAccountId = 0L)
            assertNotNull(opponent)
            assertEquals(8.25f, opponent!!.elixir, 0.001f)
            assertEquals(listOf(1, 2, 3, 4), opponent.hand.map { it.cardId })
        }
    }

    @Test
    fun `arms the probe lifecycle on connect, because GET stays gated without it`() {
        val stub = StubProbe("{\"in_battle\":false}\n")
        stub.use {
            val client = ProbeClient(
                host = "127.0.0.1",
                port = stub.port,
                executor = sameThread,
                listener = {},
                pollIntervalMs = 20L,
            )
            client.start()
            val deadline = System.currentTimeMillis() + 5_000
            while (stub.commands.none { it.startsWith("ARM") } && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            client.stop()
            assertTrue("client never armed the probe: ${stub.commands}", stub.commands.any { it.startsWith("ARM") })
        }
    }

    @Test
    fun `unreachable probe reports disconnected without crashing`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val disconnected = CountDownLatch(1)
        val client = ProbeClient(
            host = "127.0.0.1",
            port = closedPort,
            executor = sameThread,
            listener = { event -> if (event is ProbeEvent.Disconnected) disconnected.countDown() },
            pollIntervalMs = 20L,
            connectTimeoutMs = 200,
            maxBackoffMs = 60L,
        )
        client.start()
        assertTrue("no Disconnected event", disconnected.await(5, TimeUnit.SECONDS))
        assertTrue(client.isRunning)
        client.stop()
        assertFalse(client.isRunning)
    }

    @Test
    fun `ping uses the existing PING command`() {
        val stub = StubProbe("{\"in_battle\":false}\n")
        stub.use {
            val client = ProbeClient(
                host = "127.0.0.1",
                port = stub.port,
                executor = sameThread,
                listener = {},
            )
            assertTrue(client.ping())
        }
        val closedPort = ServerSocket(0).use { it.localPort }
        val offline = ProbeClient(
            host = "127.0.0.1",
            port = closedPort,
            executor = sameThread,
            listener = {},
            connectTimeoutMs = 200,
        )
        assertFalse(offline.ping())
    }

    @Test
    fun `reconnects after the probe restarts on the same port`() {
        val frame = "{\"in_battle\":true,\"tick\":5,\"players\":[{\"owner\":1,\"elixir\":1.0,\"hand\":[]}]}\n"
        val first = StubProbe(frame)
        val port = first.port
        val snapshots = java.util.Collections.synchronizedList(ArrayList<BattleState>())
        val client = ProbeClient(
            host = "127.0.0.1",
            port = port,
            executor = sameThread,
            listener = { event -> if (event is ProbeEvent.Snapshot) snapshots.add(event.state) },
            pollIntervalMs = 20L,
            maxBackoffMs = 40L,
        )
        client.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (snapshots.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse("no snapshot before restart", snapshots.isEmpty())

        // Simulate a game restart: the listener dies, then comes back on the
        // same port because the probe port is compiled into the library.
        first.close()
        Thread.sleep(200)
        val second = StubProbe(frame, port)
        try {
            val before = snapshots.size
            val restartDeadline = System.currentTimeMillis() + 8_000
            while (snapshots.size <= before && System.currentTimeMillis() < restartDeadline) {
                Thread.sleep(20)
            }
            assertTrue("client did not recover", snapshots.size > before)
        } finally {
            client.stop()
            second.close()
        }
    }
}
