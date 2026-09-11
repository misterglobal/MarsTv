package tv.mars.app.updates

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class ApkTransferTest {
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    @Test fun `copies exact signed bytes with progress`() {
        val output = ByteArrayOutputStream(); var last = 0
        ApkTransfer.copy(bytes.inputStream(), output, bytes.size.toLong(), hash) { assertTrue(it >= last); last = it }
        assertArrayEquals(bytes, output.toByteArray()); assertEquals(100, last)
    }
    @Test fun `truncated oversized and corrupted downloads fail`() {
        listOf(bytes.copyOf(bytes.size - 1), bytes + 1.toByte(), bytes.copyOf().also { it[2] = 0 }).forEach {
            assertTrue(runCatching { ApkTransfer.copy(it.inputStream(), ByteArrayOutputStream(), bytes.size.toLong(), hash) {} }.isFailure)
        }
    }
    @Test fun `cancellation interrupts streaming`() {
        val output = ByteArrayOutputStream()
        assertTrue(runCatching { ApkTransfer.copy(bytes.inputStream(), output, bytes.size.toLong(), hash) { error("cancel") } }.isFailure)
        assertTrue(output.size() < bytes.size)
    }
}
