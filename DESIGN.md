---
name: Music Auto Tagger
description: 自托管音乐整理工具的 Web 面板：无人值守时安静，有事要人处理时一眼可见。
colors:
  primary: "#1d6ef0"
  primary-dark: "#1550c4"
  primary-soft: "#e9f0fe"
  primary-line: "#c9dafc"
  cool-ground: "#f2f4f8"
  soft-ground: "#f7f8fb"
  surface: "#ffffff"
  hairline: "#e3e7ee"
  hairline-strong: "#d3d9e3"
  ink: "#121a26"
  ink-soft: "#4a576b"
  ink-mute: "#5f6b80"
  success: "#12703a"
  success-soft: "#e7f6ec"
  warn: "#b45309"
  warn-soft: "#fdf3e2"
  danger: "#c62f35"
  danger-soft: "#fcebec"
  info: "#0e7490"
  info-soft: "#e3f4f8"
  log-ground: "#141c27"
typography:
  headline:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif"
    fontSize: "21px"
    fontWeight: 700
    lineHeight: 1.3
  metric:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif"
    fontSize: "28px"
    fontWeight: 750
    lineHeight: 1.15
    letterSpacing: "-0.5px"
    fontFeature: "tnum"
  title:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif"
    fontSize: "14px"
    fontWeight: 700
    lineHeight: 1.4
  body:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.55
  label:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif"
    fontSize: "12.5px"
    fontWeight: 400
    lineHeight: 1.5
  mono:
    fontFamily: "'JetBrains Mono', 'SF Mono', Consolas, 'Courier New', monospace"
    fontSize: "12.5px"
    fontWeight: 400
    lineHeight: 1.65
rounded:
  sm: "7px"
  md: "10px"
  lg: "12px"
  pill: "999px"
spacing:
  xs: "6px"
  sm: "10px"
  md: "18px"
  lg: "28px"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.surface}"
    rounded: "{rounded.sm}"
    padding: "6px 14px"
    height: "34px"
  button-primary-hover:
    backgroundColor: "{colors.primary-dark}"
  button-ghost:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.sm}"
    padding: "6px 14px"
    height: "34px"
  button-danger-soft:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.danger}"
    rounded: "{rounded.sm}"
    padding: "6px 14px"
  card:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.lg}"
    padding: "16px 18px"
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.sm}"
    padding: "8px 11px"
    height: "36px"
  nav-link-active:
    backgroundColor: "{colors.primary-soft}"
    textColor: "{colors.primary-dark}"
    rounded: "8px"
    padding: "8px 10px"
  nav-count:
    backgroundColor: "{colors.warn-soft}"
    textColor: "{colors.warn}"
    rounded: "{rounded.pill}"
    height: "20px"
  nav-count-bad:
    backgroundColor: "{colors.danger-soft}"
    textColor: "{colors.danger}"
---

# Design System: Music Auto Tagger

## Overview

**Creative North Star: "The Night-Shift Librarian"**

面板大部分时间无人看管，像一位值夜班的图书管理员：安静地把唱片归档，只把真正拿不准的几张放到台面上等人决定。界面因此分两种音量：日常状态（监控中、已处理多少）用中性灰与白卡片低声陈述；需要人处理的积压（待确认、重新识别）用琥珀与红色计数在侧栏和概览里直接点名。

视觉语言是冷灰底上的白色卡片，一个蓝色只做主操作与选中。密度偏中等，信息靠细线分组而不是靠阴影和色块堆叠。所有页面共用同一条左侧导航，设置是一页而不是弹层。

**Key Characteristics:**
- 冷灰底 + 白卡片 + 1px 细线，阴影只做轻微浮起
- 唯一强调色蓝色，只给主按钮、选中导航、焦点与进度
- 状态色只表达状态：绿=成功、琥珀=待处理、红=失败/危险
- 数字一律等宽数字（tabular-nums），日志与路径用等宽字体
- 中英双语，所有静态文案走 `data-i18n`

## Colors

一套冷调中性色加一个蓝色，状态色各司其职，不做装饰。

