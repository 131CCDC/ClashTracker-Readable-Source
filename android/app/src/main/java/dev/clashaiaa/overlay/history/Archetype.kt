package dev.clashaiaa.overlay.history

/**
 * Rule-based enemy-deck archetype classifier.
 *
 * Deliberately not a model: a first version that can be audited, argued with
 * and extended by editing one table. Every rule states what it *requires*
 * (`core`), what *supports* it (`support`) and what contradicts it (`avoid`),
 * and the result carries the confidence the evidence earned, so a thin guess
 * can be labelled instead of being presented as a fact.
 *
 * `archetype` is the family ("Miner", "Hog", "X-Bow"), `archetypeSubtype` the
 * build inside it ("Poison", "Earthquake", "Cycle"). The two together are what
 * the matchup table shows, and either alone is still a valid grouping key.
 */
data class ArchetypeMatch(
    val archetype: String,
    val archetypeZh: String,
    val subtype: String? = null,
    val subtypeZh: String? = null,
    val confidence: Double = 0.0,
) {
    val display: String
        get() = if (subtype.isNullOrBlank()) archetype else "$archetype $subtype"

    val displayZh: String
        get() = if (subtypeZh.isNullOrBlank()) archetypeZh else "$archetypeZh·$subtypeZh"

    companion object {
        val UNKNOWN = ArchetypeMatch("Unknown", "未知", null, null, 0.0)
    }
}

object ArchetypeClassifier {

    /** Card ids, named so the table below reads as the decks it describes. */
    private object C {
        const val KNIGHT = 26000000
        const val ARCHER = 26000001
        const val GOBLINS = 26000002
        const val GIANT = 26000003
        const val PEKKA = 26000004
        const val MINIONS = 26000005
        const val BALLOON = 26000006
        const val WITCH = 26000007
        const val BARBARIANS = 26000008
        const val GOLEM = 26000009
        const val SKELETONS = 26000010
        const val VALKYRIE = 26000011
        const val SKELETON_ARMY = 26000012
        const val BOMBER = 26000013
        const val MUSKETEER = 26000014
        const val BABY_DRAGON = 26000015
        const val PRINCE = 26000016
        const val WIZARD = 26000017
        const val MINI_PEKKA = 26000018
        const val SPEAR_GOBLINS = 26000019
        const val GIANT_SKELETON = 26000020
        const val HOG_RIDER = 26000021
        const val MINION_HORDE = 26000022
        const val ICE_WIZARD = 26000023
        const val ROYAL_GIANT = 26000024
        const val GUARDS = 26000025
        const val PRINCESS = 26000026
        const val DARK_PRINCE = 26000027
        const val THREE_MUSKETEERS = 26000028
        const val LAVA_HOUND = 26000029
        const val ICE_SPIRITS = 26000030
        const val FIRE_SPIRITS = 26000031
        const val MINER = 26000032
        const val SPARKY = 26000033
        const val BOWLER = 26000034
        const val LUMBERJACK = 26000035
        const val BATTLE_RAM = 26000036
        const val INFERNO_DRAGON = 26000037
        const val ICE_GOLEM = 26000038
        const val MEGA_MINION = 26000039
        const val DART_GOBLIN = 26000040
        const val GOBLIN_GANG = 26000041
        const val ELECTRO_WIZARD = 26000042
        const val ELITE_BARBARIANS = 26000043
        const val HUNTER = 26000044
        const val EXECUTIONER = 26000045
        const val BANDIT = 26000046
        const val ROYAL_RECRUITS = 26000047
        const val NIGHT_WITCH = 26000048
        const val BATS = 26000049
        const val ROYAL_GHOST = 26000050
        const val RAM_RIDER = 26000051
        const val ZAPPIES = 26000052
        const val RASCALS = 26000053
        const val CANNON_CART = 26000054
        const val MEGA_KNIGHT = 26000055
        const val SKELETON_BARREL = 26000056
        const val FLYING_MACHINE = 26000057
        const val WALL_BREAKERS = 26000058
        const val ROYAL_HOGS = 26000059
        const val GOBLIN_GIANT = 26000060
        const val FISHERMAN = 26000061
        const val MAGIC_ARCHER = 26000062
        const val ELECTRO_DRAGON = 26000063
        const val FIRECRACKER = 26000064
        const val MIGHTY_MINER = 26000065
        const val ELIXIR_GOLEM = 26000067
        const val BATTLE_HEALER = 26000068
        const val SKELETON_KING = 26000069
        const val ARCHER_QUEEN = 26000072
        const val GOLDEN_KNIGHT = 26000074
        const val MONK = 26000077
        const val MOTHER_WITCH = 26000083
        const val ELECTRO_GIANT = 26000085
        const val PHOENIX = 26000087
        const val LITTLE_PRINCE = 26000093
        const val GOBLIN_MACHINE = 26000096
        const val GOBLINSTEIN = 26000099
        const val RUNE_GIANT = 26000101
        const val BERSERKER = 26000102
        const val BOSS_BANDIT = 26000103

