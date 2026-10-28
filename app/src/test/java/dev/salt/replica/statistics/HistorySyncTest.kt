package dev.salt.replica.statistics

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class HistorySyncTest {
    private val track=StatTrack(songIdentity("Song","Artist"),"Song","Artist","Album")
    private fun record(id:String="record-a",origin:String="a",ms:Long=300_000,revision:Long=1)=HistoryRecord(id,origin,"session-$id","span",track,1_000_000,1_000_000+ms,ms,revision)
    @Test fun differentFormatsAndAlbumsUseTitleAndArtistIdentity(){
        assertEquals(songIdentity(" Song ","ＡＲＴＩＳＴ"),songIdentity("song","artist"))
        assertNotEquals(songIdentity("Song","Artist"),songIdentity("Song","Other"))
        assertNotEquals(songIdentity("Song (Live)","Artist"),songIdentity("Song","Artist"))
    }
    @Test fun circularSyncPreservesRecordsWithoutMultiplyingOverlappingListening(){
        val a=mutableMapOf("record-a" to record());val b=mutableMapOf("record-b" to record("record-b","b"));val c=mutableMapOf<String,HistoryRecord>()
        fun merge(target:MutableMap<String,HistoryRecord>,rows:List<HistoryRecord>,origin:String){rows.forEach{if(acceptsHistory(target[it.id],it,origin))target[it.id]=it}}
        repeat(3){merge(b,a.values.toList(),"b");merge(c,b.values.toList(),"c");merge(a,c.values.toList(),"a")}
        assertEquals(2,a.size);assertEquals(a,b);assertEquals(a,c)
        assertEquals(600_000L,a.values.sumOf{it.ms})
    }
    @Test fun activeSpanRevisionsReplaceOlderCopiesAndNeverTrustEchoedLocalUpdates(){
        val old=record();val newer=record(ms=400_000,revision=2)
        assertTrue(acceptsHistory(old,newer,"b"));assertFalse(acceptsHistory(newer,old,"b"))
        assertFalse(acceptsHistory(old,newer,"a"));assertFalse(acceptsHistory(old,old,"b"))
        assertFalse(acceptsHistory(old,newer.copy(origin="forged"),"b"))
        assertFalse(acceptsHistory(old,newer.copy(ms=100),"b"))
    }
    @Test fun originalRecordIdentitySurvivesSerializationAndRelay(){
        val source=record();val received=HistoryRecord.parse(JSONObject(source.json().toString()))
        assertEquals(source,received);assertFalse(acceptsHistory(source,received,"b"))
    }
    @Test fun invalidDurationsAndChangedTrackIdentityAreRejected(){
        val valid=record();assertTrue(valid.valid())
        assertFalse(acceptsHistory(null,valid.copy(ms=-1),"b"))
        assertFalse(acceptsHistory(null,valid.copy(end=valid.start),"b"))
        assertFalse(acceptsHistory(null,valid.copy(ms=Long.MAX_VALUE),"b"))
        assertFalse(acceptsHistory(valid,valid.copy(track=track.copy(title="Other"),revision=2),"b"))
    }
}
