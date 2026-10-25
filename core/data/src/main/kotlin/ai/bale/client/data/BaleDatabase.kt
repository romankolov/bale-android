package ai.bale.client.data

import ai.bale.client.domain.*
import androidx.paging.PagingSource
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations", primaryKeys = ["accountId", "peerKey"])
internal data class ConversationEntity(
    val accountId: Int, val peerKey: String, val peerId: Int, val kind: Int, val accessHash: Long,
    val title: String, val preview: String, val date: Long, val unread: Int,
) {
    fun domain() = Conversation(Peer(peerId, kind, accessHash), title, preview, date, unread)
}
@Entity(tableName = "messages", primaryKeys = ["accountId", "peerKey", "rid"], indices = [Index(value = ["accountId", "peerKey", "date"])])
internal data class MessageEntity(
    val accountId: Int, val peerKey: String, val rid: Long, val senderId: Int,
    val text: String, val date: Long, val delivery: String, val supported: Boolean,
    @ColumnInfo(defaultValue = "X''") val metadata: ByteArray = byteArrayOf(),
    @ColumnInfo(defaultValue = "0") val replySeq: Long = 0,
    @ColumnInfo(defaultValue = "0") val received: Boolean = false,
    @ColumnInfo(defaultValue = "0") val read: Boolean = false,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)
@Entity(tableName = "message_tombstones", primaryKeys = ["accountId", "peerKey", "date", "rid"], indices = [Index(value = ["accountId", "peerKey", "rid"])])
internal data class MessageTombstone(val accountId: Int, val peerKey: String, val date: Long, val rid: Long)
@Dao
internal interface MessengerDao {
    @Query("SELECT * FROM conversations WHERE accountId=:account ORDER BY date DESC, peerKey ASC")
    fun conversations(account: Int): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE accountId=:account AND peerKey=:peer")
    suspend fun conversation(account: Int, peer: String): ConversationEntity?
    @Query("SELECT MIN(date) FROM conversations WHERE accountId=:account")
    suspend fun oldestConversation(account: Int): Long?
    @Query("SELECT * FROM messages WHERE accountId=:account AND peerKey=:peer ORDER BY date ASC, rid ASC")
    fun messages(account: Int, peer: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE accountId=:account AND peerKey=:peer ORDER BY date DESC, rid DESC")
    fun pagedMessages(account: Int, peer: String): PagingSource<Int, MessageEntity>
    @Query("SELECT MIN(date) FROM messages WHERE accountId=:account AND peerKey=:peer AND delivery='Sent'")
    suspend fun oldestMessage(account: Int, peer: String): Long?
    @Query("SELECT * FROM messages WHERE accountId=:account AND peerKey=:peer AND rid=:rid")
    suspend fun message(account: Int, peer: String, rid: Long): MessageEntity?
    @Upsert suspend fun tombstones(items: List<MessageTombstone>)
    @Query("SELECT EXISTS(SELECT 1 FROM message_tombstones WHERE accountId=:account AND peerKey=:peer AND ((date=:date AND date>0) OR (rid=:rid AND rid!=0)))")
    suspend fun isDeleted(account: Int, peer: String, date: Long, rid: Long): Boolean
    @Upsert suspend fun conversations(items: List<ConversationEntity>)
    @Upsert suspend fun messages(items: List<MessageEntity>)
    @Query("UPDATE messages SET delivery=:delivery, date=:date WHERE accountId=:account AND peerKey=:peer AND rid=:rid")
    suspend fun delivery(account: Int, peer: String, rid: Long, delivery: String, date: Long)
    @Query("UPDATE conversations SET unread=0 WHERE accountId=:account AND peerKey=:peer")
    suspend fun read(account: Int, peer: String)
    @Query("UPDATE conversations SET title=:title WHERE accountId=:account AND peerKey=:peer")
    suspend fun renamePeer(account: Int, peer: String, title: String)
    /**
     * Live-list bump for a newly observed message: newest date, fresh preview (kept when the
     * message has no textual body) and an unread tick for incoming senders only. Returns the
     * affected row count so callers can seed conversations that are not in the local list yet.
     */
    @Query(
        "UPDATE conversations SET date = MAX(date, :date), " +
            "preview = CASE WHEN :preview = '' THEN preview ELSE :preview END, " +
            "unread = unread + CASE WHEN :incoming = 1 THEN 1 ELSE 0 END " +
            "WHERE accountId=:account AND peerKey=:peer",
    )
    suspend fun bumpOnMessage(account: Int, peer: String, date: Long, preview: String, incoming: Int): Int
    @Query("UPDATE conversations SET unread=:unread WHERE accountId=:account AND peerKey=:peer")
    suspend fun unread(account: Int, peer: String, unread: Int)
    @Query("UPDATE messages SET received=1, `read`=CASE WHEN :isRead THEN 1 ELSE `read` END WHERE accountId=:account AND peerKey=:peer AND date<=:date AND senderId=:account AND delivery='Sent'")
    suspend fun receipt(account: Int, peer: String, date: Long, isRead: Boolean)
    @Query("UPDATE messages SET deleted=1, text='', metadata=X'', supported=0 WHERE accountId=:account AND peerKey=:peer AND date IN (:dates)")
    suspend fun deleteDates(account: Int, peer: String, dates: List<Long>)
    @Query("UPDATE messages SET deleted=1, text='', metadata=X'', supported=0 WHERE accountId=:account AND peerKey=:peer AND rid=:rid")
    suspend fun deleteRid(account: Int, peer: String, rid: Long)
    @Query("DELETE FROM messages WHERE accountId=:account AND peerKey=:peer AND rid=:rid AND delivery='Failed'")
    suspend fun discardPending(account: Int, peer: String, rid: Long): Int
    @Query("UPDATE messages SET delivery='Failed' WHERE delivery='Pending'")
    suspend fun recoverInterruptedOutbox()
}
@Database(entities = [ConversationEntity::class, MessageEntity::class, MessageTombstone::class], version = 3, exportSchema = true)
internal abstract class BaleDatabase : RoomDatabase() { abstract fun messenger(): MessengerDao }
