package ai.bale.client.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DatabaseMigrationTest {
    @Test fun versionOneCacheAndOutboxSurviveMigrationWithoutDestructiveFallback() = verifyMigration(1)
    @Test fun versionTwoCacheAndOutboxSurviveTombstoneMigration() = verifyMigration(2)

    private fun verifyMigration(startVersion: Int) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-$startVersion-3-fixture.db"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { legacy ->
            legacy.execSQL("CREATE TABLE conversations (accountId INTEGER NOT NULL, peerKey TEXT NOT NULL, peerId INTEGER NOT NULL, kind INTEGER NOT NULL, accessHash INTEGER NOT NULL, title TEXT NOT NULL, preview TEXT NOT NULL, date INTEGER NOT NULL, unread INTEGER NOT NULL, PRIMARY KEY(accountId,peerKey))")
            legacy.execSQL("CREATE TABLE messages (accountId INTEGER NOT NULL, peerKey TEXT NOT NULL, rid INTEGER NOT NULL, senderId INTEGER NOT NULL, text TEXT NOT NULL, date INTEGER NOT NULL, delivery TEXT NOT NULL, supported INTEGER NOT NULL, PRIMARY KEY(accountId,peerKey,rid))")
            legacy.execSQL("CREATE INDEX index_messages_accountId_peerKey_date ON messages(accountId,peerKey,date)")
            legacy.execSQL("INSERT INTO conversations VALUES(7,'1:42',42,1,123,'Preserved conversation','cached',1000,2)")
            legacy.execSQL("INSERT INTO messages VALUES(7,'1:42',9007199254740993,7,'pending reply',1000,'Pending',1)")
            if (startVersion == 2) {
                legacy.execSQL("ALTER TABLE messages ADD COLUMN metadata BLOB NOT NULL DEFAULT X''")
                listOf("replySeq", "received", "read", "deleted").forEach {
                    legacy.execSQL("ALTER TABLE messages ADD COLUMN `$it` INTEGER NOT NULL DEFAULT 0")
                }
            }
            legacy.version = startVersion
        }
        val migrated = Room.databaseBuilder(context, BaleDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
        try {
            val dao = migrated.messenger()
            assertEquals("Preserved conversation", dao.conversations(7).first().single().title)
            val message = dao.messages(7, "1:42").first().single()
            assertEquals(9007199254740993, message.rid)
            assertEquals("pending reply", message.text)
            assertEquals("Pending", message.delivery)
            assertTrue(message.metadata.isEmpty())
            assertFalse(message.deleted)
            assertFalse(message.read)
            assertEquals(3, migrated.openHelper.readableDatabase.version)
            dao.recoverInterruptedOutbox()
            assertEquals("Failed", dao.message(7, "1:42", message.rid)?.delivery)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