        const val CANNON = 27000000
        const val GOBLIN_HUT = 27000001
        const val MORTAR = 27000002
        const val INFERNO_TOWER = 27000003
        const val BOMB_TOWER = 27000004
        const val BARBARIAN_HUT = 27000005
        const val TESLA = 27000006
        const val ELIXIR_COLLECTOR = 27000007
        const val X_BOW = 27000008
        const val TOMBSTONE = 27000009
        const val FURNACE = 27000010
        const val GOBLIN_CAGE = 27000012
        const val GOBLIN_DRILL = 27000013

        const val FIREBALL = 28000000
        const val ARROWS = 28000001
        const val RAGE = 28000002
        const val ROCKET = 28000003
        const val GOBLIN_BARREL = 28000004
        const val FREEZE = 28000005
        const val MIRROR = 28000006
        const val LIGHTNING = 28000007
        const val ZAP = 28000008
        const val POISON = 28000009
        const val GRAVEYARD = 28000010
        const val LOG = 28000011
        const val TORNADO = 28000012
        const val CLONE = 28000013
        const val EARTHQUAKE = 28000014
        const val BARB_BARREL = 28000015
        const val HEAL = 28000016
        const val SNOWBALL = 28000017
        const val ROYAL_DELIVERY = 28000018
        const val GOBLIN_CURSE = 28000024
    }

    private class Rule(
        val family: String,
        val familyZh: String,
        val subtype: String?,
        val subtypeZh: String?,
        val core: Set<Int>,
        val support: Set<Int> = emptySet(),
        val avoid: Set<Int> = emptySet(),
    )

