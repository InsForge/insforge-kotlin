package dev.insforge.realtime

import io.socket.client.IO
import io.socket.client.Socket

internal typealias RealtimeSocketListener = (Array<out Any?>) -> Unit

internal interface RealtimeSocket {
    fun on(event: String, listener: RealtimeSocketListener)
    fun onAnyIncoming(listener: RealtimeSocketListener)
    fun off()
    fun emit(event: String, data: Any)
    fun emitWithAck(event: String, data: Any, callback: RealtimeSocketListener)
    fun connect()
    fun disconnect()
    fun isConnected(): Boolean
    fun id(): String?
}

internal fun interface RealtimeSocketFactory {
    fun create(baseUrl: String, options: IO.Options): RealtimeSocket
}

internal object DefaultRealtimeSocketFactory : RealtimeSocketFactory {
    override fun create(baseUrl: String, options: IO.Options): RealtimeSocket {
        return SocketIoRealtimeSocket(IO.socket(baseUrl, options))
    }
}

private class SocketIoRealtimeSocket(
    private val socket: Socket
) : RealtimeSocket {
    override fun on(event: String, listener: RealtimeSocketListener) {
        socket.on(event) { args -> listener(args) }
    }

    override fun onAnyIncoming(listener: RealtimeSocketListener) {
        socket.onAnyIncoming { args -> listener(args) }
    }

    override fun off() {
        socket.off()
    }

    override fun emit(event: String, data: Any) {
        socket.emit(event, data)
    }

    override fun emitWithAck(event: String, data: Any, callback: RealtimeSocketListener) {
        socket.emit(event, arrayOf(data)) { args -> callback(args) }
    }

    override fun connect() {
        socket.connect()
    }

    override fun disconnect() {
        socket.disconnect()
    }

    override fun isConnected(): Boolean = socket.connected()

    override fun id(): String? = socket.id()
}
