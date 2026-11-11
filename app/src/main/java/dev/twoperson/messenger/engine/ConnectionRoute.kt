package dev.twoperson.messenger.engine

import dev.twoperson.messenger.protocol.Protocol

/**
 * The discovery path that produced the endpoint of a live link. Unlike the user's preferred
 * [dev.twoperson.messenger.data.ConnectionMethod], this always names one concrete route, which
 * is what the connected status reports even when Automatic was selected.
 */
enum class ConnectionRoute(val wireCode: Int) {
    LAN(Protocol.ROUTE_LAN),
    UDP_TRACKER(Protocol.ROUTE_UDP_TRACKER),
    DHT(Protocol.ROUTE_DHT),
    LAST_ENDPOINT(Protocol.ROUTE_LAST_ENDPOINT);

    companion object {
        fun fromWireCode(code: Int): ConnectionRoute? =
            entries.firstOrNull { it.wireCode == code }
    }
}