    /**
     * Ordered only for readability; the winner is chosen by score, not by
     * position, so a new rule can be dropped in anywhere.
     */
    private val RULES: List<Rule> = listOf(
        // ---- Miner family -------------------------------------------------
        Rule("Miner", "矿工", "Poison", "毒药", setOf(C.MINER, C.POISON),
            support = setOf(C.SKELETONS, C.BATS, C.ICE_SPIRITS, C.LOG, C.VALKYRIE)),
        Rule("Miner", "矿工", "Bomb Tower", "炸弹塔", setOf(C.MINER, C.BOMB_TOWER),
            support = setOf(C.POISON, C.SKELETONS, C.LOG)),
        Rule("Miner", "矿工", "Rocket", "火箭", setOf(C.MINER, C.ROCKET),
            support = setOf(C.SKELETONS, C.LOG, C.ICE_SPIRITS)),
        Rule("Miner", "矿工", "Drill", "钻机", setOf(C.MINER, C.GOBLIN_DRILL),
            support = setOf(C.POISON, C.GOBLIN_GANG)),
        Rule("Miner", "矿工", "Balloon", "气球", setOf(C.MINER, C.BALLOON)),
        Rule("Miner", "矿工", "Control", "控制", setOf(C.MINER),
            support = setOf(C.SKELETONS, C.BATS, C.LOG, C.VALKYRIE, C.MUSKETEER, C.TESLA)),

        // ---- Hog family ---------------------------------------------------
        Rule("Hog", "野猪", "Earthquake", "地震", setOf(C.HOG_RIDER, C.EARTHQUAKE),
            support = setOf(C.LOG, C.FIREBALL, C.MUSKETEER, C.ICE_SPIRITS)),
        Rule("Hog", "野猪", "Cycle", "速转", setOf(C.HOG_RIDER),
            support = setOf(C.SKELETONS, C.ICE_SPIRITS, C.CANNON, C.LOG, C.MUSKETEER, C.ICE_GOLEM)),
        Rule("Hog", "野猪", "ExeNado", "屠夫飓风", setOf(C.HOG_RIDER, C.EXECUTIONER, C.TORNADO)),

        // ---- Siege --------------------------------------------------------
        Rule("X-Bow", "十字连弩", "IceBow", "冰法弩", setOf(C.X_BOW, C.ICE_WIZARD),
            support = setOf(C.TESLA, C.LOG, C.SKELETONS)),
        Rule("X-Bow", "十字连弩", "Cycle", "速转", setOf(C.X_BOW),
            support = setOf(C.SKELETONS, C.ICE_SPIRITS, C.TESLA, C.LOG, C.ARCHER, C.KNIGHT)),
        Rule("Mortar", "迫击炮", "Cycle", "速转", setOf(C.MORTAR),
            support = setOf(C.SKELETONS, C.ICE_SPIRITS, C.TESLA, C.LOG, C.KNIGHT)),
        Rule("Mortar", "迫击炮", "Bait", "诱饵", setOf(C.MORTAR, C.GOBLIN_BARREL),
            support = setOf(C.GOBLIN_GANG, C.PRINCESS, C.DART_GOBLIN)),

        // ---- PEKKA / Mega Knight ------------------------------------------
        Rule("Mega Knight", "超级骑士", "PEKKA", "皮卡", setOf(C.MEGA_KNIGHT, C.PEKKA),
            support = setOf(C.BANDIT, C.ELECTRO_WIZARD, C.ROYAL_GHOST, C.BATTLE_RAM)),
        Rule("PEKKA", "皮卡", "Bridge Spam", "桥头", setOf(C.PEKKA),
            support = setOf(C.BATTLE_RAM, C.BANDIT, C.ROYAL_GHOST, C.ELECTRO_WIZARD, C.MINION_HORDE, C.ZAPPIES)),
        Rule("Mega Knight", "超级骑士", "Bait", "诱饵", setOf(C.MEGA_KNIGHT),
            support = setOf(C.GOBLIN_GANG, C.SKELETON_ARMY, C.BATS, C.FIRECRACKER, C.MINER)),

        // ---- Heavy tanks ---------------------------------------------------
        Rule("Golem", "戈仑石人", "Beatdown", "推进", setOf(C.GOLEM),
            support = setOf(C.NIGHT_WITCH, C.BABY_DRAGON, C.LIGHTNING, C.TORNADO, C.MEGA_MINION)),
        Rule("Lava Hound", "熔岩猎犬", "LavaLoon", "狗球", setOf(C.LAVA_HOUND, C.BALLOON),
            support = setOf(C.TOMBSTONE, C.MEGA_MINION)),
        Rule("Lava Hound", "熔岩猎犬", "Air", "空袭", setOf(C.LAVA_HOUND),
            support = setOf(C.BABY_DRAGON, C.MINIONS, C.TOMBSTONE, C.MEGA_MINION)),
        Rule("Giant", "巨人", "Beatdown", "推进", setOf(C.GIANT),
            support = setOf(C.PRINCE, C.MUSKETEER, C.MEGA_MINION, C.MINIONS, C.WITCH)),
        Rule("Giant", "巨人", "Double Prince", "双王子", setOf(C.GIANT, C.PRINCE, C.DARK_PRINCE)),
        Rule("Royal Giant", "皇家巨人", "Cycle", "速转", setOf(C.ROYAL_GIANT),
            support = setOf(C.FISHERMAN, C.HUNTER, C.ELECTRO_DRAGON, C.MOTHER_WITCH)),
        Rule("Goblin Giant", "哥布林巨人", "Sparky", "电磁炮", setOf(C.GOBLIN_GIANT, C.SPARKY)),
        Rule("Goblin Giant", "哥布林巨人", "Beatdown", "推进", setOf(C.GOBLIN_GIANT),
            support = setOf(C.SPARKY, C.ELECTRO_DRAGON, C.MEGA_MINION)),
        Rule("Elixir Golem", "圣水戈仑", "Beatdown", "推进", setOf(C.ELIXIR_GOLEM),
            support = setOf(C.BATTLE_HEALER, C.ELECTRO_DRAGON, C.NIGHT_WITCH)),
        Rule("Electro Giant", "雷电巨人", "Beatdown", "推进", setOf(C.ELECTRO_GIANT),
            support = setOf(C.TORNADO, C.LIGHTNING, C.BABY_DRAGON, C.DARK_PRINCE)),
        Rule("Giant Skeleton", "骷髅巨人", "Bait", "诱饵", setOf(C.GIANT_SKELETON),
            support = setOf(C.CLONE, C.MIRROR, C.TORNADO)),

        // ---- Balloon / air -------------------------------------------------
        Rule("Balloon", "气球", "LumberLoon", "樵夫球", setOf(C.BALLOON, C.LUMBERJACK),
            support = setOf(C.RAGE, C.BATS, C.ICE_GOLEM, C.TORNADO)),
        Rule("Balloon", "气球", "Cycle", "速转", setOf(C.BALLOON),
            support = setOf(C.LUMBERJACK, C.RAGE, C.BATS, C.ICE_GOLEM, C.TORNADO)),
        Rule("Air", "空中", "Minion Horde", "亡灵大军", setOf(C.MINION_HORDE, C.MINIONS),
            support = setOf(C.BALLOON, C.LAVA_HOUND)),

        // ---- Graveyard -----------------------------------------------------
        Rule("Graveyard", "墓园", "Freeze", "冰冻", setOf(C.GRAVEYARD, C.FREEZE),
            support = setOf(C.KNIGHT, C.BABY_DRAGON)),
        Rule("Graveyard", "墓园", "Control", "控制", setOf(C.GRAVEYARD),
            support = setOf(C.KNIGHT, C.POISON, C.TORNADO, C.BABY_DRAGON, C.ICE_WIZARD)),

        // ---- Bait ----------------------------------------------------------
        Rule("Log Bait", "滚木诱饵", "Classic", "经典", setOf(C.GOBLIN_BARREL, C.PRINCESS),
            support = setOf(C.GOBLIN_GANG, C.ICE_SPIRITS, C.KNIGHT, C.ROCKET)),
        Rule("Bait", "诱饵", "Barrel", "飞桶", setOf(C.GOBLIN_BARREL),
            support = setOf(C.GOBLIN_GANG, C.PRINCESS, C.DART_GOBLIN, C.SKELETON_ARMY)),
        Rule("Bait", "诱饵", "Rascals", "绿林", setOf(C.RASCALS),
            support = setOf(C.GOBLIN_BARREL, C.FIRECRACKER)),

        // ---- Swarm / cycle -------------------------------------------------
        Rule("Royal Hogs", "皇家野猪", "Split", "分路", setOf(C.ROYAL_HOGS),
            support = setOf(C.FLYING_MACHINE, C.ROYAL_DELIVERY, C.EARTHQUAKE, C.ZAPPIES)),
        Rule("Three Musketeers", "三个火枪手", "Cycle", "速转", setOf(C.THREE_MUSKETEERS),
            support = setOf(C.ELIXIR_COLLECTOR, C.BATTLE_RAM, C.ICE_GOLEM, C.ROYAL_GHOST)),
        Rule("Elite Barbarians", "野蛮人精锐", "Bridge Spam", "桥头", setOf(C.ELITE_BARBARIANS),
            support = setOf(C.RAGE, C.ZAP, C.BANDIT, C.ROYAL_GHOST)),
        Rule("Ram Rider", "蛮羊骑士", "Bridge Spam", "桥头", setOf(C.RAM_RIDER),
            support = setOf(C.BANDIT, C.ROYAL_GHOST, C.ELECTRO_WIZARD)),
        Rule("Wall Breakers", "攻城炸弹人", "Cycle", "速转", setOf(C.WALL_BREAKERS),
            support = setOf(C.MINER, C.BOMB_TOWER, C.SPEAR_GOBLINS, C.BATS)),
        Rule("Sparky", "电磁炮", "Rage", "狂暴", setOf(C.SPARKY),
            support = setOf(C.GOBLIN_GIANT, C.RAGE, C.TORNADO)),
        Rule("Goblin Drill", "哥布林钻机", "Control", "控制", setOf(C.GOBLIN_DRILL),
            support = setOf(C.MINER, C.POISON, C.GOBLIN_GANG, C.BOMB_TOWER)),
        Rule("Goblin", "哥布林", "Machine", "机甲", setOf(C.GOBLIN_MACHINE),
            support = setOf(C.GOBLINSTEIN, C.GOBLIN_GANG, C.GOBLIN_DRILL, C.GOBLIN_CURSE)),
        Rule("Goblin", "哥布林", "Stein", "科学怪", setOf(C.GOBLINSTEIN),
            support = setOf(C.GOBLIN_MACHINE, C.GOBLIN_CURSE, C.GOBLIN_GANG)),
        Rule("Rune Giant", "符文巨人", "Beatdown", "推进", setOf(C.RUNE_GIANT),
            support = setOf(C.BERSERKER, C.RAGE, C.LIGHTNING)),
        Rule("Berserker", "狂战士", "Cycle", "速转", setOf(C.BERSERKER),
            support = setOf(C.RUNE_GIANT, C.RAGE)),
        Rule("Hero", "英雄", "Champion", "英雄核心",
            setOf(C.SKELETON_KING), support = setOf(C.ARCHER_QUEEN, C.GOLDEN_KNIGHT, C.MONK)),
        Rule("Hero", "英雄", "Champion", "英雄核心",
            setOf(C.ARCHER_QUEEN), support = setOf(C.SKELETON_KING, C.GOLDEN_KNIGHT, C.MONK)),
        Rule("Hero", "英雄", "Champion", "英雄核心",
            setOf(C.GOLDEN_KNIGHT), support = setOf(C.SKELETON_KING, C.ARCHER_QUEEN, C.MONK)),
        Rule("Hero", "英雄", "Champion", "英雄核心",
            setOf(C.MONK), support = setOf(C.SKELETON_KING, C.ARCHER_QUEEN, C.GOLDEN_KNIGHT)),
        Rule("Hero", "英雄", "Little Prince", "小王子", setOf(C.LITTLE_PRINCE)),
        Rule("Hero", "英雄", "Boss Bandit", "刺客头领", setOf(C.BOSS_BANDIT)),
    )

