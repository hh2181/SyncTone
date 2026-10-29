<img src="assets/branding/app-icon.png" width="88" alt="SyncTone">

# SyncTone

支持 Android 与 Windows 的本地音乐播放器。

[下载最新版本](https://github.com/hh2181/SyncTone/releases/latest)

## 功能

- 本地音乐导入与文件夹扫描
- 专辑、艺人分类与播放统计
- 逐字歌词、翻译显示与动态背景
- 联网搜索、预览和下载同步歌词，支持酷狗逐字歌词与 LRCLIB
- 局域网音乐传输与播放历史同步

## 安装与使用

- Android：支持 Android 8.0 及以上，下载安装 APK。
- Windows：下载 x64 便携版，完整解压后运行 `SyncTone.exe`。

首次使用可导入音乐文件，或添加文件夹扫描曲库。
在侧边栏“歌词更新”中选择音乐，搜索、预览歌词，设置内容顺序与匹配规则，或批量更新；也可开启缺少歌词时自动匹配。
下载的歌词保存在本机，重启后可离线使用。详见[联网歌词说明](docs/online-lyrics.md)。
升级 Windows 便携版前，请备份原目录中的 `data` 文件夹。

局域网同步在部分网络环境下仍有连接问题，详见[发行说明](docs/releases/0.5.0.md)。

## 开发

构建方法与工程说明见[开发文档](docs/development.md)。

## 字体与第三方组件

Windows 版使用 MiSans 字体，字体版权归相应权利人所有。
其他组件声明见[第三方说明](THIRD_PARTY_NOTICES.md)。
