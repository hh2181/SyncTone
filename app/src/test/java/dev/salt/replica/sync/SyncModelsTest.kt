package dev.salt.replica.sync

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class SyncModelsTest {
    @Test fun wifiUplinkAndHotspotAddressesAreBothShownWithoutDuplicates(){
        val addresses=listOf("10.137.203.222","10.81.15.68","10.137.203.222","192.168.1.20")
            .map(InetAddress::getByName)
        assertEquals(listOf("10.137.203.222","10.81.15.68","192.168.1.20"),syncLanIpv4Addresses(addresses))
    }
    @Test fun manualConnectionAddressesExcludeLoopbackPublicLinkLocalAndIpv6(){
        val addresses=listOf("127.0.0.1","0.0.0.0","169.254.2.3","8.8.8.8","198.18.0.1","26.116.116.15","::1","fe80::1234","fd00::1")
            .map(InetAddress::getByName)
        assertTrue(syncLanIpv4Addresses(addresses).isEmpty())
        assertEquals(listOf("172.16.0.1","192.168.1.1"),syncLanIpv4Addresses(listOf("172.16.0.1","192.168.1.1").map(InetAddress::getByName)))
    }
    @Test fun contentHashIgnoresTheSourceFilename(){
        val bytes="complete music file".toByteArray()
        assertEquals(digest(bytes),bytes.inputStream().use(::fileDigest))
        assertNotEquals(digest(bytes),digest(bytes+1))
    }
    @Test fun receivedNamesCannotEscapeTheMusicFolderAndVersionsRemainDistinct(){
        val name=safeMusicName("../../music\\..\\Song.mp3","a".repeat(64),false)
        assertEquals("Song-aaaaaaaaaa.mp3",name)
        assertNotEquals(name,safeMusicName("Song.mp3","b".repeat(64),false))
        assertTrue(safeMusicName("<>:*?.flac","c".repeat(64),true).endsWith(".flac"))
    }
    @Test fun manifestsRoundTripAndRejectInvalidHashOrFileLength(){
        val song=SyncSong("a".repeat(64),"Song","Artist","Album","song.flac",1024,true)
        assertEquals(song,SyncSong.parse(song.json()))
        assertThrows(IllegalArgumentException::class.java){SyncSong.parse(song.json().put("hash","../../secret"))}
        assertThrows(IllegalArgumentException::class.java){SyncSong.parse(song.json().put("size",-1))}
    }
}