    private const val MIN_DECK_FOR_CONFIDENCE = 6

    /**
     * Classify one enemy deck. `cardIds` are base family ids; the variant flags
     * (evolution / hero) do not change the archetype, because an evolved
     * Hog Rider is still a Hog deck.
     */
    fun classify(cardIds: Collection<Int>): ArchetypeMatch {
        val deck = cardIds.filter { it > 0 }.toSet()
        if (deck.size < 4) return ArchetypeMatch.UNKNOWN

        var best: ArchetypeMatch? = null
        var bestScore = 0.0
        var bestCore = 0
        for (rule in RULES) {
            if (!deck.containsAll(rule.core)) continue
            if (rule.avoid.any { deck.contains(it) }) continue
            var score = 0.50 + 0.15 * (rule.core.size - 1)
            val hits = rule.support.count { deck.contains(it) }
            score += (0.10 * hits).coerceAtMost(0.20)
            if (rule.core.size == 1 && hits == 0) score -= 0.10
            if (deck.size < MIN_DECK_FOR_CONFIDENCE) score *= 0.6
            score = score.coerceIn(0.05, 0.95)
            val better = score > bestScore + 1e-9 ||
                (kotlin.math.abs(score - bestScore) <= 1e-9 && rule.core.size > bestCore)
            if (better) {
                bestScore = score
                bestCore = rule.core.size
                best = ArchetypeMatch(rule.family, rule.familyZh, rule.subtype, rule.subtypeZh, score)
            }
        }
        return best ?: ArchetypeMatch.UNKNOWN
    }

    /** Every rule's family, for the UI's filter list. */
    val families: List<String> get() = RULES.map { it.family }.distinct()
}
