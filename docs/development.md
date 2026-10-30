# 开发与构建

## 工程结构

- `app/`：Android 播放器、歌词、扫描、统计和局域网同步。
- `desktopApp/`：Windows 播放器、测试与打包工具。
- `sharedButtons/`：两端共用的按钮组件。
- `gradle/`：Gradle Wrapper 和依赖版本目录。

Android 使用 Jetpack Compose 和 Media3；Windows 使用 Compose Multiplatform/JVM 和 libVLC。
Windows 还直接编译 `app/` 内部分平台无关的歌词、统计、同步源码，并读取其中的图标资源。
请保留整个仓库后再构建。

`app/.../lyrics/online/` 是两端共用的 Kotlin/JVM 联网歌词模块，包含受限 HTTP 请求、
歌词源适配、KRC 解码、增强 LRC 导出、匹配控制器和 Compose 搜索界面。
平台层负责保存到 Android 私有目录或 Windows 曲库数据库，下载内容继续使用原来的歌词渲染器。
“歌词更新”的三个分页使用共用 HorizontalPager：420 ms 的非线性平移保持新旧卡片同时参与布局。
批量任务串行执行，启动时固定规则，按歌曲 ID 保存，支持取消请求后的暂停续传；任务不保存到磁盘。
原文、罗马音、翻译由 `LyricContent` 标注，保存时保留内容顺序；旧曲库和旧 LRC 仍可读取。
Android 的歌词更新分页由 `PlayerApp` 持有状态，通过 `LyricsDrawerConnection` 接收分页最左端剩余的右滑距离。
`LyricsUpdateScreen.pagerModifier` 用于接入该手势，桌面端保留默认分页行为。

封面下方的简要歌词使用 `lyrics/CompactLyricsLayout.kt` 按逻辑宽度选择字号，正文 16–20 sp、次要内容至少 14 sp。
布局通过 Compose 的 sp→dp 转换预留行高，并为播放控件保留空间；短屏缩小封面，避免通过压小歌词来容纳内容。
大标题须显式设置匹配字号的行高，例如详情的 27 sp 字号配 34 sp 行高，避免继承正文行高导致换行重叠。

移动端同类歌曲列表共用 `ui/MusicListComponents.kt`，以统计页的封面、字体和留白为基准。
首页、曲库、详情和队列通过 `SongRow` 提供播放与更多操作，统计页提供播放次数和累计时长；
使用 `MusicListCard / MusicListRow / MusicListMetric / MusicListDivider` 保持容器、排版和分隔线一致。
歌曲点选以歌曲 ID 解析 Media3 的实际队列位置，避免直接把曲库、筛选结果或推荐列表的索引用于播放器。
播放服务可以比界面存活更久，重新连接时须主动读取实际队列及当前播放状态，不能只等待新的播放事件。

Android 卡片容器过渡的时长、曲线、边界和圆角规则共用 `ui/CardTransitionMotion.kt`。
展开后的容器统一保留 28 dp 圆角，动画结束不转为直角；收回时连续恢复源卡片圆角。
共享封面目标使用详情页内的布局坐标，在运动中固定；不要从正在缩放或平移的全局坐标反推目标位置。
源卡片避免叠加按压缩放，封面渐变在收回终点须为透明，静态封面与运动封面应在相同边界交接。

## 均衡器

双端共用 `equalizer/EqualizerModel.kt`、`EqualizerDsp.kt`、`EqualizerRepository.kt` 和 Compose 页面。
Android 的 `EqualizerAudioProcessor` 由 `PlaybackService` 持有，禁用浮点旁路输出；Windows 在 `VlcPcmOutput` 中接收 VLC 的 S16N PCM，按 VLC 时钟调度 JavaSound 输出。
曲线与音频使用同一个系数设计函数，内部目标增益与校准后的滤波器增益分开，不能用 UI 样条代替实际频响。
采样率、声道数和播放中参数更新需在正确的线程处理；seek／flush 清理滤波状态，关闭服务时释放订阅。
设置保存在独立的 `equalizer-presets.json`，写入合并后原子替换，不保存处理后的音频。

针对性检查：`EqualizerTest`（两端）、`playbackSmoke`、`equalizerVisualSmoke`。
打包后可运行 `desktopApp/tools/verify-equalizer-package.ps1 -OutputDirectory <新的隔离目录>` 验证私有 JVM 的实际音频处理路径；完成后删除该隔离目录。
用户说明与格式限制见 [均衡器说明](equalizer.md)。

## 构建

需要 JDK 17；Android 构建还需要 Android SDK 37，minSdk 26 / targetSdk 36。
首次构建需要下载 Gradle 和 Maven 依赖。通过 Android Studio 配置 SDK，或在本地
`local.properties` 中配置 `sdk.dir`。该文件不提交到 Git。

在仓库根目录运行：

```powershell
# JAVA_HOME 应指向本机安装的 JDK 17。
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
.\gradlew.bat :desktopApp:desktopTest
.\gradlew.bat :desktopApp:createDistributable
```

Windows 原生运行库由 `desktopApp/tools/prepare-vlc.ps1` 按固定版本和校验值准备。
构建产物、运行库、曲库、配对数据和日志不提交到源码仓库。
Windows 打包目录和数据存储说明见 [Windows 工程说明](../desktopApp/README.md)。

## 本地资源

`app/src/main/assets/reference.mp3` 是随源码提供的合成测试音频，包含生成的封面和测试字幕，
不包含外部歌曲录音或歌词。生成方式和使用说明见[测试资源说明](../app/src/main/assets/README.md)。

Windows 在应用中使用 MiSans 字体。请从官方获取字体，将未经修改的 `MiSansVF.ttf` 放入
`desktopApp/src/desktopMain/resources/fonts/` 后构建。未提供本地字体文件时，使用系统
sans-serif 字体，仍可运行。字体文件不提交到源码仓库，也不作为独立下载附件提供。
字体版权和使用条件见[第三方说明](../THIRD_PARTY_NOTICES.md)。

两端应用图标使用 `assets/branding/app-icon.png` 中的项目图稿；使用 Pillow 运行
`python tools/generate-app-icons.py` 可重新生成所需尺寸和格式。

## 局域网同步

两端打开“连接”页后发现设备，首次使用需核对并确认配对码。同步使用协议 v2、
证书固定的 TLS 和 SHA-256 文件内容比对。目前曲库比对及文件同步范围为 MP3/FLAC。
“共同 / 本机缺少 / 对方缺少”反映最近一次完成的比对结果。

首次连接受局域网路由、防火墙和网络隔离影响；当前已知的手机主动连接部分 Windows
网络环境超时问题仍需排查。详见[发行说明](releases/0.5.0.md)。

## 许可

项目尚未选定整体开源许可证；第三方组件仍适用各自的许可证。
