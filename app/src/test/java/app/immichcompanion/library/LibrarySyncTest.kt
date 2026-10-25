package app.immichcompanion.library

import app.immichcompanion.library.LibrarySync.Companion.toRemoteAsset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class LibrarySyncTest {
    private fun asset(overrides: String = "") = Json.decodeFromString<JsonObject>(
        """{"id":"a1","ownerId":"u1","originalFileName":"IMG_1.HEIC","thumbhash":null,"checksum":"c",
            "fileCreatedAt":"2026-10-25T14:28:00.000Z","fileModifiedAt":null,"createdAt":null,
            "localDateTime":null,"duration":null,"type":"IMAGE","deletedAt":null,"isFavorite":true,
            "visibility":"timeline","livePhotoVideoId":null,"stackId":null,"libraryId":null,
            "width":4032,"height":3024,"isEdited":false $overrides}""",
    )

    @Test fun mapsVisiblePhoto() {
        val a = asset().toRemoteAsset()!!
        assertEquals("image/heic", a.mimeType)
        assertEquals(Instant.parse("2026-10-25T14:28:00Z").toEpochMilli(), a.takenAt)
        assertEquals(true, a.isFavorite)
        assertEquals(4032, a.width)
    }

    @Test fun mapsVideoDuration() {
        val a = asset(""","type":"VIDEO","originalFileName":"clip.mov","duration":3000""").toRemoteAsset()!!
        assertEquals("video/quicktime", a.mimeType)
        assertEquals(3000, a.durationMs)
    }

    @Test fun hidesTrashedHiddenAndLockedAssets() {
        assertNull(asset(""","deletedAt":"2026-10-25T15:00:00.000Z"""").toRemoteAsset())
        assertNull(asset(""","visibility":"hidden"""").toRemoteAsset())
        assertNull(asset(""","visibility":"locked"""").toRemoteAsset())
        assertNull(asset(""","type":"AUDIO"""").toRemoteAsset())
    }

    @Test fun mimeTypeFallsBackByKind() {
        assertEquals("image/jpeg", LibrarySync.mimeTypeFor("photo", isVideo = false))
        assertEquals("video/mp4", LibrarySync.mimeTypeFor("clip.xyz", isVideo = true))
        // A video with an image extension (e.g. a motion photo part) is still a video.
        assertEquals("video/mp4", LibrarySync.mimeTypeFor("x.jpg", isVideo = true))
    }
}
