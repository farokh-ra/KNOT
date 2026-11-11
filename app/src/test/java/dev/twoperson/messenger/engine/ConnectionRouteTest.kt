package dev.twoperson.messenger.engine

import dev.twoperson.messenger.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionRouteTest {

    @Test
    fun `every route maps to its wire code and back`() {
        assertEquals(Protocol.ROUTE_LAN, ConnectionRoute.LAN.wireCode)
        assertEquals(Protocol.ROUTE_UDP_TRACKER, ConnectionRoute.UDP_TRACKER.wireCode)
        assertEquals(Protocol.ROUTE_DHT, ConnectionRoute.DHT.wireCode)
        assertEquals(Protocol.ROUTE_LAST_ENDPOINT, ConnectionRoute.LAST_ENDPOINT.wireCode)
        for (route in ConnectionRoute.entries) {
            assertEquals(route, ConnectionRoute.fromWireCode(route.wireCode))
        }
    }

    @Test
    fun `unknown wire codes are ignored`() {
        assertNull(ConnectionRoute.fromWireCode(0))
        assertNull(ConnectionRoute.fromWireCode(Protocol.ROUTE_LAST_ENDPOINT + 1))
        assertNull(ConnectionRoute.fromWireCode(255))
    }
}
