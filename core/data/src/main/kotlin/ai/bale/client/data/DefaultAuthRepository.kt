package ai.bale.client.data

import ai.bale.client.domain.*
import ai.bale.client.network.*
import ai.bale.client.protocol.*
import com.google.protobuf.BoolValue
import com.google.protobuf.ByteString
import android.util.Base64
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock

internal class DefaultAuthRepository(
    private val vault: SessionVault,
    private val transport: AuthTransport,
    private val socket: BaleSocket,
    private val database: BaleDatabase,
    scope: CoroutineScope,
    gate: AccountGate,
    private val mediaDir: File? = null,
    private val notifier: MessageNotifier? = null,
) : AuthRepository {
    private val mutableAccount = MutableStateFlow<Account?>(null)
    override val account = mutableAccount.asStateFlow()
    private val lock = gate.mutex
    @Volatile private var session: SavedSession? = null
    private var lastRefresh = System.currentTimeMillis()

    fun currentToken(): String? = session?.token

    init {
        scope.launch {
            socket.state.collect { if (it == Connection.Unauthorized) signOut() }
        }
        scope.launch {
            // JWT claims only schedule refresh; they are NOT treated as proof of authenticity.
            while (isActive) {
                delay(60_000)
                val current = session ?: continue
                val expiry = try {
                    val payload = current.token.split('.')[1]
                    JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)).getLong("exp") * 1000
                } catch (_: Exception) { Long.MAX_VALUE }
                val now = System.currentTimeMillis()
                if (socket.state.value == Connection.Connected && (expiry - now < 5 * 60_000 || now - lastRefresh > 60 * 60_000)) {
                    try { refreshToken(); lastRefresh = now } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* retry on next connected minute; never log token/error payload */ }
                }
            }
        }
    }

    override suspend fun restore(): Unit = lock.withLock {
        database.messenger().recoverInterruptedOutbox()
        val saved = vault.load()
        if (saved == null) {
            notifier?.clear()
            withContext(Dispatchers.IO) {
                database.clearAllTables()
                mediaDir?.deleteRecursively()
                Unit
            }
        } else {
            session = saved
            mutableAccount.value = saved.account
            socket.start(saved.token)
        }
    }

    override suspend fun start(phone: String): AuthChallenge = lock.withLock {
        val request = Auth_StartPhoneAuth_Request.newBuilder()
            .setPhoneNumber(normalizePhone(phone).toLong()).setAppId(4).setApiKey(BaleEndpoint.APP_KEY)
            .setDeviceHash(ByteString.copyFromUtf8(vault.deviceId())).setDeviceTitle("Bale Android custom client")
            .setSendCodeType(1).build()
        val response = Auth_StartPhoneAuth_Response.parseFrom(transport.call("StartPhoneAuth", request))
        if (response.transactionHash.isBlank()) throw ProtocolException("Missing authentication transaction")
        if (response.activationType !in 0..1) throw ProtocolException("Unsupported activation method")
        AuthChallenge(response.transactionHash, if (response.activationType == 1) AuthStep.Password else AuthStep.Code)
    }

    override suspend fun verify(challenge: AuthChallenge, value: String): AuthChallenge? = lock.withLock {
        require(value.isNotBlank())
        val (method, request) = when (challenge.step) {
            AuthStep.Code -> "ValidateCode" to Auth_ValidateCode_Request.newBuilder()
                .setTransactionHash(challenge.transaction).setCode(value.map { it.digitToIntOrNull()?.toString() ?: it.toString() }.joinToString(""))
                .setIsJwt(BoolValue.of(true)).build()
            AuthStep.Password -> "ValidatePassword" to Auth_ValidatePassword_Request.newBuilder()
                .setTransactionHash(challenge.transaction).setPassword(value).setIsJwt(BoolValue.of(true)).build()
            AuthStep.Registration -> "SignUp" to Auth_SignUp_Request.newBuilder()
                .setTransactionHash(challenge.transaction).setName(value.trim()).build()
            AuthStep.Phone -> error("Start authentication first")
        }
        val raw = try { transport.call(method, request) } catch (error: RpcException) {
            val reason = error.reason.lowercase()
            return@withLock when {
                challenge.step == AuthStep.Code && "password" in reason -> challenge.copy(step = AuthStep.Password)
                challenge.step == AuthStep.Code && "unoccupied" in reason -> challenge.copy(step = AuthStep.Registration)
                else -> throw error
            }
        }
        val response = Msg_index_fe3fa18088__86923_A.parseFrom(raw)
        if (!response.hasJwt() || response.jwt.value.isBlank() || !response.hasUser()) throw ProtocolException("Incomplete login response")
        val saved = SavedSession(Account(response.user.id, response.user.name), response.jwt.value)
        vault.save(saved)
        session = saved
        mutableAccount.value = saved.account
        socket.start(saved.token)
        null
    }

    private suspend fun refreshToken() = lock.withLock {
        val current = session ?: return@withLock
        val result = Auth_GetJWTToken_Response.parseFrom(socket.rpc("bale.auth.v1.Auth", "GetJWTToken", Auth_GetJWTToken_Request.getDefaultInstance()))
        if (result.jwt.value.isBlank()) throw ProtocolException("Empty refreshed token")
        val updated = current.copy(token = result.jwt.value)
        vault.save(updated)
        session = updated
        socket.start(updated.token)
    }

    override suspend fun sessions(): List<AuthSessionInfo> {
        val api = lock.withLock {
            if (session == null) return emptyList()
            DirectoryApi(socket, socket.sessionId)
        }
        return api.authSessions().map {
            AuthSessionInfo(
                id = it.id,
                appTitle = it.appTitle.ifBlank { "Bale" },
                deviceTitle = it.deviceTitle.ifBlank { "Unknown device" },
                location = it.authLocation,
                authTimeSeconds = it.authTime.toLong(),
                lastActiveAt = it.lastActivityAt.value,
            )
        }
    }

    override suspend fun terminateSession(id: Int) {
        val api = lock.withLock {
            if (session == null) return
            DirectoryApi(socket, socket.sessionId)
        }
        api.terminateSession(id)
    }

    override suspend fun terminateOtherSessions() {
        val api = lock.withLock {
            if (session == null) return
            DirectoryApi(socket, socket.sessionId)
        }
        api.terminateAllSessions()
    }

    override suspend fun signOut(): Unit = lock.withLock {
        // Attempt bounded remote revocation when connected; never block offline logout.
        if (session != null && socket.state.value == Connection.Connected) {
            runCatching { withTimeoutOrNull(2_000) { DirectoryApi(socket, socket.sessionId).remoteSignOut() } }
        }
        mutableAccount.value = null
        session = null
        notifier?.clear()
        socket.stop()
        vault.clear()
        withContext(Dispatchers.IO) {
            database.clearAllTables()
            mediaDir?.deleteRecursively()
        }
    }
}
