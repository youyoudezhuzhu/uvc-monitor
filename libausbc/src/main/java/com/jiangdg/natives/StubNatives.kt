package com.jiangdg.natives

/**
 * Stub — 替代 libnative 模块（MP3 编码/视频旋转）。
 * 本项目仅需音频播放，不依赖 lame mp3 编码；签名与 AUSBC 调用保持一致。
 */
object LameMp3 {
    fun lameInit(sampleRate: Int, channelCount: Int, outSampleRate: Int, bitRate: Int, degree: Int): Int = -1
    fun lameEncode(data: ShortArray, byteArray: ByteArray?, size: Int, mp3Buf: ByteArray): Int = 0
    fun lameFlush(mp3Buf: ByteArray): Int = 0
    fun lameClose() {}
}

object YUVUtils {
    fun nativeRotateNV21(data: ByteArray, width: Int, height: Int, degree: Int) {}
    fun nv21ToYuv420sp(data: ByteArray, width: Int, height: Int): ByteArray = data
}
