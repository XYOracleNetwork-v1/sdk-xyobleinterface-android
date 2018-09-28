package network.xyo.mod_bluetooth_kotlin

import org.junit.Assert
import org.junit.Test

class XyoIncommingOutcommingPacketTest {

    @Test
    fun testPackets() {
        val starting = ByteArray(421)
        val outgoing = XyoBluetoothOutgoingPacket(20, starting)
        val firstIn = outgoing.getNext()
        println(bytesToString(firstIn))
        val inn = XyoBluetoothIncomingPacket(firstIn)

        println(bytesToString(starting))
        while (!inn.done) {
            if (outgoing.canSendNext) {
                val out = outgoing.getNext()
                val innn = inn.addPacket(out)

                if (innn != null) {
                    println("here1")
                    Assert.assertArrayEquals(starting, innn)
                }
            }
        }
    }

    fun bytesToString(bytes: ByteArray?): String {
        val sb = StringBuilder()
        val it = bytes!!.iterator()
        sb.append("0x")
        while (it.hasNext()) {
            sb.append(String.format("%02X ", it.next()))
        }

        return sb.toString()
    }
}