package tv.mars.app.updates

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Enforces the signed size while streaming; never buffers the APK in memory. */
internal object ApkTransfer {
    fun copy(input: InputStream, output: OutputStream, expectedSize: Long, expectedHash: String, progress: (Int) -> Unit) {
        require(expectedSize in 1..UpdateManifestVerifier.MAX_APK_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            check(total <= expectedSize) { "APK exceeds signed size" }
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
            progress((100 * total / expectedSize).toInt())
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(total == expectedSize && actual.equals(expectedHash, true)) { "APK checksum or size mismatch" }
    }
}
