package dev.clashaiaa.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The defect these tests pin down: `players[].owner` is a world seat index that
 * the server swaps between matches, so a seat was rendered as "me" and the
 * opponent columns displayed the local player's own hand about half the time.
 * Identity must come from evidence, and a confirmed identity is account-based.
 */
class IdentityResolverTest {

    private fun player(owner: Int, account: Long, deck: List<Int> = emptyList()) =
        ProbePlayer(owner, account, 5f, 50_000, emptyList(), deck)

    /** Seat 0 = account 11 (the enemy here), seat 1 = account 22 (this device). */
    private fun battle(
        tick: Int,
        players: List<ProbePlayer> = listOf(player(0, 11L), player(1, 22L)),
        inputTicks: List<Int> = emptyList(),
        supported: Boolean = true,
        entities: List<ProbeEntity> = emptyList(),
    ) = BattleState(
        inBattle = true,
        stale = false,
        tick = tick,
        players = players,
        localInputTicks = inputTicks,
        localInputSupported = supported,
        entities = entities,
    )

    private fun drop(issuer: Long, serverTick: Int) = GhostDropEvent(
        sequence = serverTick.toLong(),
        issuerAccountId = issuer,
        cardId = 26000021,
        x = 9000,
        y = 21000,
        serverTick = serverTick,
        execTick = serverTick + 20,
        commandSequence = serverTick.toLong(),
    )

    private fun feed(tick: Int, events: List<GhostDropEvent>) =
        GhostFeed("nulls-ghost.v1", true, tick, 11L, 22L, 0L, events)

    @Test
    fun `the seat setting is a label, never proof`() {
        val state = battle(tick = 200, inputTicks = listOf(150))
        val resolver = IdentityResolver()
        resolver.onFrame(state)
        val identity = resolver.identity(state)
        assertEquals(IdentitySource.SEAT_FALLBACK, identity.source)
        assertFalse(identity.verified)
        // It still renders (the panel must show the battle from frame one) but it
        // is marked: the renderer keeps identityVerified false for it.
        assertTrue(identity.usable)
    }

    @Test
    fun `two echoed local plays confirm the account, and it is not seat zero`() {
        val learned = ArrayList<Long>()
        val resolver = IdentityResolver(notifyLearned = { learned += it })
        resolver.onFrame(battle(tick = 100, inputTicks = listOf(100)))
        resolver.onFrame(battle(tick = 200, inputTicks = listOf(200)))
        resolver.onGhost(feed(110, listOf(drop(22L, 100))))
        resolver.onGhost(feed(210, listOf(drop(22L, 200))))

        val identity = resolver.identity(battle(tick = 220))
        assertEquals(22L, identity.accountId)
        assertEquals(1, identity.owner)
        assertEquals(IdentitySource.OBSERVED, identity.source)
        assertTrue(identity.verified)
        assertEquals(listOf(22L), learned)
        assertEquals(22L, resolver.learnedAccountId)
    }

    @Test
    fun `a single coincidental tick match is not proof`() {
        val resolver = IdentityResolver()
        resolver.onFrame(battle(tick = 100, inputTicks = listOf(100)))
        resolver.onGhost(feed(110, listOf(drop(22L, 100))))
        assertFalse(resolver.identity(battle(tick = 120)).verified)
    }

    @Test
    fun `a tick that two accounts share is discarded instead of voting`() {
        val resolver = IdentityResolver()
        resolver.onFrame(battle(tick = 100, inputTicks = listOf(100)))
        resolver.onGhost(feed(110, listOf(drop(11L, 100), drop(22L, 100))))
        assertFalse(resolver.identity(battle(tick = 120)).verified)
    }

    @Test
    fun `an event from an older battle cannot vote`() {
        val resolver = IdentityResolver()
        resolver.onFrame(battle(tick = 100, inputTicks = listOf(100)))
        // Same tick value, but the feed's own clock says it belongs to an older
        // battle, so the edge belongs to a different play.
        resolver.onGhost(feed(400, listOf(drop(22L, 100))))
        assertFalse(resolver.identity(battle(tick = 120)).verified)
    }

