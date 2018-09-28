package network.xyo.mod_bluetooth_kotlin

import network.xyo.sdkcorekotlin.data.XyoUnsignedHelper

/**
 * A class to chunk bluetooth data when writing to a GATT.
 *
 * @param chunkSize The number of bytes per chunk.
 * @param bytes The bytes to chunk.
 */
class XyoBluetoothOutgoingPacket (private val chunkSize : Int, private val bytes : ByteArray) {
    private var currentIndex = 0
    private val encodedSize = XyoUnsignedHelper.createUnsignedInt(bytes.size + 4)

    /**
     * If there are more packets to send.
     */
    val canSendNext : Boolean
        get() = bytes.size != currentIndex


    /**
     * Gets the next packet to send.
     */
    fun getNext() : ByteArray {
        val startingIndex : Int
        val packet = ByteArray(Math.min(chunkSize, bytes.size - currentIndex))

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

        return packet
    }
}