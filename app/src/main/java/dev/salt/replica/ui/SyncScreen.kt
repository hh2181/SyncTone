package dev.salt.replica.ui

import dev.salt.buttons.SyncToneButton as Button
import dev.salt.buttons.SyncToneIconButton as IconButton
import dev.salt.buttons.SyncToneTextButton as TextButton

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.salt.replica.playback.PlayerStore
import dev.salt.replica.sync.*
import dev.salt.replica.ui.theme.hyperClickable

private fun syncSize(bytes:Long):String=if(bytes>=1_073_741_824L)"%.2f GB".format(bytes/1_073_741_824.0)else "%.1f MB".format(bytes/1_048_576.0)

@Composable
fun SyncScreen(store:PlayerStore,onMenu:()->Unit,modifier:Modifier){
    val context=LocalContext.current
    val repository=remember{LanSyncRepository.get(context)}
    val pageOwner=remember{Any()}
    val state by repository.state.collectAsStateWithLifecycle()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val color=MaterialTheme.colorScheme
    val green=if(color.background==Color(0xFFF5F6F8))Color(0xFF249773)else Color(0xFF64CBA7)
    var rename by remember{mutableStateOf(false)}
    var direct by remember{mutableStateOf(false)}
    var forget by remember{mutableStateOf(false)}
    var search by remember{mutableStateOf("")}
    var text by remember{mutableStateOf("")}
    var permissionError by remember{mutableStateOf(false)}
    val networkPermissions=remember{
        buildList{
            if(Build.VERSION.SDK_INT<=28)add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if(Build.VERSION.SDK_INT>=37&&runCatching{context.packageManager.getPermissionInfo("android.permission.ACCESS_LOCAL_NETWORK",0).protectionLevel and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE==android.content.pm.PermissionInfo.PROTECTION_DANGEROUS}.getOrDefault(false))add("android.permission.ACCESS_LOCAL_NETWORK")
        }
    }
    var granted by remember{mutableStateOf(networkPermissions.all{ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED})}
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){
        granted=networkPermissions.all{p->ContextCompat.checkSelfPermission(context,p)==PackageManager.PERMISSION_GRANTED};permissionError=!granted
    }
    fun permissions(){val missing=buildList{addAll(networkPermissions.filter{ContextCompat.checkSelfPermission(context,it)!=PackageManager.PERMISSION_GRANTED});if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)add(Manifest.permission.POST_NOTIFICATIONS)}
        if(missing.isNotEmpty())permissionLauncher.launch(missing.toTypedArray())else{granted=true;permissionError=false;repository.start()}}
    LaunchedEffect(Unit){permissions()}
    DisposableEffect(lifecycle,granted){
        fun update(){repository.setPageOpen(pageOwner,granted&&lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))}
        val observer=LifecycleEventObserver{_,_->update()};lifecycle.addObserver(observer);update()
        onDispose{lifecycle.removeObserver(observer);repository.setPageOpen(pageOwner,false)}
    }
    val transferring=state.phase==SyncPhase.TRANSFERRING
    val busy=transferring||state.remoteSending
    val connected=state.peer!=null&&state.phase !in listOf(SyncPhase.COMPARING,SyncPhase.WAITING)
    val selected=state.songs.filter{it.hash in state.selected}
    val visibleSongs=state.songs.filter{search.isBlank()||"${it.title} ${it.artist} ${it.album}".contains(search.trim(),true)}
    val pairing=state.phase in listOf(SyncPhase.COMPARING,SyncPhase.WAITING)||state.prompt!=null
    val topLabel=when{state.remoteSending->"正在发送";!granted->"需要授权";else->when(state.phase){
        SyncPhase.SCANNING->"正在发现设备";SyncPhase.COMPARING->"正在对比音乐库";SyncPhase.WAITING->"等待配对";SyncPhase.TRANSFERRING->"正在同步";SyncPhase.PAUSED->"同步已暂停";SyncPhase.FAILED->"同步未完成";SyncPhase.COMPLETE->"同步完成";else->if(state.serving)"已就绪"else "未连接局域网"}}
    CompositionLocalProvider(LocalContentColor provides color.onSurface) {
    LazyColumn(modifier.padding(horizontal=16.dp),contentPadding=PaddingValues(top=4.dp,bottom=18.dp)){
        item("header"){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){IconButton(onMenu){Icon(Icons.Default.Menu,"打开侧栏")};Text("连接",fontSize=13.sp,color=color.onSurfaceVariant,modifier=Modifier.weight(1f));Icon(Icons.Default.Wifi,null,tint=green,modifier=Modifier.padding(end=10.dp).size(19.dp))}
            Text("局域网同步",fontSize=27.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=8.dp,bottom=18.dp))
        }
        item("status"){
            Column(Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(26.dp)).background(if(state.phase==SyncPhase.COMPLETE)green.copy(alpha=.10f)else color.primaryContainer).padding(horizontal=17.dp,vertical=10.dp)){
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Sync,null,Modifier.size(17.dp),tint=if(state.phase==SyncPhase.COMPLETE)green else color.primary);Spacer(Modifier.width(7.dp));Text(topLabel,fontSize=12.sp,color=color.primary,modifier=Modifier.weight(1f));IconButton({text=state.localName;rename=true},Modifier.size(34.dp)){Icon(Icons.Default.Edit,"修改本机名称",Modifier.size(18.dp),tint=color.onSurfaceVariant)}}
                Text(state.localName,fontSize=23.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                AnimatedContent(state.message,transitionSpec={fadeIn(tween(180)).togetherWith(fadeOut(tween(120)))},label="sync-status"){message->Text(if(permissionError)"需要局域网或存储权限"else message.ifBlank{"等待连接局域网"},fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)}
                if(state.totalBytes>0&&state.phase in listOf(SyncPhase.TRANSFERRING,SyncPhase.PAUSED,SyncPhase.FAILED,SyncPhase.COMPLETE)){
                    LinearProgressIndicator(progress={ (state.bytes.toFloat()/state.totalBytes).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth().padding(top=6.dp).height(4.dp).clip(RoundedCornerShape(4.dp)),color=if(state.phase==SyncPhase.COMPLETE)green else color.primary)
                    Text("${syncSize(state.bytes)} / ${syncSize(state.totalBytes)} · ${state.completed.size}/${selected.size} 首",fontSize=11.sp,color=color.onSurfaceVariant,modifier=Modifier.padding(top=3.dp))
                }else Text(state.address.ifBlank{if(permissionError)"权限尚未授予"else "等待局域网连接"},fontSize=11.sp,color=color.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
            }
            state.peer?.let{peer->
                Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically){
                    Text("当前设备 · ${peer.name}",fontSize=12.sp,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
                    TextButton({forget=true},enabled=!busy&&!pairing){Text("移除配对",color=color.error,fontSize=12.sp)}
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Text(state.comparison?.statusText()?:"尚未完成曲库比对",fontSize=12.sp,color=color.onSurfaceVariant,modifier=Modifier.weight(1f))
                    IconButton(repository::refreshComparison,enabled=!busy&&!pairing){Icon(Icons.Default.Refresh,"重新对比曲库",Modifier.size(20.dp))}
                }
                Text("MP3 / FLAC · 按文件内容比对",fontSize=10.sp,color=color.onSurfaceVariant)
            }
            if(state.error!=null||permissionError)Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically){Text(state.error?:"需要授权才能同步",fontSize=12.sp,color=color.error,modifier=Modifier.weight(1f));IconButton({if(!granted)permissions()else repository.scan()}){Icon(Icons.Default.Refresh,"重新连接",tint=color.primary)}}
        }
        item("devices-heading"){Row(Modifier.fillMaxWidth().padding(top=23.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically){Text("附近设备",fontSize=17.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f));Text("${state.peers.size} 台",fontSize=12.sp,color=color.onSurfaceVariant);IconButton({text="";direct=true},Modifier.size(34.dp)){Icon(Icons.Default.Link,"使用局域网地址连接",Modifier.size(18.dp),tint=color.primary)}}}
        val tiles=listOf<SyncPeer?>(null)+state.peers
        itemsIndexed(tiles.chunked(2),key={index,_->"device-row-$index"}){_,row->
            Row(Modifier.fillMaxWidth().padding(bottom=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                row.forEach{peer->val isSelected=peer!=null&&state.peer?.id==peer.id
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(26.dp)).background(if(peer==null)color.primaryContainer else if(isSelected)green.copy(alpha=.10f)else color.surface)
                        .hyperClickable(enabled=!busy&&state.phase!=SyncPhase.COMPARING&&state.phase!=SyncPhase.WAITING){if(peer==null){if(granted)repository.scan()else permissions()}else repository.select(peer)}){
                        Column(Modifier.fillMaxSize().padding(16.dp)){
                            Row(Modifier.fillMaxWidth()){Icon(if(peer==null)Icons.Default.Radar else Icons.Default.PhoneAndroid,null,Modifier.size(30.dp),tint=if(isSelected)green else color.primary);Spacer(Modifier.weight(1f));if(isSelected)Icon(Icons.Default.CheckCircle,"已选中",Modifier.size(20.dp),tint=green)}
                            Spacer(Modifier.weight(1f))
                            Text(peer?.name?:if(state.phase==SyncPhase.SCANNING)"扫描中…"else "扫描设备",fontSize=16.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis,color=if(peer==null)color.primary else color.onSurface)
                            Text(if(peer==null)"发现附近设备"else if(isSelected)"已选中"else "点击连接",fontSize=11.sp,color=color.onSurfaceVariant,modifier=Modifier.padding(top=6.dp))
                        }
                    }
                };if(row.size==1)Spacer(Modifier.weight(1f))
            }
        }
        item("songs-heading"){
            Row(Modifier.fillMaxWidth().padding(top=9.dp),verticalAlignment=Alignment.CenterVertically){Text("待接收歌曲",fontSize=17.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f));if(visibleSongs.isNotEmpty()){Checkbox(visibleSongs.all{it.hash in state.selected},{repository.selectAll(visibleSongs.map{it.hash}.toSet())},enabled=!busy&&!pairing);Text(if(search.isBlank())"全选"else "筛选结果",fontSize=12.sp,color=color.primary)}}
            if(state.songs.isNotEmpty())OutlinedTextField(search,{search=it},modifier=Modifier.fillMaxWidth().padding(top=10.dp,bottom=4.dp),singleLine=true,label={Text("搜索待接收歌曲")},leadingIcon={Icon(Icons.Default.Search,null)},enabled=!busy)
            Text(if(connected)"已选 ${selected.size} 首 · ${syncSize(selected.sumOf{it.size})} · 跳过 ${state.skipped} 首相同文件"else "选择设备后显示新增歌曲",fontSize=11.sp,color=color.onSurfaceVariant,modifier=Modifier.padding(top=4.dp,bottom=12.dp))
            Row(Modifier.fillMaxWidth().padding(bottom=9.dp),horizontalArrangement=Arrangement.spacedBy(9.dp)){
                Button(onClick={if(transferring)repository.pause()else repository.begin()},enabled=connected&&!state.remoteSending&&state.phase !in listOf(SyncPhase.COMPARING,SyncPhase.WAITING),contentPadding=PaddingValues(horizontal=12.dp,vertical=13.dp),modifier=Modifier.weight(1f)){
                    Icon(if(transferring)Icons.Default.Pause else if(state.phase==SyncPhase.PAUSED||state.phase==SyncPhase.FAILED)Icons.Default.PlayArrow else Icons.Default.Download,null,Modifier.size(19.dp));Spacer(Modifier.width(8.dp));Text(if(transferring)"暂停同步"else if(state.songs.isEmpty()&&(state.comparison?.missingLocal?:0)>0)"载入待接收歌曲"else if(state.phase==SyncPhase.PAUSED||state.phase==SyncPhase.FAILED)"继续同步"else if(selected.isEmpty())"同步播放历史"else "一键同步 · ${selected.size} 首",fontSize=14.sp,maxLines=1)
                }
                if(state.phase in listOf(SyncPhase.TRANSFERRING,SyncPhase.PAUSED,SyncPhase.FAILED))IconButton({repository.cancelTransfer()},Modifier.size(48.dp).clip(CircleShape).background(color.surfaceVariant)){Icon(Icons.Default.Close,"取消同步")}
            }
            Text("内部存储 / Music / sync",fontSize=11.sp,color=color.onSurfaceVariant,modifier=Modifier.padding(bottom=14.dp))
        }
        if(visibleSongs.isEmpty())item("songs-empty"){
            Column(Modifier.fillMaxWidth().heightIn(min=150.dp).clip(RoundedCornerShape(24.dp)).background(color.surface).padding(20.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
                if(state.phase==SyncPhase.COMPARING)CircularProgressIndicator(Modifier.size(25.dp),strokeWidth=2.dp)else Icon(if(connected)Icons.Default.CheckCircle else Icons.Default.MusicNote,null,tint=if(connected)green else color.primary,modifier=Modifier.size(29.dp))
                Text(if(search.isNotBlank())"没有匹配的歌曲"else if(state.phase==SyncPhase.COMPARING)"正在对比音乐库"else if(connected)syncEmptyLibraryText(state.comparison)else "尚未选择设备",fontSize=13.sp,color=color.onSurfaceVariant,modifier=Modifier.padding(top=11.dp))
            }
        }
        itemsIndexed(visibleSongs,key={_,song->song.hash}){index,song->
            val shape=RoundedCornerShape(topStart=if(index==0)24.dp else 0.dp,topEnd=if(index==0)24.dp else 0.dp,bottomStart=if(index==visibleSongs.lastIndex)24.dp else 0.dp,bottomEnd=if(index==visibleSongs.lastIndex)24.dp else 0.dp)
            Column(Modifier.fillMaxWidth().clip(shape).background(color.surface)){
                Row(Modifier.fillMaxWidth().padding(end=13.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){
                    Checkbox(song.hash in state.selected,{repository.toggle(song.hash)},enabled=!busy)
                    val bitmap=store.tracks.firstOrNull{it.album==song.album&&it.artist==song.artist}?.artwork
                    Artwork(bitmap,Modifier.size(39.dp).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f)){Text(song.title,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text("${song.artist.ifBlank{"未知歌手"}} · ${if(song.flac)"FLAC"else "MP3"} · ${syncSize(song.size)}",fontSize=11.sp,color=color.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp))}
                    if(song.hash in state.completed)Icon(Icons.Default.CheckCircle,"已接收",Modifier.size(18.dp),tint=green)else if(song.hash==state.currentHash&&transferring)CircularProgressIndicator(Modifier.size(17.dp),strokeWidth=2.dp)
                };if(index!=visibleSongs.lastIndex)HorizontalDivider(Modifier.padding(start=48.dp,end=14.dp),color=color.outlineVariant)
            }
        }
        item("history"){
            Row(Modifier.fillMaxWidth().padding(horizontal=4.dp,vertical=19.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Icon(Icons.Default.History,null,Modifier.size(23.dp),tint=color.primary)
                Column(Modifier.weight(1f)){Text("播放历史",fontSize=13.sp);Text(if(state.phase==SyncPhase.COMPLETE)"双向合并完成 · 更新 ${state.historyChanges} 条"else "双向合并 · 同时播放分别累计",fontSize=11.sp,color=color.onSurfaceVariant,modifier=Modifier.padding(top=3.dp))}
                Icon(if(state.phase==SyncPhase.COMPLETE)Icons.Default.CheckCircle else Icons.Default.SyncAlt,null,Modifier.size(19.dp),tint=green)
            }
        }
    }
    if(forget)AlertDialog(onDismissRequest={forget=false},title={Text("移除配对")},text={Text("移除与 ${state.peer?.name.orEmpty()} 的配对？")},confirmButton={TextButton({repository.forgetPeer();forget=false}){Text("移除")}},dismissButton={TextButton({forget=false}){Text("取消")}})
    }
    state.prompt?.let{prompt->AlertDialog(onDismissRequest=repository::cancelPair,
        title={Text("${if(prompt.incoming)"来自"else "连接"} ${prompt.peerName}",fontSize=20.sp)},
        text={Column{Text("配对码",fontSize=13.sp,color=color.onSurfaceVariant);Text(prompt.code.chunked(3).joinToString(" "),fontSize=34.sp,fontWeight=FontWeight.Medium,modifier=Modifier.fillMaxWidth().padding(vertical=19.dp));Text(if(prompt.localConfirmed)"等待对方确认"else "确认两台设备显示相同配对码",fontSize=12.sp,color=color.onSurfaceVariant)}},
        confirmButton={TextButton(repository::confirmPair,enabled=!prompt.localConfirmed){Text(if(prompt.localConfirmed)"已确认"else "确认配对")}},dismissButton={TextButton(repository::cancelPair){Text("取消")}},shape=RoundedCornerShape(28.dp))}
    if(rename||direct)AlertDialog(onDismissRequest={rename=false;direct=false},title={Text(if(rename)"本机名称"else "使用地址连接")},text={Column{if(direct)Text("本机地址：${state.address}",fontSize=12.sp,modifier=Modifier.padding(bottom=12.dp));OutlinedTextField(text,{text=it},label={Text(if(rename)"设备名称"else "IP:端口")},singleLine=true)}},confirmButton={TextButton({if(rename)repository.rename(text)else repository.connectAddress(text);rename=false;direct=false}){Text(if(rename)"保存"else "连接")}},dismissButton={TextButton({rename=false;direct=false}){Text("取消")}},shape=RoundedCornerShape(28.dp))
}
