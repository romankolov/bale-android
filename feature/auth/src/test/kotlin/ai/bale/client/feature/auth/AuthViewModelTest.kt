package ai.bale.client.feature.auth

import ai.bale.client.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }
    private class FakeAuth : AuthRepository {
        override val account = MutableStateFlow<Account?>(null)
        var calls = 0
        var fail = false
        override suspend fun restore() = Unit
        override suspend fun start(phone: String): AuthChallenge {
            calls++
            if (fail) error("test failure")
            return AuthChallenge("not-a-real-transaction", AuthStep.Code)
        }
        override suspend fun verify(challenge: AuthChallenge, value: String): AuthChallenge? = challenge.copy(step = AuthStep.Password)
        override suspend fun signOut() = Unit
    }
    @Test fun `double submit starts only one transaction`() = runTest(dispatcher) {
        val repository = FakeAuth()
        val model = AuthViewModel(repository)
        model.submit("989123456789"); model.submit("989123456789")
        advanceUntilIdle()
        assertEquals(1, repository.calls)
        assertEquals(AuthStep.Code, model.state.value.step)
        assertFalse(model.state.value.busy)
    }
    @Test fun `password challenge changes screen and reset clears transaction`() = runTest(dispatcher) {
        val model = AuthViewModel(FakeAuth())
        model.submit("989123456789"); advanceUntilIdle()
        model.submit("12345"); advanceUntilIdle()
        assertEquals(AuthStep.Password, model.state.value.step)
        model.reset()
        assertEquals(AuthStep.Phone, model.state.value.step)
    }
    @Test fun `failure is recoverable and sanitized`() = runTest(dispatcher) {
        val repository = FakeAuth().apply { fail = true }
        val model = AuthViewModel(repository)
        model.submit("989123456789"); advanceUntilIdle()
        assertTrue(model.state.value.error)
        assertFalse(model.state.value.busy)
        repository.fail = false
        model.submit("989123456789"); advanceUntilIdle()
        assertFalse(model.state.value.error)
        assertEquals(AuthStep.Code, model.state.value.step)
    }
}
