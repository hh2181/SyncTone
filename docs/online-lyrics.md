# 联网歌词

Android 与 Windows 的统一入口为：侧边栏 → 歌词更新。
播放器的歌词设置中也保留了前往此页面的快捷入口。

## 使用

1. 点击“已选 N 首”筛选本地曲库，勾选批量处理的歌曲，或点击一首歌旁的“搜索”进入单曲匹配。
2. 在“搜索与预览”中编辑关键词，选择全部或指定来源，查看多个候选的歌手、时长、来源和相似度。
3. 点击结果预览实际歌词与逐字／逐行类型。宽窗口并排展示结果与预览；移动端和较矮窗口使用底部预览面板。
4. 点击“应用此歌词”，保存到本次搜索绑定的歌曲。原有播放器继续负责同步高亮、滚动及翻译开关。
5. 如需恢复，在播放器的歌词设置中点击“恢复原歌词”，回到原有内嵌／本地歌词。

三个分页使用 HorizontalPager，点击切换或横向滑动时，新旧卡片共同参与 420 ms 非线性平移，保持布局连续。
歌词动画预览只演示歌词时间，不播放候选版本的音频。

## 匹配设置与批量更新

- 可勾选原文、罗马音、翻译，并用上下按钮调整显示和保存顺序，至少保留一种内容。
- 匹配相似度可设为 60%–100%，按规范化歌名、歌手的编辑距离及歌曲时长计算。
- 可要求必须逐字、必须有翻译或罗马音；逐字要求根据实际下载的时间戳检查，不用逐行歌词伪造逐字时间。
- 时长允许误差可设为 ±0.5–5 秒；自动／批量匹配缺少歌手或时长的信息时留待手动选择。
- 来源可启用／关闭并调整优先级，当前实际接通酷狗和 LRCLIB。
- 可选择保留已有歌词，或替换满足要求的版本。手动选择允许替换，不受自动匹配阈值与逐字条件限制。

批量更新只处理勾选的歌曲，启动时固定整份规则，串行执行，不在执行过程中偷偷换规则。
每首歌显示“已更新 / 已保留 / 待确认 / 失败”；待确认及失败项可回到单曲搜索自由选择。
暂停会取消正在进行的网络请求，继续时从未完成项接着处理；已完成项不会重做。
暂停后可结束本次任务。运行进度只在本次应用会话内保留，退出后需重新发起，已保存歌词保持不变。

“缺少歌词时自动匹配”默认关闭，开启后在本机持久保存。仅处理当前缺少歌词的歌曲；
自动匹配遵循上述来源、相似度、时长和歌词内容要求；默认相似度至少 90%、必须逐字、时长差不超过 2.5 秒。
每个启用来源至多尝试两个满足元数据条件的候选，不覆盖已有歌词，也不反复轮询失败结果。
更换搜索目标或发起新搜索会取消旧的手动请求；切换播放歌曲不会误改当前手动搜索的目标。

## 来源与存储

- 酷狗：HTTPS 搜索及 KRC 下载，保留逐字起止时间；若源返回逐行内容，按真实类型显示。
- LRCLIB：HTTPS 搜索获取同步歌词，复用搜索响应内的 LRC，减少重复请求；过滤无时间戳的纯文本结果。
- 原文、翻译与罗马音沿用 `Lyrics / LyricCell / LyricSubLine` 模型，并保存 `LyricContent` 类型与顺序；旧歌词仍可读取。
- Android：保存到应用私有 `files/lyrics/<歌曲ID>.lrc`，采用增强 LRC 保留逐字边界。
- Windows：保存到便携版 `data/library.db` 的歌词覆盖字段，沿用原有有界歌词内存缓存。
- 不修改音频标签，不下载音频，不缓存搜索历史或下载响应到磁盘。
- 搜索向所选服务发送关键词；自动匹配发送当前歌名、歌手。时长仅用于本地排序和匹配。
- 所有请求有超时和 2 MiB 响应上限，KRC 解压也受 2 MiB 上限限制；暂停批量任务会取消当前请求。
- 离开“歌词更新”页面保留任务状态；应用退出或重建播放器会取消未完成的网络工作。

LRCLIB 和酷狗的可用性、歌曲覆盖与版权范围由来源决定。单一来源故障不会影响另一个来源的搜索。
尚未接入 QQ QRC、网易 YRC 或 LDDC 的 Python 服务；演示中的这些规划来源没有作为可用接口展示。
本功能批量下载和更新本机歌词，尚未实现两台设备之间传输歌词覆盖数据。

## 实现参考

参考 [LDDC](https://github.com/chenmozhijin/LDDC) 的多源搜索、匹配、预览与本地持久保存思路，
以及其 [酷狗适配](https://github.com/chenmozhijin/LDDC/blob/main/LDDC/core/api/lyrics/kg.py)
和 [LRCLIB 适配](https://github.com/chenmozhijin/LDDC/blob/main/LDDC/core/api/lyrics/lrclib.py) 对协议的说明。
LDDC 使用 GPL-3.0-only；本功能使用独立的 Kotlin 实现，没有打包 LDDC 的源码、Qt 或 Python 运行时。
LRCLIB 接口说明见 [LRCLIB API 文档](https://lrclib.net/docs)。

## 定向验证

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'dev.salt.replica.lyrics.online.*' --tests 'dev.salt.replica.lyrics.LyricsTest'
.\gradlew.bat :desktopApp:desktopTest --tests 'dev.salt.replica.lyrics.online.*' --tests 'dev.salt.desktop.LyricsUpdateStorageTest'
# 下面两项按需运行；前者访问外部来源，后者使用合成歌词检查界面和滑动中间帧。
.\gradlew.bat :desktopApp:onlineLyricsSmoke
.\gradlew.bat :desktopApp:onlineLyricsVisualSmoke
```

测试生成的临时目录均在 finally 中删除；真实联网检查仅输出候选及歌词行数，不保存服务返回的歌词。
视觉检查截图输出在 `desktopApp/build/reports/online-lyrics-visual/`，查看完成后可删除该目录。
