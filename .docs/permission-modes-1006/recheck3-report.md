# 权限模式第三轮复测报告（R3：空输入框写入）

- 设备：小米（Dora 云真机），Dora 序列号 e3ef776f，ADB 10.76.222.13:41635（型号与系统版本未在本轮记录，Dora 设备信息见 device/R3/device.txt 与 boot.png）
- APK：Movo-permission3-arm64.apk，sha256 前 16 位 d76c39a4a8fe30a1
- 证据根目录：/Users/bytedance/dev/Movo/.docs/permission-modes-1006/device/R3/
- 说明：截图为点击后连拍，release 包无 Movo 进程日志，未归档 logcat。

## W1 YOLO 空框输入：通过
- 卡片 0 张。
- 执行卡共 6 步，全部 ✓：打开应用·设置 0.3s、查看屏幕（28 条）、点击屏幕 0.4s、查看屏幕（14 条）、输入文本·2 字符 0.5s（第一次即 ✓，无 ✕ 输入步骤）、查看屏幕（24 条）。
- 设置搜索框显示「蓝牙」并有结果（蓝牙、蓝牙设备黑名单、显示没有名称的蓝牙设备、始终保持蓝牙开启 等）。
- 最终回复（开头与结尾）：「已完成。设置已打开，并在搜索框输入“蓝牙”，搜索结果显示以下相关项：」+ 结果表格 +「需要我点进某一项（比如蓝牙主设置页），或帮你开启/关闭蓝牙吗？」
- 证据：R3/W1/（conv_top.png、conv.png、c3_a.png、c3_b.png、s1-s6.png）

## W2 应用规则空框输入：通过
- 前置：手动审批，5 项类别规则全关，应用卡加「设置」；强停设置，搜索记录页有历史词但搜索框为空。
- 卡片 2 张，卡头均为「需要你确认 · 你选的应用」，标题「在「设置」里继续操作？」，脚注「你设了在「设置」里每一步都先问你。」，按钮「拒绝 / 继续」。
  - 卡 1 灰底块「下一步：点按「搜索系统设置项」」，点继续后进入搜索页（搜索框为空）。
  - 卡 2 灰底块「下一步：输入「蓝牙」」，点继续后搜索框出现「蓝牙」并有结果（c2_2 起），其后连拍 c2_2-c2_6 无新卡，没有文字相同的第二张输入卡。
- 执行卡：已完成 6 个步骤，用时 33 秒，全部 ✓：思考、打开应用·设置 0.3s、查看屏幕（28 条）、点击屏幕 8.0s、查看屏幕（14 条）、输入文本·2 字符 14.9s（含等待审批）、查看屏幕（24 条）；无 ✕ 输入步骤。
- 最终回复：「已在设置中搜索「蓝牙」，结果页显示了 7 条相关项：」蓝牙（蓝牙设置页）、蓝牙→蓝牙、蓝牙设备黑名单（高级设置）、显示没有名称的蓝牙设备（高级设置）、始终保持蓝牙开启（高级设置）、蓝牙（通知与状态栏→图标显示自定义）、蓝牙设备解锁；「需要我点进哪一项吗？」
- 证据：R3/W2/（a1.png、c1_1-4.png、c2_1-6.png、steps.png、steps2.png、conv_top.png）

## W3 恢复与释放：通过
- 权限模式 YOLO（W3/perm_top.png）；5 项类别规则开关全关；应用规则「设置」已移除，应用卡仅剩「添加应用」（W3/rules_after_remove.png）。
- 无障碍服务恢复为 com.android.cts.verifier/com.bytest.service.ByAccessibilityService，accessibility_enabled=1。
- 已移除 deviceidle 白名单、删除 /data/local/tmp/m.apk、卸载 Movo（包列表 0）；SYSTEM_ALERT_WINDOW 授权随卸载清除。
- 已释放 e3ef776f，occupied 列表 total=0，adb 已断开。
- 证据：R3/W3/（restore.txt、release.txt、occupied-after.txt、perm_top.png、perm_bottom.png、rules_after_remove.png）

## 其他观察
- 测试中一次误点（600,1500）把工具页「读取敏感信息」关掉（W1/afterstraytap.png），已重新打开并确认（setup/tools_fixed.png）；该开关为 Movo 卸载前已恢复，卸载后无残留。
- 设置应用的搜索历史（亮度、wenti、kaifa 等）未清除，非本轮产生。
- 会话列表里有两个「打开设置，搜索蓝牙」会话（W1、W2 各一个），随卸载清除。
