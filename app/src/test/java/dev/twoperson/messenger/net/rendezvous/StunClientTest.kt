package dev.twoperson.messenger.net.rendezvous

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class StunClientTest {

    @Test
    fun parsesXorMappedAddressFromBindingSuccess() = runBlocking {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = DatagramSocket(0, loopback)
        val clientSocket = DatagramSocket(0, loopback)
        val mappedIp = byteArrayOf(203.toByte(), 0, 113, 7)
        val mappedPort = 54321

        val serverThread = Thread {
            try {
                val buffer = ByteArray(128)
                val request = DatagramPacket(buffer, buffer.size)
                server.receive(request)
                val transaction = request.data.copyOfRange(
                    request.offset + 8,
                    request.offset + 20
                )
                val response = bindingSuccess(transaction, mappedIp, mappedPort)
                server.send(
                    DatagramPacket(
                        response,
                        response.size,
                        request.socketAddress
                    )
                )
            } catch (e: Exception) {
            }
        }.apply {
            isDaemon = true
            start()
        }

        val client = StunClient(clientSocket, servers = listOf("127.0.0.1" to server.localPort))
        val pump = Thread {
            val buffer = ByteArray(1500)
            while (!clientSocket.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    clientSocket.receive(packet)
                    client.onPacket(
                        packet.data.copyOfRange(packet.offset, packet.offset + packet.length),
                        packet.socketAddress as InetSocketAddress
                    )
                } catch (e: Exception) {
                    if (clientSocket.isClosed) return@Thread
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        try {
            val endpoint = client.publicEndpoint(budgetMs = 3_000)
            assertNotNull(endpoint)
            assertEquals(mappedPort, endpoint!!.port)
            assertEquals(InetAddress.getByAddress(mappedIp), endpoint.address)
            assertEquals(endpoint, client.cachedEndpoint())
        } finally {
            clientSocket.close()
            server.close()
            serverThread.interrupt()
            pump.interrupt()
        }
    }

    @Test
    fun ignoresAResponseWithAForeignTransaction() = runBlocking {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = DatagramSocket(0, loopback)
        val clientSocket = DatagramSocket(0, loopback)

        val serverThread = Thread {
            try {
                val buffer = ByteArray(128)
                val request = DatagramPacket(buffer, buffer.size)
                server.receive(request)
                val response = bindingSuccess(
                    ByteArray(12) { 0x5A },
                    byteArrayOf(203.toByte(), 0, 113, 7),
                    40000
                )
                server.send(DatagramPacket(response, response.size, request.socketAddress))
            } catch (e: Exception) {
            }
        }.apply {
            isDaemon = true
            start()
        }
        val client = StunClient(clientSocket, servers = listOf("127.0.0.1" to server.localPort))
        val pump = Thread {
            val buffer = ByteArray(1500)
            while (!clientSocket.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    clientSocket.receive(packet)
                    client.onPacket(
                        packet.data.copyOfRange(packet.offset, packet.offset + packet.length),
                        packet.socketAddress as InetSocketAddress
                    )
                } catch (e: Exception) {
                    if (clientSocket.isClosed) return@Thread
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        try {
            assertEquals(null, client.publicEndpoint(budgetMs = 2_500))
        } finally {
            clientSocket.close()
            server.close()
            serverThread.interrupt()
            pump.interrupt()
        }
    }

    private fun bindingSuccess(
        transaction: ByteArray,
        ip: ByteArray,
        port: Int
    ): ByteArray {
        val xorPort = port xor (MAGIC ushr 16)
        val xorIp = ByteArray(4) { index ->
            (ip[index].toInt() xor ((MAGIC ushr (24 - index * 8)) and 0xFF)).toByte()
        }
        return ByteBuffer.allocate(32)
            .putShort(0x0101)
            .putShort(12)
            .putInt(MAGIC)
            .put(transaction)
            .putShort(0x0020)
            .putShort(8)
            .putShort(0x0001)
            .putShort(xorPort.toShort())
            .put(xorIp)
            .array()
    }

    companion object {
        private const val MAGIC = 0x2112A442
    }
}
