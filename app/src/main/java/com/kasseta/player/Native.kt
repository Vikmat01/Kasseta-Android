package com.kasseta.player

/** Bridge to the FFmpeg-based native core (app/src/main/cpp). Paths are passed as UTF-8 bytes. */
object Native {
    init {
        System.loadLibrary("kasseta")
    }

    /** Opens a decoder; returns 0 on failure (err[0] gets the reason). */
    @JvmStatic external fun open(path: ByteArray, maxRate: Int, dsdFilter: Boolean, err: Array<String?>): Long

    /** Fills [out] (size >= 10) with stream info, returns the codec name. */
    @JvmStatic external fun info(handle: Long, out: IntArray): String?

    /** Reads up to [maxFrames] stereo float frames; returns frames, 0 at the end, < 0 on error. */
    @JvmStatic external fun read(handle: Long, buf: FloatArray, maxFrames: Int): Int

    @JvmStatic external fun seek(handle: Long, ms: Long): Int
    @JvmStatic external fun setGain(handle: Long, gain: Float)
    @JvmStatic external fun close(handle: Long)

    /** "key\u0000value\u0000..." blob with tags and stream info, or null. */
    @JvmStatic external fun probe(path: ByteArray): ByteArray?

    /** Embedded cover picture bytes, or null. */
    @JvmStatic external fun cover(path: ByteArray): ByteArray?

    fun pathBytes(path: String): ByteArray = path.toByteArray(Charsets.UTF_8)
}

/** Stream information of an open decoder. */
class StreamInfo(a: IntArray, val codec: String) {
    val outRate = a[0]
    val outChannels = a[1]
    val srcRate = a[2]
    val srcChannels = a[3]
    val bits = a[4]
    val isDsd = a[5] != 0
    val dsdRate = a[6]
    val durationMs: Long = (a[7].toLong() and 0x7fffffffL) or (a[8].toLong() shl 31)
    val kbps = a[9]
}
