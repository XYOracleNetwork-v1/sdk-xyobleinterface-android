package network.xyo.mod_bluetooth_kotlin

import org.junit.Assert
import org.junit.Test

class XyoIncomingOutgoingPacketTest {

    @Test
    fun testPackets() {
        val starting = ByteArray(1000)
        val outgoing = XyoBluetoothOutgoingPacket(20, starting)
        val firstIn = outgoing.getNext()
        val incoming = XyoBluetoothIncomingPacket(firstIn)

        while (!incoming.done) {
            if (outgoing.canSendNext) {
                val out = outgoing.getNext()
                val inPacket = incoming.addPacket(out)

                if (inPacket != null) {
                    Assert.assertArrayEquals(starting, inPacket)
                }
            }
        }
    }
}