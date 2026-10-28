package dev.salt.replica.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dev.salt.replica.MainActivity
import dev.salt.replica.R
import kotlinx.coroutines.*

class SyncService:Service(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var wake:PowerManager.WakeLock?=null
    override fun onCreate(){super.onCreate()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("lan-sync","局域网同步",NotificationManager.IMPORTANCE_LOW))
        startForeground(204,notification("准备同步",0,0))
        wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SyncTone:LAN").apply{acquire(6*60*60*1000L)}
        scope.launch{LanSyncRepository.get(this@SyncService).state.collect{s->manager.notify(204,notification(s.message,s.bytes,s.totalBytes))}}
        scope.launch{while(isActive){delay(15_000);LanSyncRepository.get(this@SyncService).checkLease()}}
    }
    private fun notification(text:String,bytes:Long,total:Long):Notification{
        val intent=Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open_sync",true)
        return NotificationCompat.Builder(this,"lan-sync").setSmallIcon(R.drawable.ic_nav_cast).setContentTitle("局域网音乐同步").setContentText(text)
            .setContentIntent(PendingIntent.getActivity(this,204,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(100,if(total>0)(bytes*100/total).toInt().coerceIn(0,100)else 0,total<=0).build()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_NOT_STICKY
    override fun onTimeout(startId:Int,fgsType:Int){LanSyncRepository.get(this).serviceTimedOut();stopSelf()}
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy(){scope.cancel();wake?.let{if(it.isHeld)it.release()};super.onDestroy()}
}
