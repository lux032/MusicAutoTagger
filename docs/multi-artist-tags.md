# 多艺术家标签修复

## 改动

- 从 MusicBrainz `artist-credit` 保留逐人的姓名列表，不再仅保留拼接后的展示文字。
- 曲目写 `ARTIST` 展示文字及 `ARTISTS` 结构化姓名；按容器使用 jaudiotagger `setField` / `addField` 写多值。
- 专辑写 `ALBUM_ARTISTS` 时尊重现有专辑艺人策略；配置成 Various Artists 时不擅自还原为多人专辑署名。
- 元数据审核快照与复制保留列表；重新指定展示艺人时清除旧列表，避免错误沿用上一首歌的结构化署名。
- 原有姓名列表可从文件读回；重写艺人时移除过期 `ARTISTS`。
- 原有 MusicBrainz Artist ID 标签继续保留。

## 现有文件

此修复不自动重写已经完成刮削的文件。032 Music Server 已配套支持旧 MusicAutoTagger 的“逗号展示名 + 分号 MBID”格式，可在有效 MBID 与人数严格对应时通过完整扫描恢复多人关系。

名字本身含逗号、数量不匹配等歧义不会自动拆分，可重新刮削写入明确的 `ARTISTS`，或在服务端管理页单曲修正。

## 验证

- 覆盖 FLAC、Vorbis、ID3v2.3、ID3v2.4、MP4 的标签写入测试。
- 合成 ID3 文件由 jaudiotagger 3.0.1 写出，再由服务端读取，已验证中日文与逐值 UTF-16 BOM 兼容。
- Maven 全量测试 280 项通过；后续新增测试以最新报告为准。

没有修改实际音乐文件。用户已授权提交和推送，main 推送会触发 Docker 镜像构建，结果以 GitHub Actions 为准。
