package dev.salt.replica.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicScanRulesTest {
    @Test fun acceptsAudioDespiteGenericProviderMimeOrMissingExtension() {
        assertTrue(isMusicDocument("TRACK.FLAC", "application/octet-stream"))
        assertTrue(isMusicDocument("track.Mp3", ""))
        assertTrue(isMusicDocument("no-extension", "audio/mp4"))
        assertTrue(isMusicDocument("track.opus", "audio/ogg"))
    }

    @Test fun excludesPlaylistsLyricsAndUnrelatedFiles() {
        assertFalse(isMusicDocument("playlist.m3u", "audio/x-mpegurl"))
        assertFalse(isMusicDocument("playlist", "audio/x-scpls"))
        assertFalse(isMusicDocument("track.lrc", "audio/mpeg"))
        assertFalse(isMusicDocument("cover.jpg", "image/jpeg"))
        assertFalse(isMusicDocument("film.mp4", "video/mp4"))
    }
}
