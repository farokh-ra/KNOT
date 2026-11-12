package dev.twoperson.messenger.engine

import dev.twoperson.messenger.protocol.Protocol

/**
 * The discovery path that produced the endpoint of a live link. Every device runs every path,
 * and this names the one concrete route that actually carried the link for the connected status.
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
