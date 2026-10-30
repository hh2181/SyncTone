# 十段 EQ 双端后端研究

研究日期：2026-10-30。基于当前 Android Media3 1.8.0、Windows libVLC 3.0.24。

## 结论与确定范围

现有项目可以实现十段 EQ、0.1 dB 细调、多个命名预设和响应曲线。
Android 使用应用内软件 DSP，不以系统均衡器作为主方案。
根据用户在交互样例中指出的相邻频段起伏，方案已修订：优先采用经过联合校准的十段串联 IIR 模型；Windows 原生 VLC 十段 EQ 降为兼容备选，不能用它承诺复现新模型的响应。
0.7.6 已落地：Windows 保留 VLC 解码／播放控制，经 S16N 回调接入共同 DSP，再由 JavaSound 按 VLC 时钟输出，处理格式为 48 kHz／16 bit／立体声。
Android 已把共同 DSP 接入 Media3 AudioProcessor，按实际处理采样率生成系数。
十段中心频率固定，不增加自由频率、Q 值或可增删频段的参数化 EQ。

这份文档保留后端研究与模型复核记录。当前版本已完成双端音频和 UI 接入，使用说明及验证结果见 [均衡器说明](equalizer.md) 和 [0.7.6 发行说明](releases/0.7.6.md)。

## 当前项目的接入位置

| 项目 | Android | Windows |
| --- | --- | --- |
| 播放核心 | Media3 ExoPlayer 1.8.0 | libVLC 3.0.24，经 JNA 调用 |
| 核心入口 | `app/.../playback/PlaybackService.kt` | `desktopApp/.../playback/VlcAudioEngine.kt` |
| 控制层 | `PlayerStore` → `MediaController` → `MediaSessionService` | `DesktopPlayerStore` → 单独的 `synctone-audio` 工作线程 → `AudioEngine` |
| 现有音效入口 | 目前打开媒体音量面板 | 目前按钮禁用 |
| 现有 EQ | 没有创建 Equalizer 或插入 AudioProcessor | JNA 接口尚未声明 equalizer 系列函数 |
| 存储基础 | 应用私有目录、JSON／偏好存储 | 便携数据目录、已有临时文件加原子替换模式 |

Android EQ 必须由播放服务持有，不能依赖 Compose 页面是否存在；Windows EQ 的原生调用和释放继续在音频工作线程执行。

## Android：应用内 DSP

音频路径：

```text
本地文件 → 解码为 PCM → 应用十段 EQ／前级增益 → AudioSink／AudioTrack → 输出设备
```

在创建 ExoPlayer 时，通过定制音频 sink／renderers factory 安装自己的 `AudioProcessor`。
用 IIR 滤波器处理 PCM，每个声道独立保存滤波状态，左右声道默认采用相同的十段设置。
滤波器系数根据实际采样率生成，参数变更以不可变快照交给音频线程。
音频回调内避免磁盘读写、Compose 状态访问和逐样本对象分配。