    @Test
    fun `a cached account is used from the first frame of the next battle`() {
        val resolver = IdentityResolver()
        resolver.seedLearned(22L)
        val state = battle(tick = 10)
        val identity = resolver.identity(state)
        assertEquals(22L, identity.accountId)
        assertEquals(1, identity.owner)
        assertEquals(IdentitySource.LEARNED, identity.source)
    }

    @Test
    fun `a cached account absent from this battle is ignored`() {
        val resolver = IdentityResolver()
        resolver.seedLearned(22L)
        val elsewhere = battle(tick = 10, players = listOf(player(0, 11L), player(1, 33L)))
        resolver.onFrame(elsewhere)
        val identity = resolver.identity(elsewhere)
        assertEquals(IdentitySource.SEAT_FALLBACK, identity.source)
        assertEquals(0L, identity.accountId)
        assertFalse(identity.verified)
    }

    @Test
    fun `a configured account wins when it is in this battle`() {
        val resolver = IdentityResolver()
        resolver.configuredAccountId = 11L
        val identity = resolver.identity(battle(tick = 10))
        assertEquals(11L, identity.accountId)
        assertEquals(0, identity.owner)
        assertEquals(IdentitySource.CONFIGURED, identity.source)
    }

    @Test
    fun `a configured account that is not in this battle fabricates nothing`() {
        val resolver = IdentityResolver()
        resolver.configuredAccountId = 999L
        val state = battle(tick = 10)
        resolver.onFrame(state)
        val identity = resolver.identity(state)
        assertEquals(IdentitySource.SEAT_FALLBACK, identity.source)
        assertEquals(0L, identity.accountId)
        assertFalse(identity.verified)
    }

    @Test
    fun `the fallback says whether the probe could ever do better`() {
        val resolver = IdentityResolver()
        resolver.configuredOwner = 1
        val noObserver = battle(tick = 10, supported = false)
        resolver.onFrame(noObserver)
        val identity = resolver.identity(noObserver)
        assertEquals(IdentitySource.SEAT_FALLBACK, identity.source)
        assertEquals(1, identity.owner)
        assertFalse(identity.verified)
        assertTrue(identity.usable)
        assertEquals("未确认·按座位 1·探针无自动识别", identity.label)

        val withObserver = IdentityResolver()
        withObserver.configuredOwner = 1
        withObserver.onFrame(battle(tick = 10))
        assertEquals("未确认·按座位 1", withObserver.identity(battle(tick = 10)).label)
    }

    @Test
    fun `the deploy after a local play votes for that play's seat`() {
        val resolver = IdentityResolver()
        val card = 26000021
        resolver.onFrame(battle(tick = 100, inputTicks = listOf(100)))
        resolver.onFrame(battle(tick = 120, entities = listOf(ProbeEntity(1, 1, card))))
        resolver.onFrame(battle(tick = 200, inputTicks = listOf(200), entities = listOf(ProbeEntity(1, 1, card))))
        resolver.onFrame(battle(tick = 220, entities = listOf(ProbeEntity(1, 1, card), ProbeEntity(2, 1, card))))
        resolver.onFrame(battle(tick = 300, inputTicks = listOf(300), entities = listOf(ProbeEntity(1, 1, card), ProbeEntity(2, 1, card))))
        resolver.onFrame(battle(tick = 320, entities = listOf(
            ProbeEntity(1, 1, card),
            ProbeEntity(2, 1, card),
            ProbeEntity(3, 1, card),
        )))

        val identity = resolver.identity(battle(tick = 330))
        assertEquals(IdentitySource.OBSERVED, identity.source)
        assertEquals(22L, identity.accountId)
        assertEquals(1, identity.owner)
    }

    @Test
    fun `one play that spawns three units is still one vote`() {
        val resolver = IdentityResolver()
        resolver.onFrame(battle(tick = 100, inputTicks = listOf(100)))
        resolver.onFrame(battle(tick = 120, entities = listOf(
            ProbeEntity(1, 1, 26000021),
            ProbeEntity(2, 1, 26000021),
            ProbeEntity(3, 1, 26000021),
        )))
        assertFalse(resolver.identity(battle(tick = 130)).verified)
    }

