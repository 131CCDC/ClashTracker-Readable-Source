package dev.clashaiaa.overlay.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleDbMigrationTest {

    @Test
    fun `v1 to v2 migration is append-only and creates card play schema`() {
        val sql = BattleDb.MIGRATION_1_TO_2.joinToString("\n").uppercase()
        assertEquals(2, BattleDb.SCHEMA_VERSION)
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
}
