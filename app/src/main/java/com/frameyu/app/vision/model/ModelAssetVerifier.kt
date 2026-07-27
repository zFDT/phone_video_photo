package com.frameyu.app.vision.model

import java.io.InputStream
import java.security.MessageDigest

object ModelAssetVerifier {
    private const val BUFFER_SIZE = 8 * 1024

    /**
     * 分块计算 [input] 的 SHA-256，并与 [descriptor] 中的固定摘要比较。
     *
     * [input] 由调用方持有，本方法不会关闭该流。
     */
    fun matches(descriptor: ModelDescriptor, input: InputStream): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead == -1) break
            if (bytesRead > 0) digest.update(buffer, 0, bytesRead)
        }
        val actualSha256 = digest.digest().joinToString("") { byte -> "%02X".format(byte) }
        return actualSha256 == descriptor.sha256
    }
}
