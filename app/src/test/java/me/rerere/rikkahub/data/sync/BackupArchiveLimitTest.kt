package me.rerere.rikkahub.data.sync

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
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
}
