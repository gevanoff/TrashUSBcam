package com.gevanoff.trashcam

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wire framing shared by i4season/Soulear Wi-Fi cameras. */
internal object SoulearProtocol {
    const val COMMAND_PORT = 10005
    const val STREAM_INIT_PORT = 10006
    const val NOTIFICATION_PORT = 10007

    const val TYPE_GET_DEVICE_INFO = 0x0001
    const val TYPE_OPEN_VIDEO = 0x0004

    const val HEADER_SIZE = 12
    const val VIDEO_CHUNK_HEADER_SIZE = 16
    const val MAGIC = 0xffeeffee.toInt()

    val magicBytes: ByteArray = byteArrayOf(
        0xee.toByte(),
        0xff.toByte(),
        0xee.toByte(),
        0xff.toByte()
    )

    fun request(
        id: Int,
        type: Int,
        payload: ByteArray = byteArrayOf(),
        declaredPayloadLength: Int = payload.size
    ): ByteArray {
        require(id in 0..0xffff) { "Message id must fit in 16 bits" }
        require(type in 0..0xffff) { "Message type must fit in 16 bits" }
        require(payload.size <= 0xffff) { "Payload is too large" }
        require(declaredPayloadLength in 0..0xffff) { "Declared payload length must fit in 16 bits" }

        return ByteBuffer.allocate(HEADER_SIZE + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(MAGIC)
            .putShort(id.toShort())
            .putShort(type.toShort())
            .put(1)
            .put(0)
            .putShort(declaredPayloadLength.toShort())
            .put(payload)
            .array()
    }

    /**
     * The i4season implementation appends the receive port while declaring a zero-length body.
     * The resulting 14-byte packet is required by this camera firmware to start the stream.
     */
    fun openVideoRequest(id: Int, receivePort: Int): ByteArray {
        require(receivePort in 1..0xffff) { "Receive port must fit in 16 bits" }
        val port = ByteBuffer.allocate(2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(receivePort.toShort())
            .array()
        return request(id, TYPE_OPEN_VIDEO, port, declaredPayloadLength = 0)
    }

    fun hasMagic(data: ByteArray, length: Int = data.size): Boolean {
        if (length < magicBytes.size || length > data.size) return false
        return magicBytes.indices.all { data[it] == magicBytes[it] }
    }

    fun parseResponse(data: ByteArray, length: Int = data.size): Response? {
        if (length < HEADER_SIZE || length > data.size || !hasMagic(data, length)) return null
        val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        buffer.int // magic
        val id = buffer.short.toInt() and 0xffff
        val type = buffer.short.toInt() and 0xffff
        val marker = buffer.get().toInt() and 0xff
        val errorCode = buffer.get().toInt() and 0xff
        val payloadLength = buffer.short.toInt() and 0xffff
        if (payloadLength > buffer.remaining()) return null
        val payload = ByteArray(payloadLength)
        buffer.get(payload)
        return Response(id, type, marker, errorCode, payload)
    }

    fun parseDeviceInfo(payload: ByteArray): DeviceInfo? {
        if (payload.size < DEVICE_INFO_TEXT_SIZE) return null
        return DeviceInfo(
            vendor = payload.asCString(1, 32),
            product = payload.asCString(33, 32),
            firmwareVersion = payload.asCString(65, 16),
            ssid = payload.asCString(81, 32)
        )
    }

    fun parseVideoChunk(data: ByteArray, length: Int = data.size): VideoChunk? {
        if (length <= VIDEO_CHUNK_HEADER_SIZE || length > data.size) return null
        val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val kind = buffer.get().toInt() and 0xff
        val sequence = buffer.get().toInt() and 0xff
        val frameId = buffer.get().toInt() and 0xff
        val lastChunk = buffer.get().toInt() and 0xff
        val chunkMarker = buffer.get().toInt() and 0xff
        buffer.position(12)
        val width = buffer.short.toInt() and 0xffff
        val height = buffer.short.toInt() and 0xffff
        if (chunkMarker == 0 || width == 0 || height == 0) return null
        return VideoChunk(
            kind = kind,
            sequence = sequence,
            frameId = frameId,
            lastChunk = lastChunk != 0,
            chunkMarker = chunkMarker,
            width = width,
            height = height,
            jpegPayload = data.copyOfRange(VIDEO_CHUNK_HEADER_SIZE, length)
        )
    }

    private fun ByteArray.asCString(offset: Int, maximumLength: Int): String {
        val endExclusive = (offset until minOf(size, offset + maximumLength))
            .firstOrNull { this[it] == 0.toByte() }
            ?: minOf(size, offset + maximumLength)
        return copyOfRange(offset, endExclusive).toString(Charsets.US_ASCII).trim()
    }

    data class Response(
        val id: Int,
        val type: Int,
        val marker: Int,
        val errorCode: Int,
        val payload: ByteArray
    )

    data class DeviceInfo(
        val vendor: String,
        val product: String,
        val firmwareVersion: String,
        val ssid: String
    )

    data class VideoChunk(
        val kind: Int,
        val sequence: Int,
        val frameId: Int,
        val lastChunk: Boolean,
        /** Packet index on some i4season firmware; total frame packet count on this Soulear. */
        val chunkMarker: Int,
        val width: Int,
        val height: Int,
        val jpegPayload: ByteArray
    )

    private const val DEVICE_INFO_TEXT_SIZE = 113
}
