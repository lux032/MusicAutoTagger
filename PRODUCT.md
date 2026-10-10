# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

（推断，待确认）自托管音乐收藏者：在 NAS / 家用服务器上用 Docker 部署，配合 qBittorrent / Transmission 下载。程序大部分时间无人值守，用户偶尔打开 Web 面板：确认服务在跑、处理「待确认」队列、对失败/部分识别的文件做重新识别或从回收站恢复、查询处理记录、调整配置。

## Product Purpose

Music Auto Tagger 监听下载目录，用音频指纹（Chromaprint/AcoustID）与时长序列指纹识别音乐，按 MusicBrainz 补全元数据、封面（Cover Art Archive）与同步歌词（LrcLib），并按「艺术家/专辑/曲名」归档。成功 = 音乐库自动拥有正确的标签、封面和歌词，人只需处理真正拿不准的少数情况。

## Positioning

（推断）宁可停下来问人，也不猜一个专辑直接归档：专辑无法确定时整个文件夹进入待确认队列，原文件保持原样；归档以整张专辑原子提交，失败回滚，隔离副本进回收站可还原。多套证据（本地时长相似度、联网候选、LLM 封闭式判定）并列呈现，但只有人确认后才落盘。

## Operating Context

- Web 面板页面：仪表盘（监控状态、统计、最近整理的专辑、运行日志）、待确认（专辑待确认 / 锁定专辑缺曲；候选、证据、处置操作）、重新识别（部分识别 / 识别失败 / 回收站 + 最近任务）、处理记录（搜索、筛选、批量重新识别/删除、导出 CSV）、设置（基础 / 识别 / 数据库 / 代理 / 高级 + 维护工具）、登录与首次创建管理员、修改密码。
- 后端 Java + Spring 托管静态页面；前端为原生 HTML/CSS/JS，无构建步骤。
- 中英双语，通过 `data-i18n` key 与 `messages_*.properties` 管理。

## Capabilities and Constraints

- 用户确认：除 Logo 外，现有视觉与交互均可整体替换（现状「太 demo」）。
- 保留全部功能、文案语义与 i18n key；不得编造产品不具备的能力。
- 多套分数含义不同（本地证据可用度、时长相似度、联网覆盖率、LLM 置信度），界面不得把它们横向比较。
- 安全约束：密钥掩码、CSRF、FFmpeg 路径只读。
- 未确认：是否必须支持手机访问、是否需要暗色模式。

## Brand Commitments

- 名称：Music Auto Tagger / 音乐自动标签器。
- Logo：`docs/images/MusicAutoTagger_icon.png`、`src/main/resources/static/logo.png`，必须保留。

## Evidence on Hand

- 现有界面截图：`docs/images/webCN.png`、`docs/images/webEN.png`。
- 无真实用户数据；静态稿中的专辑、曲目、日志均为示例数据。

## Product Principles

1. 不确定就停下来问人，确认动作要显式、可核对、可回退。
2. 证据并列而不混算：每种分数说清楚自己的含义。
3. 无人值守优先：面板首先回答「它还好吗、有什么等我处理」。
4. 破坏性操作说清楚后果（删除/移动/不可逆）。
