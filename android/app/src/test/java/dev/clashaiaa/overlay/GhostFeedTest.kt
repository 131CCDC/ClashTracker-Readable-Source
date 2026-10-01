package dev.clashaiaa.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostFeedTest {
    private fun feed(tick: Int = 100, events: String): GhostFeed = GhostJson.parse(
        """{"schema":"nulls-ghost.v1","in_battle":true,"tick":$tick,
        "acct0":11,"acct1":22,"self_hint":11,"events":[$events]}"""
    )

    /** Seat 0 = 11, seat 1 = 22; the device under test is account 11. */
    private val selfAccount = 11L

    @Test
    fun `parses and admits only a future enemy drop`() {
        val parsed = feed(events =
            """{"sequence":1,"issuer":22,"card_id":26000021,"x":9000,"y":21000,
            "server_tick":100,"exec_tick":120,"command_sequence":7}"""
        )
        assertEquals(1, parsed.events.size)
        val state = GhostState()
        val markers = state.update(parsed, 1_000L, selfAccount)
        assertEquals(1, markers.size)
        assertEquals(0, markers.single().viewOwner)
        assertEquals(26000021, markers.single().event.cardId)
    }

    @Test
    fun `self drops and already executing drops are never shown`() {
        val parsed = feed(events =
            """{"issuer":11,"card_id":1,"server_tick":100,"exec_tick":120,"command_sequence":1},
            {"issuer":22,"card_id":2,"server_tick":100,"exec_tick":101,"command_sequence":2}"""
        )
        assertTrue(GhostState().update(parsed, 1_000L, selfAccount).isEmpty())
    }

    @Test
    fun `an unresolved identity draws nothing rather than a guessed side`() {
        val parsed = feed(events =
            """{"issuer":22,"card_id":26000021,"server_tick":100,"exec_tick":120,"command_sequence":1}"""
        )
        val state = GhostState()
        assertTrue(state.update(parsed, 1_000L, 0L).isEmpty())
        // A seat is not identity: owner 0 here is the local player, and drawing
        // would mirror the marker onto the wrong side of the arena.
        assertTrue(state.update(parsed, 1_000L, 22L).isEmpty())
    }

    @Test
    fun `a proved account keeps the other seat's drops and its own view`() {
        val parsed = feed(events =
            """{"issuer":11,"card_id":26000021,"server_tick":100,"exec_tick":120,"command_sequence":1},
            {"issuer":22,"card_id":26000018,"server_tick":100,"exec_tick":120,"command_sequence":2}"""
        )
        val markers = GhostState().update(parsed, 1_000L, 22L)
        assertEquals(listOf(26000021), markers.map { it.event.cardId })
        assertEquals(1, markers.single().viewOwner)
    }

    @Test
    fun `marker expires after execution tick`() {
        val state = GhostState()
        val event = """{"issuer":22,"card_id":3,"server_tick":100,"exec_tick":120,"command_sequence":3}"""
        assertEquals(1, state.update(feed(100, event), 1_000L, selfAccount).size)
        assertTrue(state.update(feed(122, event), 2_100L, selfAccount).isEmpty())
    }

    @Test
    fun `an unconfirmed self hint is never an identity`() {
        val parsed = feed(events =
            """{"issuer":22,"card_id":26000021,"server_tick":100,"exec_tick":120,"command_sequence":1}"""
        )
        assertEquals(11L, parsed.selfHint)
        // The resolver is the only thing allowed to name the local account; the
        // feed's hint is published before the command is decoded, and the live
        // audit caught both clients recording the same first issuer.
        assertTrue(GhostState().update(parsed, 1_000L, 0L).isEmpty())
    }
}
