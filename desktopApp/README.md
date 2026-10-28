# SyncTone for Windows 0.5.0

Windows x64 本地音乐播放器，使用 Compose Multiplatform/JVM 和 libVLC。
从仓库根目录运行构建任务，保留 `app/` 和 `sharedButtons/` 共享源码及资源。

```powershell
.\gradlew.bat :desktopApp:desktopTest
.\gradlew.bat :desktopApp:createDistributable
```

构建前准备 JDK 17；MiSans 字体和测试音频等本地资源要求见
[开发文档](../docs/development.md)。VLC 运行库由工具脚本按固定版本和 SHA-256 准备。

便携版输出位于 `build/compose/binaries/main/app/SyncTone/`。
运行数据保存在 EXE 旁的 `data/`，临时文件和日志位于 `portable/`。
制作发布包时需要排除开发机的数据和日志。
