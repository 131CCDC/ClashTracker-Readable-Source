package dev.clashaiaa.overlay.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchetypeClassifierTest {

    private fun deck(vararg ids: Int) = ids.toList()

    @Test
    fun `miner poison is recognised from the pair`() {
        val match = ArchetypeClassifier.classify(
            deck(26000032, 28000009, 26000010, 26000030, 28000011, 26000049, 26000011, 27000004),
        )
        assertEquals("Miner", match.archetype)
        assertEquals("Poison", match.subtype)
        assertTrue("confidence should clear the single-core floor", match.confidence > 0.5)
    }

    @Test
    fun `two cores beat the generic single-core rule`() {
        val match = ArchetypeClassifier.classify(
            deck(26000055, 26000004, 26000046, 26000042, 26000036, 26000050, 28000008, 28000000),
        )
        assertEquals("Mega Knight", match.archetype)
        assertEquals("PEKKA", match.subtype)
    }

    @Test
    fun `hog with earthquake is EQ hog not plain cycle`() {
        val match = ArchetypeClassifier.classify(
            deck(26000021, 28000014, 28000011, 26000014, 26000030, 26000038, 27000000, 26000010),
        )
        assertEquals("Hog", match.archetype)
        assertEquals("Earthquake", match.subtype)
    }

    @Test
    fun `lumberloon is split out of the balloon family`() {
        val match = ArchetypeClassifier.classify(
            deck(26000006, 26000035, 28000002, 26000049, 26000038, 28000012, 26000010, 27000003),
        )
        assertEquals("Balloon", match.archetype)
        assertEquals("LumberLoon", match.subtype)
    }

    @Test
    fun `icebow is split out of the x-bow family`() {
        val match = ArchetypeClassifier.classify(
            deck(27000008, 26000023, 27000006, 28000011, 26000010, 26000030, 26000001, 28000012),
        )
        assertEquals("X-Bow", match.archetype)
        assertEquals("IceBow", match.subtype)
    }

    @Test
    fun `an unknown pile of cards stays unknown instead of guessing`() {
        val match = ArchetypeClassifier.classify(
            deck(26000000, 26000001, 26000002, 26000005, 26000013, 26000019, 28000001, 28000002),
        )
        assertEquals("Unknown", match.archetype)
        assertEquals(0.0, match.confidence, 1e-9)
    }

    @Test
    fun `too few cards is not a deck`() {
        val match = ArchetypeClassifier.classify(deck(26000032, 28000009))
        assertEquals("Unknown", match.archetype)
    }

    @Test
    fun `every rule's family is exposed for the ui`() {
        assertTrue(ArchetypeClassifier.families.contains("Miner"))
        assertTrue(ArchetypeClassifier.families.contains("Hog"))
        assertTrue(ArchetypeClassifier.families.contains("Unknown").not())
    }

    @Test
    fun `confidence never claims certainty`() {
        val match = ArchetypeClassifier.classify(
            deck(26000032, 28000009, 26000010, 26000030, 28000011, 26000049, 26000011, 27000004),
        )
        assertTrue(match.confidence <= 0.95)
        assertNotNull(match.display)
    }
}
