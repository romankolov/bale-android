package ai.bale.client.data

import ai.bale.client.domain.AuthRepository
import ai.bale.client.domain.MessengerRepository
import ai.bale.client.network.*
import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** Keep network/storage implementation types out of the application DI boundary. */
internal class DataRuntime(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder().header("Origin", BaleEndpoint.ORIGIN)
            mapOf("app_version" to BaleEndpoint.VERSION, "browser_type" to "1",
                "browser_version" to "138.0.0.0", "os_type" to "3").forEach { (key, value) ->
                request.header(key, value).header("mt_$key", value)
            }
            chain.proceed(request.build())
        }.build()
    private val transport = AuthTransport(Retrofit.Builder().baseUrl(BaleEndpoint.HTTP).client(client).build().create(AuthHttpApi::class.java))
    private val socket = BaleSocket(client, scope)
    private val database = Room.databaseBuilder(context, BaleDatabase::class.java, "bale.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    private val gate = AccountGate()
    val auth: AuthRepository = DefaultAuthRepository(SessionVault(context), transport, socket, database, scope, gate)
    val messenger: MessengerRepository = DefaultMessengerRepository(auth, socket, database, scope, gate)
}
@Module
@InstallIn(SingletonComponent::class)
internal object DataModule {
    @Provides @Singleton fun runtime(@ApplicationContext context: Context) = DataRuntime(context)
    @Provides fun auth(runtime: DataRuntime): AuthRepository = runtime.auth
    @Provides fun messenger(runtime: DataRuntime): MessengerRepository = runtime.messenger
}
