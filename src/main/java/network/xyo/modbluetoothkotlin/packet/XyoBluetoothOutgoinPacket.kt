package network.xyo.modbluetoothkotlin.packet

import network.xyo.sdkobjectmodelkotlin.objects.toHexString
import java.nio.ByteBuffer

/**
 * A class to chunk bluetooth data when writing to a GATT.
 *
 * @param chunkSize The number of bytes per chunk.
 * @param bytes The bytes to chunk.
 */
class XyoBluetoothOutgoingPacket (private val chunkSize : Int, private val bytes : ByteArray) {
    private var currentIndex = 0
    private val encodedSize = ByteBuffer.allocate(4).putInt(bytes.size + 4).array()

    /**
     * If there are more packets to send.
     */
    val canSendNext : Boolean
        get() {
            return bytes.size != currentIndex
        }


    /**
     * Gets the next packet to send.
     */
    fun getNext() : ByteArray {
        val startingIndex : Int
        val packet = ByteArray(Math.min(chunkSize, bytes.size - currentIndex))

        println(packet.size)

        if (currentIndex == 0) {
            for (i in 0 until encodedSize.size) {
                packet[i] = encodedSize[i]
            }
            startingIndex = 4
        } else {
            startingIndex = 0
        }

        for (i in startingIndex until packet.size) {
            packet[i] = bytes[currentIndex]
            currentIndex++
        }

        println("ALL BYTES: ${bytes.toHexString()}")
        println("PACKET   : ${packet.toHexString()}")

        return packet
    }
}