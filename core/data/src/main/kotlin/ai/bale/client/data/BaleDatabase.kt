package ai.bale.client.data

import ai.bale.client.domain.*
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
) {
    fun domain() = ChatMessage(peerKey, rid, senderId, text, date, Delivery.valueOf(delivery), supported)
}
@Dao
internal interface MessengerDao {
    @Query("SELECT * FROM conversations WHERE accountId=:account ORDER BY date DESC")
    fun conversations(account: Int): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM messages WHERE accountId=:account AND peerKey=:peer ORDER BY date ASC, rid ASC")
    fun messages(account: Int, peer: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE accountId=:account AND peerKey=:peer AND rid=:rid")
    suspend fun message(account: Int, peer: String, rid: Long): MessageEntity?
    @Upsert suspend fun conversations(items: List<ConversationEntity>)
    @Upsert suspend fun messages(items: List<MessageEntity>)
    @Query("UPDATE messages SET delivery=:delivery, date=:date WHERE accountId=:account AND peerKey=:peer AND rid=:rid")
    suspend fun delivery(account: Int, peer: String, rid: Long, delivery: String, date: Long)
    @Query("UPDATE conversations SET unread=0 WHERE accountId=:account AND peerKey=:peer")
    suspend fun read(account: Int, peer: String)
    @Query("UPDATE messages SET delivery='Failed' WHERE delivery='Pending'")
    suspend fun recoverInterruptedOutbox()
}
@Database(entities = [ConversationEntity::class, MessageEntity::class], version = 1, exportSchema = true)
internal abstract class BaleDatabase : RoomDatabase() { abstract fun messenger(): MessengerDao }
