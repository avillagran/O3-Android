package dev.avillagran.ot3android.core.transport

import kotlinx.coroutines.flow.Flow

/**
 * Boundary between the Kotlin app and the lib-t3-rust transport.
 *
 * The implementation over the native library arrives with the AAR; no protocol logic
 * lives on the Kotlin side. Payloads are JSON strings as exchanged over the lib's C ABI.
 */
interface T3Transport {
    suspend fun connectBearer(baseUrl: String, token: String)

    suspend fun request(path: String): String

    suspend fun rpc(tag: String, payloadJson: String): String

    fun subscribe(tag: String, payloadJson: String): Flow<String>

    suspend fun close()
}
