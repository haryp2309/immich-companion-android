package app.immichcompanion.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupLogicTest {
    @Test fun sha1IsBase64LikeImmich() {
        assertEquals("qvTGHdzF6KLavt4PO0gs2a6pQ00=", MediaFiles.sha1("hello".byteInputStream()))
    }

    @Test fun recordIsDoneOnlyForUnchangedUploadedFile() {
        val media = LocalMedia(
            id = 1, name = "a.jpg", mimeType = "image/jpeg", isVideo = false,
            size = 100, takenAt = 0, modifiedAt = 5, durationMs = 0, isFavorite = false, bucketId = "b",
        )
        val hashed = BackupRecord(1, modifiedAt = 5, size = 100, checksum = "c")
        assertFalse(hashed.isDone(media))
        assertTrue(hashed.copy(remoteId = "r").isDone(media))
        assertTrue(hashed.copy(skipReason = "unsupported-format").isDone(media))
        // Edited since the upload: back it up again.
        assertFalse(hashed.copy(remoteId = "r").isDone(media.copy(modifiedAt = 6)))
        assertFalse(hashed.copy(remoteId = "r").isDone(media.copy(size = 101)))
    }
}
