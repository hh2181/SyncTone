# SyncTone

本地音乐播放器，同一仓库包含 Android 与 Windows 应用。

| 平台 | 当前版本 | 实现 |
| --- | --- | --- |
| Android | 0.4.0 / versionCode 5 | Jetpack Compose、Media3 |
| Windows x64 | 0.4.11 | Compose Multiplatform/JVM、libVLC |

Android 应用 ID 为 `app.synctone.music`。内部 Kotlin/Java namespace
暂时保持 `dev.salt.replica`，避免改变组件引用和两端共用源码的路径。

## 工程结构

- `app/`：Android 播放器、歌词、扫描、统计和局域网同步。
- `desktopApp/`：Windows 播放器、测试与打包工具。
- `sharedButtons/`：两端共用的按钮组件。
- `gradle/`：Gradle Wrapper 和依赖版本目录。

Windows 还直接编译 `app/` 内部分平台无关的歌词、统计、同步源码，并读取
其中的图标资源。保留整个仓库后再构建。

## 构建

需要 JDK 17；Android 构建还需要 Android SDK 37，minSdk 26 / targetSdk 36。
首次构建需要下载 Gradle 和 Maven 依赖。通过 Android Studio 配置 SDK，或在
本地 `local.properties` 中配置 `sdk.dir`。该文件不提交到 Git。

```powershell
# JAVA_HOME 应指向本机安装的 JDK 17。
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
.\gradlew.bat :desktopApp:desktopTest
.\gradlew.bat :desktopApp:createDistributable
```

Windows 原生运行库由 `desktopApp/tools/prepare-vlc.ps1` 按固定版本和校验值
准备。构建产物、运行库、曲库、配对数据和日志不提交到源码仓库。

## 本地资源

`app/src/main/assets/reference.mp3` 是随源码提供的合成测试音频，包含生成的
封面和测试字幕，不包含外部歌曲录音或歌词。生成方式和使用说明见
[测试资源说明](app/src/main/assets/README.md)。

Windows 在应用中使用 MiSans 字体。请从官方获取字体，将未经修改的
`MiSansVF.ttf` 放入 `desktopApp/src/desktopMain/resources/fonts/` 后构建。
未提供本地字体文件时，开发构建会使用系统 sans-serif 字体，仍可运行。
字体文件不提交到源码仓库，也不作为独立下载附件提供。
字体版权和使用条件见 [第三方说明](THIRD_PARTY_NOTICES.md)。

## 同步

两端打开“连接”页后发现设备，首次使用需核对并确认配对码。同步使用协议 v2、
证书固定的 TLS 和 SHA-256 文件内容比对。目前曲库比对及文件同步范围为 MP3/FLAC。
“共同 / 本机缺少 / 对方缺少”反映最近一次完成的比对结果。

首次连接受局域网路由、防火墙和网络隔离影响；当前已知的手机主动连接部分
Windows 网络环境超时问题仍需排查。不要将单向连接成功视为全部方向已验证。

本仓库目前仅提供源码，尚未发布安装包，也未选定项目整体开源许可证。