    @Test
    fun `an edge that saw both seats deploy abstains`() {
        val resolver = IdentityResolver()
        var tick = 100
        repeat(4) {
            resolver.onFrame(battle(tick = tick, inputTicks = listOf(tick)))
            resolver.onFrame(battle(tick = tick + 20, entities = listOf(
                ProbeEntity(tick, 0, 1),
                ProbeEntity(tick + 1, 1, 1),
            )))
            tick += 100
        }
        assertFalse(resolver.identity(battle(tick = tick)).verified)
    }

    @Test
    fun `towers and objects the truncated entity list republished never vote`() {
        val resolver = IdentityResolver()
        resolver.onFrame(battle(tick = 100, inputTicks = listOf(100)))
        // card id -1 is a tower, not a deployment.
        resolver.onFrame(battle(tick = 120, entities = listOf(ProbeEntity(4_000_005, 0, -1))))
        // The next frame's entity list is truncated, so an existing unit is
        // republished later as if it were new; its id sits below the mark.
        resolver.onFrame(battle(tick = 200, inputTicks = listOf(200), entities = listOf(
            ProbeEntity(5_000_010, 0, 26000010),
        )))
        resolver.onFrame(battle(tick = 220, entities = listOf(
            ProbeEntity(5_000_010, 0, 26000010),
            ProbeEntity(5_000_009, 0, 26000010),
        )))
        assertFalse(resolver.identity(battle(tick = 230)).verified)
    }

    @Test
    fun `a new battle keeps the confirmed account but drops its votes`() {
        val resolver = IdentityResolver()
        resolver.onFrame(battle(tick = 100, inputTicks = listOf(100)))
        resolver.onFrame(battle(tick = 200, inputTicks = listOf(200)))
        resolver.onGhost(feed(110, listOf(drop(22L, 100))))
        resolver.onGhost(feed(210, listOf(drop(22L, 200))))
        assertEquals(22L, resolver.identity(battle(tick = 220)).accountId)

        // A new match restarts the tick counter and swaps the seats.
        val swapped = battle(
            tick = 5,
            players = listOf(player(0, 22L), player(1, 11L)),
        )
        resolver.onFrame(swapped)
        val identity = resolver.identity(swapped)
        assertEquals(22L, identity.accountId)
        assertEquals(0, identity.owner)
    }

    @Test
    fun `the ghost feed's 32-bit issuer still matches a 64-bit seat value`() {
        val wide = 4_294_967_318L // 2^32 + 22; players[] prints it in full
        val resolver = IdentityResolver()
        val state = battle(tick = 100, players = listOf(player(0, 11L), player(1, wide)), inputTicks = listOf(100))
        resolver.onFrame(state)
        resolver.onGhost(feed(110, listOf(drop(22L, 100))))
        assertFalse(resolver.identity(state).verified)

        val second = battle(tick = 200, players = listOf(player(0, 11L), player(1, wide)), inputTicks = listOf(200))
        resolver.onFrame(second)
        val wideFeed = GhostFeed("nulls-ghost.v1", true, 210, 11L, 22L, 0L, listOf(drop(22L, 200)))
        resolver.onGhost(wideFeed)
        val identity = resolver.identity(battle(tick = 220, players = listOf(player(0, 11L), player(1, wide))))
        assertEquals(IdentitySource.OBSERVED, identity.source)
        assertEquals(wide, identity.accountId)
        assertEquals(1, identity.owner)
    }

    @Test
    fun `the panel always has an identity to draw from the first frame`() {
        // Nothing is known yet, and no card has been played: the panel still has
        // to render, labelled as unconfirmed rather than withheld.
        val state = battle(tick = 4)
        val resolver = IdentityResolver()
        resolver.onFrame(state)
        val identity = resolver.identity(state)
        assertEquals(IdentitySource.SEAT_FALLBACK, identity.source)
        assertFalse(identity.verified)
        assertTrue(identity.usable)
        assertEquals("未确认·按座位 0", identity.label)
    }

