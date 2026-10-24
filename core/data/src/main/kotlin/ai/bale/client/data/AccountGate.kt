package ai.bale.client.data

import kotlinx.coroutines.sync.Mutex

/** Serializes session transitions with database inserts so logout cannot race late writes. */
internal class AccountGate { val mutex = Mutex() }