这条路径允许应用自行确定频段、调节范围和参数精度，不依赖手机系统提供多少个 EQ 频段。
系统 `android.media.audiofx.Equalizer` 可作为将来的兼容选项，但本次主方案不使用它。
该系统接口需要查询实际频段与范围，并绑定具体 audio session；不能假定所有设备都提供相同的十段。
参考 [Android Equalizer](https://developer.android.com/reference/android/media/audiofx/Equalizer)。

当前固定版本 Media3 1.8.0 有两个接入边界：自定义处理链只用于 PCM，不用于 offload／passthrough；其默认浮点输出路径也不能直接当作同一条自定义处理链使用。
实现时须明确 EQ 开启期间的输出策略，避免界面显示已开启、音频实际绕过 EQ。
首版可从当前整数 PCM 路径接入，在内部使用浮点计算；高解析浮点直通另行验证。
依据 [Media3 1.8.0 DefaultAudioSink 源码](https://github.com/androidx/media/blob/1.8.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java)。

EQ 开关应在已安装的处理器中切换处理／旁路，避免每次操作重建播放器。
采样率、声道数改变和 seek／flush 时需要正确更新或清理滤波状态。
UI 通过 MediaSession 自定义命令提交参数，并接收服务确认后的实际状态，防止后台播放与页面显示不同步。
参考 [MediaSession 自定义命令](https://developer.android.com/media/media3/session/control-playback)。

## Windows：实际接入与原生 libVLC EQ 备选研究

本版已选择 PCM 处理／输出适配层：`VlcAudioEngine` 保留解码、媒体时钟和控制，`VlcPcmOutput` 接收 S16N PCM，经共用 `EqualizerDsp` 后输出到 JavaSound SourceDataLine。
VLC 3.0.24 的 amem 输出仅接受 S16N，所以明确协商 48 kHz、双声道 PCM 16 bit；不把它称为原位修改 VLC 原生 EQ，也不承诺独占或高位深直通。
暂停、seek／flush、自然排空、音量及释放均由适配层接管，实际播放及私有打包 JVM 已完成验证。
以下保留原生 EQ 接口与滤波结构的历史研究结果。本版没有使用原生 EQ 对象，也没有向用户提供切回该备选的入口。

音频路径：

```text
本地文件 → VLC 解码／重采样 → S16N PCM 回调 → 共用 EQ DSP → JavaSound 默认输出设备
```

原生运行库包含 `plugins/audio_filter/libequalizer_plugin.dll`，曾考虑通过原生创建／释放 EQ 对象、设置增益及前级的接口快速接入。
最终为统一模型另行扩展了 `AudioEngine` 的 EQ 参数和实际状态接口，未采用这条原生滤波路径。
`libvlc_media_player_set_equalizer` 能应用到当前及后续媒体；传空指针关闭。
调用后原生播放器不保留该 EQ 对象引用，可以在返回后释放参数对象。
参考 [libVLC 3 音频控制 API](https://videolan.videolan.me/vlc-3.0/group__libvlc__audio.html)。

本轮对项目实际 DLL 进行了内存中的轻量探测，结果如下：

| 项目 | 探测结果 |
| --- | --- |
| 版本 | 3.0.24 Vetinari |
| 频段数 | 10 |
| 中心频率 Hz | 31.25、62.5、125、250、500、1000、2000、4000、8000、16000 |
| 原生内置预设 | 18 个 |
| 新 EQ 对象的原生前级值 | 0.0 |
| 频段写入／读取 | 3.1 dB → 3.1 dB，返回成功 |
| 前级写入／读取 | -4.2 dB → -4.2 dB |
| 越界设置 | 21 dB 被限制为 20 dB |

这里只验证了参数接口，不代表已经完成音频频响、输出失真或听感验证。
探测未创建媒体播放器或播放音频；对象已释放，脚本通过标准输入执行，没有保存临时脚本或音频。

### 需要处理的音量基线

固定版本的 VLC equalizer 并非简单的十个串联峰值滤波器。
从其单遍滤波计算可推导：所有频段和原生前级均为零时，滤波器自身存在 0.25 的输入比例，约为 -12.04 dB。
所以不能直接把原生参数的“全零”作为应用的等响平直预设。

适配层须区分“用户看到的前级”和“VLC 原生前级”，以旁路为基准校验归一化；
自动留余量也应在归一化之后计算，并遵守原生前级的范围。
这属于源码推导，正式实现前需要用 PCM 输入输出测量确认完整链路，不能直接凭界面数值加补偿。
依据 [VLC 3.0.24 equalizer 实现](https://github.com/videolan/vlc/blob/3.0.24/modules/audio_filter/equalizer.c)
和 [播放器设置入口](https://github.com/videolan/vlc/blob/3.0.24/lib/media_player.c)。

## 双端公共参数与精度

- 两端采用上表相同的十个中心频率，频率位置只读。
- 建议首版用户增益范围为 ±12 dB、步进 0.1 dB；底层能力仍需独立描述，不能直接把原生极值全部变成默认操作范围。
- 内部可用整数 `gainTenthDb` 保存设置，应用到 DSP／VLC 时转换，避免连续加减造成小数漂移。
- 拖动只更新内存参数；合并密集更新，并对音频处理结果做短时平滑过渡，减少参数突变产生的杂音。
- 提升多个频段时可能叠加超出输出余量，需根据整体响应提供前级衰减；不能只取某一个滑块的最大值。
- 对采样率对应的奈奎斯特频率以外的频段要有明确处理，不能生成不稳定滤波器。

Android 软件滤波与 VLC 内置滤波并不天然具有逐样本相同的响应。
新版应尽量在两端实现同一种联合校准模型，并按实际处理采样率生成系数；若使用 VLC 兼容后端，则须明确模型差异。
0.1 dB 表示用户目标参数的调节步进，不代表实际频响与目标曲线之间的误差已保证小于 0.1 dB。

## 为下一步曲线 UI 提供什么

优先提供 EQ 的理论频率响应：横轴采用对数频率，纵轴为 dB，数据由实际滤波模型及采样率计算。
Android 可从自身系数计算；Windows 应根据实际选用后端的系数、结构和前级计算。VLC 兼容后端还需处理其归一化与单／双遍模式。
不能用任意平滑连线替代响应后，再把它称为真实频响。
IIR／biquad 的标准传递函数可参考 [W3C Audio EQ Cookbook](https://www.w3.org/TR/audio-eq-cookbook/)。

响应曲线与实时音乐频谱是两种数据：前者描述 EQ 如何改变声音，后者分析正在播放的 PCM。
本次只展示 EQ 响应曲线，不为它引入录音权限或实时频谱分析。
Windows PCM 回调替代了 VLC 原有输出，因此本版同时实现了格式、时钟调度、缓冲、暂停／seek、音量与释放，不能把该回调当作旁挂的观察接口。
参考 [libVLC 音频回调说明](https://videolan.videolan.me/vlc-3.0/group__libvlc__media__player.html)。

建议公共后端接口分为：

```text
EqCapabilities  固定频率、可用增益范围、步进、曲线模型能力
EqState         开关、当前参数、实际应用状态、前级与余量
EqPreset        ID、名称、版本、十段参数、前级、创建／更新时间
EqEngine        应用参数、旁路、读取能力及实际响应数据
EqPresetStore   保存、另存、重命名、删除、恢复选中项
```

UI 以实际应用状态绘制曲线，调节草稿与已保存预设分开；提供旁路对比和平直重置所需状态。
生产页面需使用上述数据；会话内已有双端交互样例供设计评审。

### UI 样例的曲线范围与核验

旧样例使用固定 48 kHz、单遍、去掉平直基线衰减后的 VLC 并联带通模型。
之前通过单位脉冲／离散傅里叶计算确认了该模型的数学一致性，但这不能证明它符合用户对连续调音的预期。
用户反馈的参数为 `[-0.8, 0, 0, -6.8, -6.5, -2.6, 0, 0, 0, 0] dB`：旧模型在 250 Hz 约为 −7.388 dB、500 Hz 约为 −9.209 dB，区间内还出现回升。
这表明必须评估滤波模型的组合行为，不能只验证公式，也不能只改变显示曲线。

会话样例改为独立实现的联合校准串联模型，样例固定为 48 kHz；随后已将同一算法移植为共享 Kotlin，并接入双端播放器。应用页面读取实际处理采样率，包含自动前级衰减。
十个二阶峰值／衰减滤波器共同拟合十个中心频率和九个对数中点，内部滤波器增益与用户目标增益分开。
采用 0.3 邻频增益比例、17 dB 初始原型和三轮交互矩阵最小二乘求解；带宽根据当前采样率和低侧相邻频率求取。
思路参考 [Accurate Cascade Graphic Equalizer](https://acris.aalto.fi/ws/portalfiles/portal/27113913/ELEC_valimaki_et_al_Accurate_cascade_graphic.pdf)；本样例并未复现论文的全部实验条件，也不直接引用其误差上限作为自身保证。
其他并联结构也可通过联合优化改善，问题不是“所有并联 EQ 都不好”；可参考 [High-Precision Parallel Graphic Equalizer](https://research.spa.aalto.fi/publications/papers/ieee-taslp-pge/)。

同一组参数在新模型的 250 Hz 约为 −6.830 dB、500 Hz 约为 −6.525 dB。
250–500 Hz 形成较连续的衰减区域，但仍有小幅残余起伏，UI 按实际模型系数如实绘制。
使用平直、相邻等量提升／衰减、用户参数、全 +12 dB、极端交错六个案例核对，19 个设计点的最大目标误差约为 0.770 dB，滤波器均满足二阶稳定条件；这不是全部参数组合的误差保证。
再次对用户参数用 65,536 点单位脉冲和 12 个频率（包括区间内频率）核对，时域 IIR 与曲线公式的最大差异约 9.5 × 10⁻¹² dB。
绘图保留 1,035 个频率采样并包含全部中心频率，未用样条替代滤波器响应。
上述数学验证仅使用内存数据，没有生成测试音频文件；它不等价于听感验证。之后的 0.7.6 专项测试还对实际 PCM 输出与曲线进行了核对，并完成原生解码／便携包和 Android 真机验证。

正式接入后，响应数据必须带上实际生效的滤波模型、处理采样率、单／双遍模式、前级及归一化状态；
Android 直接使用音频处理器的实际系数，Windows 对照已确认的原生配置计算，并用少量输入／输出信号核验。
如果无法确认某个原生配置，则继续显示“理论响应”，不能把固定采样率的样例曲线直接标为实测或实际响应。

## 多预设保存

建议使用独立的、带版本号的 `equalizer-presets.json`：Android 放应用私有目录，Windows 放现有便携数据目录。
采用临时文件加原子替换，并区分当前草稿、选中预设和用户主动保存的多个结果。
保存参数而非处理后的音乐，不复制原曲；文件规模预计为 KB 级。

每个预设至少包含 UUID、名称、频段配置版本、滤波模型 ID／版本、十个整数目标增益、用户前级、自动余量选项和时间戳。
原生补偿值与句柄不写入公共预设；恢复后由各平台适配器计算和应用。
自定义预设不受 VLC 的 18 个内置预设数量限制。

## 已完成的接入与最小验证

1. 共用十段目标参数、模型及预设存储，保存模型 ID 和版本。
2. Android Media3 PCM 处理器、Windows VLC PCM／JavaSound 适配层。
3. 双端 6 项专项测试：模型响应、实际 PCM、独立声道、旁路／静音、稳定性与余量、预设与坏文件保留。
4. Windows 实际 MP3／FLAC、暂停、seek、音量、结束以及私有 JVM 包的实际 EQ／旁路验证。
5. 双端 Compose 页面、深色／窄屏检查，以及 Android 真机的应用状态、0.1 dB、保存／重启与清理检查。

没有为此运行完整历史测试集；测试范围聚焦 EQ、受影响的播放链路和发布运行时。测试媒体、截图与设备上的测试方案已清理，发布包不包含测试目录或用户数据。

以上为 0.7.6 接入阶段的验证记录；0.7.7 发布时另完成了双端完整测试及便携包复核，见 [0.7.7 发行说明](releases/0.7.7.md)。
