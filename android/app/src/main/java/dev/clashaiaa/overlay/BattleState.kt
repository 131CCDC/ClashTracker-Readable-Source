package dev.clashaiaa.overlay

/** One hand slot as published by the Clashaiaa native probe. */
data class HandCard(
    val slot: Int,
    val cardId: Int,
    val name: String?,
    /**
     * The probe's own `card_runtime[].selected_cost` for this card, when it
     * attested one. Preferred over the bundled table, because it is what the
     * game currently charges (cost changes, mirror, overtime).
     */
    val cost: Int? = null,
    /** Native evolution / hero state of the deck slot this card belongs to. */
    val role: CardRole? = null,
)

/**
 * Native evolution / hero state of one deck slot (`card_runtime`), mirrored from
 * the Windows tracker's `tracker/probe_client.py`: `active_form == 1` means the
 * next deployment is the evolved form, `active_form == 2` marks the
 * hero/champion form. Nothing is inferred from play history.
 */
data class CardRole(
    val activeForm: Int,
    val evolutionRequired: Int? = null,
    val evolutionProgress: Int? = null,
) {
    val isEvolution: Boolean get() = activeForm == 1
    val isHero: Boolean get() = activeForm == 2

    /** The next deployment of this card is the evolved form (native). */
    val evolutionReady: Boolean get() = isEvolution

    /**
     * Positive evidence only: charged, or a cycle already started. A slot with
     * progress 0 and form 0 cannot be told apart from "no evolution equipped"
     * in the native data, so it stays unmarked instead of being guessed.
     */
    val evolutionEquipped: Boolean get() = isEvolution || (evolutionProgress ?: 0) != 0

    val cyclesRemaining: Int?
        get() {
            val required = evolutionRequired ?: return null
            val progress = evolutionProgress ?: return null
            return (required - progress).coerceAtLeast(0)
        }
}

/** One `ability_runtime` row: the opponent's champion/hero ability state. */
data class HeroAbility(
    val name: String,
    val available: Boolean = false,
    val cooldownSeconds: Int? = null,
    val charges: Int? = null,
    val maxCharges: Int? = null,
)

/** Corner marks the HUD paints for one card; never a long text block. */
data class CardBadge(
    /** Hand tiles: `EVO 1/2` / `READY`. */
    val evoLong: String = "",
    /** Cycle tiles: `↻1` / `✦`. */
    val evoShort: String = "",
    val evoReady: Boolean = false,
    /** `★` / `★ READY` / `★ 12s`. */
    val hero: String = "",
    val heroReady: Boolean = false,
) {
    val any: Boolean get() = evoLong.isNotEmpty() || hero.isNotEmpty()

    companion object {
        val NONE: CardBadge? = null
    }
}

/** Native marks for one card, mirroring the Windows tracker's `card_badge`. */
object CardBadges {
    fun of(role: CardRole?, ability: HeroAbility? = null): CardBadge? {
        if (role == null) return null
        var evoLong = ""
        var evoShort = ""
        var ready = false
        if (role.evolutionReady) {
            evoLong = "READY"
            evoShort = "✦"
            ready = true
        } else if (role.evolutionEquipped) {
            val remaining = role.cyclesRemaining
            val required = role.evolutionRequired ?: 0
            if (remaining != null && required > 0) {
                evoLong = "EVO ${role.evolutionProgress ?: 0}/$required"
                evoShort = "↻$remaining"
            } else {
                evoLong = "EVO"
                evoShort = "↻"
            }
        }
        var hero = ""
        var heroReady = false
        if (role.isHero) {
            hero = "★"
            if (ability != null) {
                val cooldown = ability.cooldownSeconds ?: 0
                if (ability.available) {
                    hero = "★ READY"
                    heroReady = true
                } else if (cooldown > 0) {
                    hero = "★ ${cooldown}s"
                } else if ((ability.charges ?: 0) > 0 && (ability.maxCharges ?: 0) > 1) {
                    hero = "★ ${ability.charges}/${ability.maxCharges}"
                }
            }
        }
        if (evoLong.isEmpty() && hero.isEmpty()) return null
        return CardBadge(evoLong = evoLong, evoShort = evoShort, evoReady = ready, hero = hero, heroReady = heroReady)
    }
}

/** One player entry of the probe `players` array. */
data class ProbePlayer(
    val owner: Int,
    val accountId: Long,
    val elixir: Float,
    val elixirRaw: Int,
    val hand: List<HandCard>,
    val deck: List<Int>,
    /** Native queue behind the four-card hand; index zero is the next card. */
    val cycle: List<Int> = emptyList(),
    /** Native evolution/hero role per card id, from `card_runtime`. */
    val roles: Map<Int, CardRole> = emptyMap(),
    /** The opponent's live champion ability row, when the probe attested one. */
    val hero: HeroAbility? = null,
) {
    fun roleFor(cardId: Int): CardRole? = roles[cardId]
}

/**
 * One arena object, reduced to the three fields the local-seat vote needs.
 *
 * `owner` is the same world seat index as [ProbePlayer.owner]; `id` is the
 * engine's native object id, which is what makes a *new* object detectable
 * between two frames.
 */
data class ProbeEntity(
    val id: Int,
    val owner: Int,
    val cardId: Int,
)

/** Everything the overlay needs from one probe snapshot. */
data class BattleState(
    val inBattle: Boolean,
    val stale: Boolean,
    val tick: Int,
    val players: List<ProbePlayer>,
    /**
     * Game ticks at which *this device* submitted a play-card command, taken
     * from the probe's `client_input_runtime` (the local UI command path). No
     * other client can produce these edges.
     */
    val localInputTicks: List<Int> = emptyList(),
    val localInputSupported: Boolean = false,
    val entities: List<ProbeEntity> = emptyList(),
) {
    fun player(owner: Int): ProbePlayer? = players.firstOrNull { it.owner == owner }

    /** The seat holding [accountId], or null when it is not in this battle. */
    fun ownerOf(accountId: Long): Int? = players
        .firstOrNull { accountId != 0L && it.accountId == accountId }
        ?.owner

    /**
     * The opposing seat. A configured account id wins over the seat setting so
     * a mirrored or swapped lobby cannot silently show the local hand.
     */
    fun opponent(localOwner: Int, localAccountId: Long): ProbePlayer? {
        if (localAccountId != 0L) {
            val me = players.firstOrNull { it.accountId == localAccountId }
            if (me != null) return players.firstOrNull { it.owner != me.owner }
        }
        return players.firstOrNull { it.owner != localOwner }
    }

    companion object {
        val IDLE = BattleState(inBattle = false, stale = false, tick = -1, players = emptyList())
    }
}
