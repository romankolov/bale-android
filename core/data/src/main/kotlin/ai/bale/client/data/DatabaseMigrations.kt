package ai.bale.client.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Preserve existing real-account caches and pending outbox rows. Never destructive fallback. */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN metadata BLOB NOT NULL DEFAULT X''")
        db.execSQL("ALTER TABLE messages ADD COLUMN replySeq INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN received INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN `read` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
    }
}

internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS message_tombstones (accountId INTEGER NOT NULL, peerKey TEXT NOT NULL, date INTEGER NOT NULL, rid INTEGER NOT NULL, PRIMARY KEY(accountId,peerKey,date,rid))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_message_tombstones_accountId_peerKey_rid ON message_tombstones(accountId,peerKey,rid)")
    }
}
