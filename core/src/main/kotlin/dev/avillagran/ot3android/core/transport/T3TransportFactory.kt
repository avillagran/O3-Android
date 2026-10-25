package dev.avillagran.ot3android.core.transport

/**
 * Creates [T3Transport]s: the app holds one per environment, each with its own handle and
 * streams, and closes it when the environment goes away. Tests inject a [FakeT3TransportFactory].
 */
fun interface T3TransportFactory {
    fun create(): T3Transport
}

/** A [NativeT3Transport] over a new lib-t3-rust handle for every [create]. */
class NativeT3TransportFactory : T3TransportFactory {
    override fun create(): T3Transport = NativeT3Transport()
}
