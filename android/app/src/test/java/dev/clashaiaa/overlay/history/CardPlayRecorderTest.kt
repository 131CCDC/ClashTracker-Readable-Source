package dev.clashaiaa.overlay.history

import dev.clashaiaa.overlay.GhostDropEvent
import dev.clashaiaa.overlay.GhostFeed
import dev.clashaiaa.overlay.IdentitySource
import dev.clashaiaa.overlay.LocalIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardPlayRecorderTest {

    private fun event(
        issuer: Long,
        serverTick: Int,
        execTick: Int = serverTick + 20,
        commandSequence: Long = serverTick.toLong(),
        cardId: Int = 26000021,
    ) = GhostDropEvent(
        sequence = commandSequence,
        issuerAccountId = issuer,
        cardId = cardId,
        x = 9_000,
        y = 21_000,
        serverTick = serverTick,
        execTick = execTick,
        commandSequence = commandSequence,
        semanticTick = serverTick + 1,
        semanticMs = (serverTick + 1) * 50L,
    )

    private fun feed(vararg events: GhostDropEvent) = GhostFeed(
        schema = "nulls-ghost.v1",
        inBattle = true,
        tick = 200,
        account0 = 11L,
        account1 = 22L,
        selfHint = 0L,
        events = events.toList(),
    )

    private val accounts = setOf(11L, 22L)

    @Test
    fun `same event in repeated 50 ms polls is persisted once`() {
        val recorder = CardPlayRecorder()
        val snapshot = feed(event(11L, 100))

        val first = recorder.observe(snapshot, "battle-a", accounts, LocalIdentity.UNKNOWN, 1_000L)
        val repeated = recorder.observe(snapshot, "battle-a", accounts, LocalIdentity.UNKNOWN, 1_050L)

        assertEquals(1, first.size)
        assertTrue(repeated.isEmpty())
    }

    @Test
    fun `same event key is allowed in a different battle uid`() {
        val recorder = CardPlayRecorder()
        val snapshot = feed(event(11L, 100))

        assertEquals(1, recorder.observe(snapshot, "battle-a", accounts, LocalIdentity.UNKNOWN, 1L).size)
        assertEquals(1, recorder.observe(snapshot, "battle-b", accounts, LocalIdentity.UNKNOWN, 2L).size)
    }

    @Test
    fun `rows are ordered by server tick then exec tick`() {
        val recorder = CardPlayRecorder()
        val rows = recorder.observe(
            feed(
                event(11L, 120, execTick = 145, commandSequence = 3),
                event(22L, 100, execTick = 130, commandSequence = 2),
                event(11L, 100, execTick = 125, commandSequence = 1),
            ),
            "battle-a",
            accounts,
            LocalIdentity.UNKNOWN,
            1L,
        )

        assertEquals(listOf(125, 130, 145), rows.map { it.execTick })
    }

    @Test
    fun `ghost arriving before battle frame binds to the active uid`() {
        val recorder = CardPlayRecorder()
        val beforeFrame = recorder.observe(
            feed(event(22L, 100)),
            activeBattleUid = null,
            activeAccountIds = emptySet(),
            identity = LocalIdentity.UNKNOWN,
            receivedAtMs = 1_000L,
        )
        assertTrue(beforeFrame.isEmpty())

        val attached = recorder.attachPending("battle-a", accounts, LocalIdentity.UNKNOWN)
        assertEquals(1, attached.size)
        assertEquals("battle-a", attached.single().battleUid)
    }

    @Test
    fun `unknown identity never becomes false self attribution`() {
        val row = CardPlayRecorder().observe(
            feed(event(11L, 100)),
            "battle-a",
            accounts,
            LocalIdentity.UNKNOWN,
            1L,
        ).single()

        assertNull(row.isSelf)
        assertEquals(0, row.owner)
    }

    @Test
    fun `verified identity labels both self and enemy without filtering either`() {
        val identity = LocalIdentity(22L, 1, IdentitySource.CONFIGURED)
        val rows = CardPlayRecorder().observe(
            feed(event(22L, 100), event(11L, 110)),
            "battle-a",
            accounts,
            identity,
            1L,
        )

        assertEquals(2, rows.size)
        assertTrue(rows.first { it.issuerAccountId == 22L }.isSelf == true)
        assertFalse(rows.first { it.issuerAccountId == 11L }.isSelf!!)
        assertEquals(CardPlaySource.SEMANTIC_GHOST, rows[0].source)
    }

    @Test
    fun `pending rows from a different account pair are not attached`() {
        val recorder = CardPlayRecorder()
        recorder.observe(feed(event(22L, 100)), null, emptySet(), LocalIdentity.UNKNOWN, 1L)

        assertTrue(
            recorder.attachPending("battle-other", setOf(33L, 44L), LocalIdentity.UNKNOWN).isEmpty(),
        )
    }
}
