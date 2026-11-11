package dev.twoperson.messenger.ui

import dev.twoperson.messenger.R
import dev.twoperson.messenger.engine.ConnectionRoute
import dev.twoperson.messenger.engine.ConnectionState
import dev.twoperson.messenger.engine.ConnectionStatus
import dev.twoperson.messenger.engine.DirectKind
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusUiTest {

    @Test
    fun `connected status names the route that actually carried the link`() {
        assertEquals(
            R.string.state_connected_via_lan,
            StatusUi.of(ConnectionStatus(ConnectionState.CONNECTED, route = ConnectionRoute.LAN)).label
        )
        assertEquals(
            R.string.state_connected_via_udp_tracker,
            StatusUi.of(
                ConnectionStatus(ConnectionState.CONNECTED, route = ConnectionRoute.UDP_TRACKER)
            ).label
        )
        assertEquals(
            R.string.state_connected_via_dht,
            StatusUi.of(ConnectionStatus(ConnectionState.CONNECTED, route = ConnectionRoute.DHT)).label
        )
        assertEquals(
            R.string.state_connected_via_last_endpoint,
            StatusUi.of(
                ConnectionStatus(ConnectionState.CONNECTED, route = ConnectionRoute.LAST_ENDPOINT)
            ).label
        )
    }

    @Test
    fun `connected status falls back to the network kind when the route is unknown`() {
        assertEquals(
            R.string.state_connected_lan,
            StatusUi.of(
                ConnectionStatus(ConnectionState.CONNECTED, direct = DirectKind.LOCAL_NETWORK)
            ).label
        )
        assertEquals(
            R.string.state_connected_internet,
            StatusUi.of(
                ConnectionStatus(ConnectionState.CONNECTED, direct = DirectKind.INTERNET)
            ).label
        )
    }

    @Test
    fun `a stale route never leaks into a non-connected status`() {
        assertEquals(
            R.string.state_discovering,
            StatusUi.of(
                ConnectionStatus(ConnectionState.DISCOVERING, route = ConnectionRoute.DHT)
            ).label
        )
        assertEquals(
            R.string.state_unable,
            StatusUi.of(
                ConnectionStatus(ConnectionState.UNABLE_TO_CONNECT, route = ConnectionRoute.DHT)
            ).label
        )
    }
}
