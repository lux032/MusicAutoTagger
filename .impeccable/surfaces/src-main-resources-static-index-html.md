---
version: 1
slug: "src-main-resources-static-index-html"
primary_target: "src/main/resources/static/index.html"
related_targets: ["src/main/resources/static/review.html","src/main/resources/static/recovery.html","src/main/resources/static/records.html"]
---

Scope: Web 面板全部页面（登录、概览、待确认、重新识别/回收站、处理记录、设置）。Mode: Operate。
Status: 用户选定 V1 精修版（mockups/v1-refined.html），已落地到 src/main/resources/static。

## Direction contract
THESIS: 面板首先回答「服务还好吗、有什么等我处理」；拒绝旧版「一堆等大卡片 + 渐变监控卡 + 顶栏按钮组」的 demo 式排布。
OWN-WORLD: 冷灰底 #f2f4f8、白色卡片 12px 圆角细边框、单一蓝色 #1d6ef0 只用于主操作与选中态；状态色（绿/琥珀/红）只表达状态；系统字体栈，日志与路径用等宽。
STORY: 访客看一眼概览知道监控是否在跑、处理了多少；右侧「等你处理」直接把待确认/缺曲/重新识别的积压带到对应页面；所有页面共用一条左侧导航，设置是独立页面而不是弹层。
FIRST VIEWPORT: 左侧 228px 白色导航（品牌、概览、需要处理组带待确认计数、资料组、底部连接状态与账号）；主区页头「概览」+ 更新时间 + 刷新；下方一条健康条（监控状态与暂停/重启 | 已处理 | 封面缓存 | 文件夹缓存）；再下为左宽右窄两栏：最近整理的专辑封面带 + 运行日志（级别筛选），右侧等你处理 + 系统信息。
FORM: 用户锁定的 V1 精修版（非抽签方向，seed 8be29d28 的 pick 外用户指定）。签名交互：概览「等你处理」直达对应积压；待确认页最佳候选高亮 + 时长相似度条，唯一主按钮。
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
