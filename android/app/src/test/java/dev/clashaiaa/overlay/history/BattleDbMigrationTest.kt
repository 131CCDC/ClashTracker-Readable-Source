package dev.clashaiaa.overlay.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleDbMigrationTest {

    @Test
    fun `v1 to v2 migration is append-only and creates card play schema`() {
        val sql = BattleDb.MIGRATION_1_TO_2.joinToString("\n").uppercase()
        assertEquals(4, BattleDb.SCHEMA_VERSION)
        assertFalse(sql.contains("DROP "))
        assertTrue(sql.contains("ALTER TABLE BATTLES ADD COLUMN WINNER_OWNER INTEGER"))
        assertTrue(sql.contains("ALTER TABLE BATTLES ADD COLUMN NATIVE_RESULT_RAW INTEGER"))
        assertTrue(sql.contains("CREATE TABLE CARD_PLAYS"))
        assertTrue(sql.contains("REFERENCES BATTLES(BATTLE_UID) ON DELETE CASCADE"))
        assertTrue(sql.contains("UNIQUE(BATTLE_UID, EVENT_KEY)"))
        assertFalse(sql.contains("CARD_ID INTEGER NOT NULL"))
        assertTrue(sql.contains("IDX_CARD_PLAYS_BATTLE_UID"))
        assertTrue(sql.contains("IDX_CARD_PLAYS_CARD_ID"))
        assertTrue(sql.contains("IDX_CARD_PLAYS_SERVER_TICK"))
        assertTrue(sql.contains("IDX_CARD_PLAYS_OWNER"))
    }

    @Test
    fun `v2 to v3 migration is append-only and adds canonical fields`() {
        val sql = BattleDb.MIGRATION_2_TO_3.joinToString("\n").uppercase()
        assertFalse(sql.contains("DROP "))
        assertTrue(sql.contains("CANONICAL_BATTLE_ID"))
        assertTrue(sql.contains("BATTLE_FINGERPRINT"))
        assertTrue(sql.contains("PERSONAL_RECORD_ELIGIBLE"))
        assertTrue(sql.contains("MERGED_SOURCES_JSON"))
        assertTrue(sql.contains("IDX_BATTLES_FINGERPRINT"))
    }

    @Test fun `v3 to v4 migration adds pending reconciliation without guessing results`() {
        val sql = BattleDb.MIGRATION_3_TO_4.joinToString("\n").uppercase()
        assertFalse(sql.contains("DROP "))
        assertTrue(sql.contains("NEEDS_HISTORY_RECONCILIATION"))
        assertTrue(sql.contains("RECONCILIATION_ATTEMPTS"))
        assertTrue(sql.contains("WHERE STATUS = 'INCOMPLETE'"))
        assertFalse(sql.contains("RESULT = 'LOSS'"))
    }
}
