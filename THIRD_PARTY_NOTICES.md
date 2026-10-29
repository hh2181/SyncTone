# Third-party notices

## MiSans

本项目 Windows 应用使用 MiSans 字体。字体文件版权归其权利人所有，
适用 MiSans 字体知识产权许可协议，不受未来项目整体源码许可证的重新授权。

字体元数据中的声明：

Copyright (c) 2020-2025 Beijing Xiaomi Mobile Software Co.,Ltd. All Rights Reserved.

字体仅在本地构建时集成到应用，源码仓库不提供字体原文件或独立字体下载。
请从官方获取字体，并遵守相应协议：

https://hyperos.mi.com/font/en

## LDDC 与歌词来源参考

联网歌词功能的设计与协议研究参考 LDDC（沉默の金，GPL-3.0-only）：
https://github.com/chenmozhijin/LDDC 。应用未打包 LDDC 的 Python 源码或运行时。
歌词由酷狗、LRCLIB 提供，版权归相应权利人；接口及存储说明见 `docs/online-lyrics.md`。

## Runtime libraries

本项目使用 Compose、Kotlin、AndroidX/Media3、libVLC、JNA、SQLite、OkHttp、
NanoHTTPD 等第三方组件，各组件保留自己的版权和适用许可证。
依赖版本见 Gradle 配置；libVLC 准备脚本保留下载包附带的许可和作者文件。
在制作正式发布包前，需要补齐随包分发组件的许可证清单。

Windows 便携版保留 VLC 运行库附带的 `COPYING.txt`、`AUTHORS.txt`、`THANKS.txt`
以及 Java 运行库的 `legal/` 目录。第三方 JAR 中的许可证与声明随对应 JAR 保留。
VLC 的上游发布与源码入口：https://www.videolan.org/vlc/download-sources.html 。

本文件不宣称项目中的全部代码和素材均为原创，也不替代各组件的授权条款。
