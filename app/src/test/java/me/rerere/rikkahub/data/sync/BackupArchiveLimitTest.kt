package me.rerere.rikkahub.data.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupArchiveLimitTest {
    @Test
    fun acceptsExactlyTheConfiguredByteLimit() {
        val sink = ByteArrayOutputStream()
        val output = LimitedBackupOutputStream(sink, 4)

        output.write(byteArrayOf(1, 2, 3, 4))

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), sink.toByteArray())
    }

    @Test
    fun rejectsWritesThatWouldExceedTheConfiguredByteLimit() {
        val sink = ByteArrayOutputStream()
        val output = LimitedBackupOutputStream(sink, 4)
        output.write(byteArrayOf(1, 2, 3))

        assertThrows(IllegalArgumentException::class.java) {
            output.write(byteArrayOf(4, 5))
        }

        assertArrayEquals(byteArrayOf(1, 2, 3), sink.toByteArray())
    }

    @Test
    fun countsSingleByteWrites() {
        val output = LimitedBackupOutputStream(ByteArrayOutputStream(), 1)
        output.write(1)

        assertThrows(IllegalStateException::class.java) {
            output.write(2)
        }
    }
    @Test
    fun copiesStreamAtExactlyTheConfiguredLimit() = runBlocking {
        val target = File.createTempFile("backup-limit-", ".bin")
        try {
            copyBackupStreamToFile(ByteArrayInputStream(byteArrayOf(1, 2, 3)), target, maxBytes = 3)
            assertTrue(target.isFile)
            assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
        } finally {
            target.delete()
        }
    }

    @Test
    fun rejectsOversizedStreamAndDeletesPartialTarget() = runBlocking {
        val target = File.createTempFile("backup-limit-", ".bin")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    copyBackupStreamToFile(
                        ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                        target,
                        maxBytes = 3,
                    )
                }
            }
            assertFalse(target.exists())
        } finally {
            target.delete()
        }
    }

}
