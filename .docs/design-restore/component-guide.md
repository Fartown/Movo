# 设计还原 · 组件用法指南（给改版页面用）

依据：`docs/DESIGN_SYSTEM.md`（v1.0 定稿）与 Figma「Movo · 首页重设计 v1」（fileKey `RDCD9OcgkFDkD45kyxtIoM`）。本文只说明代码里怎么用，规格以规范为准。

## 0. 硬性要求

1. **功能不能丢、不能坏**：改版只换视觉与排版。每个页面改之前先列出现有的全部可见元素与交互（按钮、开关、弹窗、长按、菜单、条件显示、禁用条件、错误提示、ON_RESUME 刷新等），改完逐条核对仍然存在且行为一致。存储 key、回调、路由、ViewModel/State 调用一律不改。
2. 不裸写颜色、dp、字号、动画时长：颜色用 `MovoColors`，间距 `MovoSpacing`，圆角 `MovoRadius`，尺寸 `MovoSize`，字体 `MovoTypography`，动效 `MovoMotion`（`ui/theme/`）。
3. 字重只有 Regular / Medium，不用 Bold / SemiBold。
4. 只做浅色；不再读 `MiuixTheme.colorScheme` 做新界面的颜色（Miuix 主题已映射到 Movo 色板，旧组件暂时保留也不会跑色）。
5. 不用 Toast / Snackbar 做新的反馈：结果就地显示（行说明、值、状态）。已有 Toast 如果改起来牵涉逻辑，可以保留并在报告里列出。
6. 图标用 `MovoIcon(MovoIcons.Xxx, …)`（Lucide 线条图标，`ui/theme/MovoIcons.kt`，由 `.docs/design-restore/tools/gen-icons.mjs` 生成）；缺图标时在报告里写出需要的 Lucide 名称，暂时保留原 Material 图标。
7. 列表页（设置类、列表类二级页）：**卡片外不放任何文字**；分组标题在卡片内（`CardTitle`）；说明写进卡片页脚（`CardFooter`，一句一行）；卡片之间 16，顶栏到第一张卡 12；页面左右 20。

## 1. 页面骨架

| 场景 | 用法 |
|---|---|
| LazyColumn 列表页 | `MovoListPage(title, onBack, actions = { MovoIconButton(...) }) { item { MovoCard { … } } }`：已含居中顶栏、Q7 滚动态、底色、左右 20、卡片间距 16、顶栏到第一张卡 12、底部留白 |
| 自定义内容页 | `MovoPage(title, onBack) { contentPadding, sidePadding -> … }`：`contentPadding.top` 是顶栏高度，内容自己处理滚动与左右边距 |
| 旧调用 | `MiuixScaffoldPage` / `MiuixScaffold` 已经换成新顶栏，但内容没有左右边距与卡片间距（兼容旧 Miuix Card 的 12 边距）；改版时换成 `MovoListPage` |

顶栏右侧操作：`MovoIconButton(icon = MovoIcons.Plus, contentDescription = …, onClick = …)`（热区 44、图标 24）。

## 2. 卡片与行

```kotlin
MovoCard {                         // 白底、圆角 28、0.5 发丝线、无阴影、下内边距 4
    CardTitle("基础能力")           // 13 Medium 次要色，上 16 下 4
    SettingsRow(                   // 56 / 68 高，标题 15 Medium，说明 13 Regular 次要色
        title = "网页浏览",
        subtitle = "搜索网页，打开链接并阅读正文",   // 可选
        leading = RowLeading.Icon(MovoIcons.Globe), // 只在设置主页这类「一级入口」用；二级页不放图标
        trailing = RowTrailing.Switch(checked) { … }, // 或 Arrow(value) / External(value) / Value(text) / Custom { } / None
        showDivider = true,        // 每张卡最后一行 false
        enabled = …,
        attention = false,         // 需要处理：Rose 警示图标 + 值前 Rose 点
        onClick = { … },           // 开关行不用传，整行点击即切换
        onLongClick = null,
    )
    CardFooter(listOf("第一句。", "第二句。"))   // ⓘ + 13 Regular 次要色，一句一行
}
```

- 行说明写法见规范 10.1：开关行写「开启后能做什么」，动词开头，一行写完，不加句号。
- 行内按钮：`MovoPillButton(label, onClick, icon = null, primary = false)`（32 高）。
- 整行按钮：`MovoBlockButton(label, onClick, tone = BlockTone.Primary/Secondary/Destructive)`（48 高）。
- 圆形按钮：`MovoCircleButton(icon, contentDescription, onClick, primary = false)`（40）。
- 开关：`MovoSwitch(checked, onCheckedChange)`（S2：开 = 浅 Indigo 轨道 + 深 Indigo 滑块）。
- 分隔线：`MovoDivider(start = 16.dp)`。
- 内容页分组标题（不是列表页）：`MovoSectionHeader(text, actionLabel, onAction)`。
- 可点击的自定义元素：`Modifier.movoClickable(PressKind.Card/Solid/Icon/Row/Link, shape) { … }`（不用水波纹）。
- 表面：`Modifier.movoSurface(shape, elevation = MovoElevation.E0/Card/Composer/Overlay)`。

## 3. 对话框

| 旧 | 新 |
|---|---|
| Miuix `WindowDialog` + `MiuixDialogActions` 确认 | `MovoConfirmDialog(show, title, message, confirmText, onConfirm, onDismissRequest, destructive = 删除/清除类为 true, confirmEnabled, cancelEnabled, extraContent = { 输入框等 })` |
| `WindowSpinnerPreference` / `OverlayDropdownPreference` 单选 | `SettingsRow(trailing = RowTrailing.Arrow(当前值)) { show = true }` + `MovoChoiceDialog(show, title, options, selectedIndex, onSelect, onDismissRequest)` |
| 带输入框的对话框 | `MovoConfirmDialog(extraContent = { … })`，输入框先保留 Miuix `TextField`（颜色已映射） |

## 4. 文字样式

`MovoTypography`：`displayGreeting` 30/40、`titlePage` 22/30、`titleSection` 16/22、`bodyStrong` 15/22 M、`bodyRegular` 15/22、`bodyReading` 16/26、`inputPlaceholder` 16/24、`labelMedium` 13/18 M、`labelRegular` 13/18、`microMedium` 12/16 M、`numericLabel` 13/18 M tnum。
颜色：主 `textPrimary`、次 `textSecondary`、三级 `textTertiary`（只用于非关键信息）。

## 5. 文案

- 新增文案写进 `res/values*/strings_movo.xml`（三份：英文、简体、繁体），键名 `movo_*`；已有字符串能用就用。
- 中文与英文/数字之间加半角空格；「·」两边不加空格；省略用「…」。

## 6. 验证

- `./gradlew :app:compileDebugKotlin -q`（与别人并行时，若报错文件不在你的范围内，等 1–2 分钟重试，不要改别人的文件）。
- 相关单测：`./gradlew :app:testDebugUnitTest --tests '<包名>.*'`。
- 真机（小米 15，adb 已连）截图由主流程统一做，子任务不装包。