    @Test
    fun `one play locks the identity when both device-local signals agree`() {
        // Same play: the echoed command names the issuer (ghost join) and the
        // deploy 21 ticks later names the seat.
        val resolver = IdentityResolver()
        resolver.onFrame(battle(tick = 100, inputTicks = listOf(100)))
        resolver.onGhost(feed(110, listOf(drop(22L, 100))))
        val afterGhostOnly = resolver.identity(battle(tick = 120))
        assertFalse("a lone tick match must not lock", afterGhostOnly.verified)

        resolver.onFrame(battle(tick = 121, entities = listOf(ProbeEntity(5_000_001, 1, 26000021))))
        val identity = resolver.identity(battle(tick = 130))
        assertEquals(IdentitySource.OBSERVED, identity.source)
        assertEquals(22L, identity.accountId)
        assertEquals(1, identity.owner)
    }

    @Test
    fun `the agreement lock is cached only once a single signal is strict`() {
        val learned = ArrayList<Long>()
        val resolver = IdentityResolver(notifyLearned = { learned += it })
        resolver.onFrame(battle(tick = 100, inputTicks = listOf(100)))
        resolver.onGhost(feed(110, listOf(drop(22L, 100))))
        resolver.onFrame(battle(tick = 121, entities = listOf(ProbeEntity(5_000_001, 1, 26000021))))
        assertEquals(22L, resolver.identity(battle(tick = 130)).accountId)
        assertTrue("agreement alone must not poison the cache", learned.isEmpty())

        // A second own play makes the tick join strict, and only then is it cached.
        resolver.onFrame(battle(tick = 200, inputTicks = listOf(200)))
        resolver.onGhost(feed(210, listOf(drop(22L, 200))))
        assertEquals(listOf(22L), learned)
        assertEquals(22L, resolver.learnedAccountId)
    }

    @Test
    fun `the cached deck identifies the local seat from the first frame`() {
        val deck = listOf(26000021, 26000014, 28000000, 26000010, 26000030, 27000000, 26000038, 27000011)
        val myDeckState = battle(tick = 100, players = listOf(player(0, 11L), player(1, 22L, deck)))
        val resolver = IdentityResolver()
        resolver.onFrame(myDeckState)
        // Learned while the identity was proven: seat 1 is the local deck.
        resolver.onFrame(myDeckState.copy(localInputTicks = listOf(100)))
        resolver.onGhost(feed(110, listOf(drop(22L, 100))))
        resolver.onFrame(myDeckState.copy(tick = 121, entities = listOf(ProbeEntity(5_000_001, 1, 26000021))))

        // A later session, same account absent, same deck: identified instantly.
        val fresh = IdentityResolver()
        fresh.seedLearnedDeck(resolver.learnedDeckSignature)
        val nextBattle = battle(
            tick = 3,
            players = listOf(
                player(0, 44L, listOf(1, 2, 3, 4, 5, 6, 7, 8)),
                player(1, 22L, deck),
            ),
        )
        fresh.onFrame(nextBattle)
        val identity = fresh.identity(nextBattle)
        assertEquals(IdentitySource.DECK, identity.source)
        assertTrue(identity.verified)
        assertEquals(22L, identity.accountId)
        assertEquals(1, identity.owner)
    }

    @Test
    fun `a mirrored deck is ambiguous and never chosen by deck alone`() {
        val deck = listOf(26000021, 26000014, 28000000, 26000010, 26000030, 27000000, 26000038, 27000011)
        val resolver = IdentityResolver()
        resolver.seedLearnedDeck(deck.sorted().joinToString(","))
        val mirrored = battle(
            tick = 3,
            players = listOf(player(0, 44L, deck), player(1, 22L, deck)),
        )
        resolver.onFrame(mirrored)
        assertEquals(IdentitySource.SEAT_FALLBACK, resolver.identity(mirrored).source)
    }

    @Test
    fun `a stale or idle frame ends the battle without losing the cache`() {
        val resolver = IdentityResolver()
        resolver.seedLearned(22L)
        resolver.onFrame(battle(tick = 100, inputTicks = listOf(100)))
        resolver.onFrame(BattleState.IDLE)
        val identity = resolver.identity(battle(tick = 100))
        assertEquals(IdentitySource.LEARNED, identity.source)
    }
}
