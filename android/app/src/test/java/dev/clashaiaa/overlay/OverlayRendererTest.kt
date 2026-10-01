package dev.clashaiaa.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayRendererTest {

    private val opponent = ProbePlayer(
        owner = 1,
        accountId = 42L,
        elixir = 7.349f,
        elixirRaw = 73490,
        hand = listOf(
            HandCard(0, 26000016, "Prince"),
            HandCard(1, 12345678, null),
            HandCard(2, 0, null),
            HandCard(3, 27000000, "Fireball"),
        ),
        deck = emptyList(),
    )

    private val battle = BattleState(
        inBattle = true,
        stale = false,
        tick = 100,
        players = listOf(ProbePlayer(0, 1L, 5f, 50000, emptyList(), emptyList()), opponent),
    )

    /** Account 1 is the local player, so the opponent is the account 42 seat. */
    private val me = LocalIdentity(1L, 0, IdentitySource.CONFIGURED)

    @Test
    fun `disconnected never shows stale battle data`() {
        val model = OverlayRenderer.model(connected = false, state = battle, identity = me)
        assertEquals("Probe: Disconnected", model.status)
        assertFalse(model.showBattleData)
        assertFalse(model.waiting)
        assertEquals(listOf("--", "--", "--", "--"), model.cards)
        assertEquals("--", model.elixir)
    }

    @Test
    fun `connected probe without a battle waits instead of guessing`() {
        val model = OverlayRenderer.model(
            connected = true,
            state = BattleState.IDLE,
            identity = me,
        )
        assertFalse(model.showBattleData)
        assertTrue(model.waiting)
        assertEquals("Probe: Connected", model.status)
        assertEquals(OverlayRenderer.WAITING_NOTICE, model.notice)
        assertEquals(listOf("--", "--", "--", "--"), model.cards)
    }

    @Test
    fun `live battle exposes opponent elixir and four hand slots`() {
        val model = OverlayRenderer.model(connected = true, state = battle, identity = me)
        assertTrue(model.showBattleData)
        assertFalse(model.waiting)
        assertEquals("Probe: Connected", model.status)
        // One decimal, exactly like the Windows HUD's elixir pill.
        assertEquals("7.3", model.elixir)
        assertEquals(listOf("Prince", "Unknown(12345678)", "--", "Fireball"), model.cards)
        assertEquals("本机 1", model.identityLabel)
        assertTrue(model.identityVerified)
    }

    @Test
    fun `the panel renders from the first frame, with the seat fallback labelled`() {
        val model = OverlayRenderer.model(
            connected = true,
            state = battle,
            identity = LocalIdentity(0L, 0, IdentitySource.SEAT_FALLBACK),
        )
        // The Windows HUD would be reading its configured account here; on the
        // tablet the seat is the provisional answer, drawn immediately and named.
        assertTrue(model.showBattleData)
        assertFalse(model.identityVerified)
        assertEquals("未确认·按座位 0", model.identityLabel)
        assertEquals("7.3", model.elixir)
        assertEquals("Prince", model.cards.first())
    }

    @Test
    fun `native evolution and hero marks reach the model`() {
        val hero = HeroAbility(name = "IceGolemiteHero_Ability", available = true, charges = 1, maxCharges = 1)
        val withRoles = ProbePlayer(
            owner = 1,
            accountId = 42L,
            elixir = 4f,
            elixirRaw = 40_000,
            hand = listOf(
                HandCard(0, 26000018, "MiniPekka", cost = 4, role = CardRole(activeForm = 1, evolutionRequired = 2, evolutionProgress = 2)),
                HandCard(1, 26000015, "BabyDragon", cost = 4, role = CardRole(activeForm = 2)),
                HandCard(2, 26000030, "IceGolemite", cost = 2, role = CardRole(activeForm = 2)),
                HandCard(3, 0, null),
            ),
            deck = emptyList(),
            cycle = listOf(26000038),
            // A cycle already started, not yet charged: `↻1` on the small tile.
            roles = mapOf(26000038 to CardRole(activeForm = 0, evolutionRequired = 2, evolutionProgress = 1)),
            hero = hero,
        )
        val state = BattleState(
            inBattle = true,
            stale = false,
            tick = 100,
            players = listOf(ProbePlayer(0, 1L, 5f, 50_000, emptyList(), emptyList()), withRoles),
        )
        val model = OverlayRenderer.model(true, state, me)
        assertEquals("READY", model.cardBadges[0]?.evoLong)
        assertTrue(model.cardBadges[0]?.evoReady == true)
        // The opponent has one live ability row, which marks every hero-form card
        // in the hand, exactly like the Windows HUD's `card_badge(role, hero)`.
        assertEquals("★ READY", model.cardBadges[1]?.hero)
        assertEquals("★ READY", model.cardBadges[2]?.hero)
        assertEquals(listOf(4, 4, 2, null), model.cardCosts)
        assertEquals("↻1", model.cycleBadges.single()?.evoShort)
    }

    @Test
    fun `an evolution that is merely available stays unmarked`() {
        val role = CardRole(activeForm = 0, evolutionRequired = 2, evolutionProgress = 0)
        assertNull(CardBadges.of(role, null))
        assertEquals("EVO 1/2", CardBadges.of(CardRole(0, 2, 1), null)?.evoLong)
        assertEquals("★ 12s", CardBadges.of(CardRole(2), HeroAbility("x", cooldownSeconds = 12))?.hero)
        assertEquals("★ 2/2", CardBadges.of(CardRole(2), HeroAbility("x", charges = 2, maxCharges = 2))?.hero)
    }

    @Test
    fun `the raw elixir reaches the model for the affordability gauge`() {
        val model = OverlayRenderer.model(connected = true, state = battle, identity = me)
        // The text is one decimal; the gauge gets the value the probe published,
        // so 4.98 and 5.01 cannot be mistaken for each other.
        assertEquals("7.3", model.elixir)
        assertEquals(7.349f, model.elixirValue)
        assertNull(
            OverlayRenderer.model(connected = false, state = battle, identity = me).elixirValue,
        )
        assertNull(
            OverlayRenderer.model(connected = true, state = BattleState.IDLE, identity = me).elixirValue,
        )
    }

    @Test
    fun `the local seat may be seat one, and the enemy is still the enemy`() {
        val pekka = ProbePlayer(
            owner = 1,
            accountId = 11L,
            elixir = 5f,
            elixirRaw = 50_000,
            hand = listOf(HandCard(0, 26000004, "Pekka")),
            deck = emptyList(),
        )
        val miniPekkaLocal = ProbePlayer(
            owner = 0,
            accountId = 22L,
            elixir = 6f,
            elixirRaw = 60_000,
            hand = listOf(HandCard(0, 26000018, "MiniPekka")),
            deck = emptyList(),
        )
        val swapped = BattleState(true, false, 200, listOf(miniPekkaLocal, pekka))
        // This device is account 22, which the server put at seat 0 this match.
        val observed = LocalIdentity(22L, 0, IdentitySource.OBSERVED)
        val model = OverlayRenderer.model(true, swapped, observed)
        assertEquals(listOf(26000004, 0, 0, 0), model.cardIds)
        assertEquals("Pekka", model.cards.first())
    }

    @Test
    fun `a stale seat setting cannot invert a confirmed account`() {
        val pekka = ProbePlayer(
            owner = 0,
            accountId = 11L,
            elixir = 5f,
            elixirRaw = 50_000,
            hand = listOf(HandCard(0, 26000004, "Pekka")),
            deck = emptyList(),
        )
        val miniPekkaLocal = ProbePlayer(
            owner = 1,
            accountId = 22L,
            elixir = 6f,
            elixirRaw = 60_000,
            hand = listOf(HandCard(0, 26000018, "MiniPekka")),
            deck = emptyList(),
        )
        val swapped = BattleState(true, false, 200, listOf(pekka, miniPekkaLocal))
        // The seat setting says 0, but the proved local account is seat 1.
        val observed = LocalIdentity(22L, 1, IdentitySource.LEARNED)
        val model = OverlayRenderer.model(true, swapped, observed)
        assertEquals(listOf(26000004, 0, 0, 0), model.cardIds)
        assertEquals("Pekka", model.cards.first())
    }
}