### Primary
- **Signal Blue** (#1d6ef0)：主按钮、选中态、焦点环、进度条、相似度条最佳项。每屏只有一个主按钮用它填充。
- **Deep Signal Blue** (#1550c4)：主按钮悬停、浅蓝底上的文字（选中导航、徽章）。
- **Blue Wash** (#e9f0fe) 与 **Blue Hairline** (#c9dafc)：选中行、批量操作条、说明框的底与边。

### Neutral
- **Cool Ground** (#f2f4f8)：页面底色，所有卡片浮在其上。
- **Soft Ground** (#f7f8fb)：表头、分段控件槽、字段组底色。
- **Paper White** (#ffffff)：卡片、侧栏、输入框。
- **Hairline** (#e3e7ee) / **Strong Hairline** (#d3d9e3)：卡片边、分隔线 / 输入框与次按钮边框。
- **Ink** (#121a26)：正文与标题。**Soft Ink** (#4a576b)：次级正文。**Muted Ink** (#5f6b80)：说明、标签、占位符（白底与灰底均 ≥ 4.5:1）。
- **Log Night** (#141c27)：运行日志面板底色，唯一的深色区域。

### Status
- **Archive Green** (#12703a on #e7f6ec)：成功、已确认、已归档。
- **Pending Amber** (#b45309 on #fdf3e2)：待确认计数、需要人处理。
- **Failure Red** (#c62f35 on #fcebec)：失败计数、删除与不可逆操作。
- **Teal Info** (#0e7490 on #e3f4f8)：未验证归档等中性信息态。

### Named Rules
**The One Blue Rule.** 蓝色只属于主操作与选中。占位封面、装饰、分组标题都不得用蓝以外的彩色；封面占位只用蓝灰色相族。

**The Status Means Status Rule.** 绿/琥珀/红只出现在它们代表的状态上。积压计数：有失败用红，否则用琥珀，为零时回到中性灰。

## Typography

**Body Font:** 系统字体栈（-apple-system / Segoe UI / PingFang SC / Microsoft YaHei）
**Mono Font:** JetBrains Mono（回退 SF Mono / Consolas）

**Character:** 工具型界面，系统字体保证中日英混排（专辑名大量是日文）在 NAS 用户的各种客户端上都正常；层级靠字号与字重，不靠字体对比。

### Hierarchy
- **Headline**（700，21px，1.3）：页面标题（概览、待确认、设置…），手机端 19px。
- **Metric**（750，28px，1.15，-0.5px，等宽数字）：概览健康条里的核心数字。
- **Title**（700，14px）：卡片标题。
- **Body**（400，14px，1.55）：正文、表格内容（13px）。
- **Label**（400，12–12.5px）：说明、表头、元信息，用 Muted Ink。
- **Mono**（12.5px，1.65）：运行日志、文件路径。

### Named Rules
**The Tabular Numbers Rule.** 计数、时长、大小、时间一律 `font-variant-numeric: tabular-nums`，刷新时数字不跳动。

## Layout

外壳是两列网格：228px 白色侧栏（sticky，满高）+ 主区（24px 28px 内边距）。主区顶部是页头：标题 + 一行说明，右侧放页面级操作。概览为「健康条（监控状态 1.3fr + 三个数字）」+ 左宽右窄两栏（主栏自适应，右栏 320px）。待确认为 340px 队列 + 详情；设置为 196px 分节导航 + 表单卡片，底部保存条 sticky。卡片间距统一 18px。

响应式：≤1100px 两栏合并为单列，「等你处理」排到日志之前；≤860px 侧栏变为顶栏，导航收进「菜单」按钮，设置分节变为横向滚动标签；≤560px 健康条逐行排列。

## Elevation & Depth

以细线分层为主，阴影只给轻微浮起：卡片 `0 1px 2px rgba(18,26,38,.05), 0 2px 6px rgba(18,26,38,.04)`；弹层与 Toast `0 24px 60px -12px rgba(18,26,38,.28)`。弹层遮罩为不透明度 45% 的墨色，不用模糊。

### Named Rules
**The Hairline First Rule.** 分组先用 1px 细线和底色差，不够再加阴影；不使用彩色粗边条（>1px 的彩色左/右边框）来标记状态。

## Shapes

中等圆角：按钮与输入 7px，内部容器 10px，卡片与弹层 12px，计数与徽章为胶囊。封面占位 8px。所有边框 1px。

## Components

### Buttons
- **Shape:** 7px 圆角，最小高度 34px（小号 28px）。
- **Primary:** Signal Blue 填充白字；同一视图只出现一个。
- **Ghost:** 白底 + Strong Hairline 边框 + Ink 文字，承担绝大多数操作（含列表行内的「确认」）。
- **Danger-soft:** 白底红字红细边，用于清空、删除记录；不可逆的最终确认才用实心红。
- **Plain:** 无边框，用于次要链接式操作（重启服务、全部记录）。
- **Hover / Focus:** 背景与边框 150ms 过渡；焦点为 2px Signal Blue 外描边。

### Segmented Control
- Soft Ground 槽 + 2px 内边距，选中项白底 + 卡片阴影。用于日志级别、记录状态、重新识别分类等互斥筛选。

### Cards / Containers
- 12px 圆角、白底、1px Hairline、卡片阴影、16px 18px 内边距；卡片头标题 14px 粗体，右侧放操作。卡片内不再嵌套带阴影的卡片，内部分组用 Soft Ground 底的 10px 容器。

### Inputs / Fields
- 白底、Strong Hairline、7px 圆角、36px 高；聚焦为蓝边 + 3px Blue Wash 光晕；只读/禁用为 Soft Ground 底 + Muted Ink。

### Navigation
- 侧栏链接：图标 17px + 文字，悬停 Soft Ground，选中 Blue Wash 底 + Deep Signal Blue 字。分组小标题 11.5px Muted Ink（「需要处理」「资料」）。积压计数为右侧胶囊（琥珀/红）。底部为连接状态与账号菜单（向上展开）。

### Attention List（概览「等你处理」）
- 卡片内整行可点的链接列表：32px 图标格 + 标题/说明 + 右侧计数胶囊；直达待确认（专辑待确认 / 锁定专辑缺曲）与重新识别。

### Candidate Table（待确认）
- 时长相似度最高的候选整行 #f6f9ff 高亮并加粗；相似度列带 64px 细条（最佳为蓝，其余灰），窄屏只显示百分比；行内「确认」一律 Ghost，主按钮只在系统建议卡上。

## Do's and Don'ts

### Do:
- **Do** 每个视图只放一个 Primary 按钮，其余用 Ghost / Plain。
- **Do** 用琥珀/红计数把积压带到侧栏和概览，为零时回到中性。
- **Do** 所有新文案加 `data-i18n` 并同时补齐 `messages_zh_CN` 与 `messages_en_US`。
- **Do** 新页面复用 `.shell / .side / .main / .page-head` 外壳与 `shell.js`。

### Don't:
- **Don't** 使用渐变色块、渐变文字或彩色占位封面。
- **Don't** 用 >1px 的彩色左边框标记列表项或提示框。
- **Don't** 在标题上方加小号说明标签（如「系统建议」）。
- **Don't** 用 emoji 代替图标；图标统一 1.8 线宽的描边 SVG。
- **Don't** 把设置或长表单重新做成弹层。
