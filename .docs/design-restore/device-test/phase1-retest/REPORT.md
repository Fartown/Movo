# 第一段问题复测（同一台设备）

- 设备：Redmi K80 Pro，Dora serial `c6688527`，adb `10.76.222.97:37445`，session `20260926061201341554a10cdd696A9FB`；**未释放**，占用到 2026-09-26 16:12:01（查询时剩约 8.7 小时，未续租，见 `common/device_get_end.json`）
- APK：3.0.9 debug arm64，sha1 前 12 位 `6ee6549a5f8e`，07:11:59 覆盖安装
- 环境：系统深色、手势导航、三个动画倍率 = 1；结束时无障碍已重新写入、悬浮窗 allow、默认助手 Movo
- 稳定性：全程无崩溃、无 ANR；录屏抽帧后已删除（本地与设备均无视频）
- 执行：测试子代理（auto-tester）；本文件由主流程按其回报整理落盘
- 复用：`common/h.sh`（连接与截图）、`common/lum.py`（逐帧亮度）；中文输入用 `bytedcli dora device control text --serial c6688527 --text ...`；**键盘已收起时按返回会退出 App**

## 结论

| 项 | 结论 | 现象 | 证据 |
|---|---|---|---|
| F1 侧边栏 + 系统返回 | 通过 | 左右边缘慢速返回都跟手收起，松手停在 App；拖一段再拖回松手会弹回。备注：拖约 150–200px 已基本收完，比手指快 | `F1/left_edge/sheet.png`、`F1/right_edge/sheet.png`、`F1/cancel_rec/sheet.png`、`F1/cancel_sheet.png` |
| F2 空闲后发送 | 通过（2/2） | 两次空闲 87–89 秒后发送都正常回答 | `F2/run1_result.png`、`F2/run2_result.png`、`F2/send*_time.txt` |
| M1 冷启动进场 | 不通过 | 深色模式下启动页为深色（约 RGB 34,35,31），约 0.5 秒后直接跳到浅色首页；冷启动总恢复上一次会话，看不到首页进场（点「+」时首页进场正常） | `M1/splash_dark_frame50.png`、`M1/first_app_frame55.png`、`M1/run2/sheet_50_71.png`、`M1/plus_entrance/sheet_30.png`、`M1/run3`、`M1/run4_launcher_intent` |
| M2 能力卡进入对话 | 通过 | 卡片收成气泡移到顶部、首页淡出上移，约 0.4–0.5 秒 | `M2/run1/sheet_40.png` |
| M3 浮层展开到 App | 不通过 | 无灰色矩形帧；但切到 App 时侧边栏先显示为打开，约 0.2 秒内收起 | `M3/expand/sheet.png`、`M3/key_sidebar_open_after_expand_f060.png`、`M3/key_sidebar_closing_f064.png` |
| M4 记忆页返回手势 | 通过（附备注） | 跟手缩小不淡出，拖到一半停住不会完成；松手回设置页，标题不再二次飞回；点返回按钮时标题飞回行内。备注：松手后一帧直接切换，无收尾动画 | `M4/gesture/sheet.png`、`M4/key_*`、`M4/backbutton/sheet.png` |
| M5 开关按压 | 通过 | 按住时圆点拉长，松手恢复，状态不变 | `M5/compare.png` |
| M6 切换语言 | 不通过 | 切到 English 和切回跟随系统各出现 5 帧（约 167ms）纯黑；首帧新旧标题叠在一起 | `M6/key_black_f046.png`、`M6/to_english/sheet.png`、`M6/to_system/` |

## 新发现

1. 冷启动总是恢复上一次会话，即使退出前停在「+」新建的首页
2. 深色模式下启动页为深色后直接跳到浅色首帧（安装包里启动页底色已是浅色 #FFF4F3EF，怀疑系统对启动页做了强制深色，未证实）
3. 浮层「展开到 App」时侧边栏先露出再收起
4. 切换语言黑屏约 167ms；英文界面有直译（set up / about / source code）
5. 重新打开 App 后会话停在顶部，不在最新消息
6. 记忆页系统返回手势松手后无收尾动画

顺带：首页问候折为「今天想交给 / 我什么？」，没有再出现只剩「么？」（`M1/home_after_new.png`，未正式验证）。

## 未测（超出本轮上限）

小光球残留、问候折行（正式）、输入框换行、记忆页、会话顶栏长标题；回归：侧边栏拖动吸附、二级页标题移动、发送飞成气泡与按句显现、删除消息离场、侧边栏删除会话、浮层弹出 / 点遮罩关闭 / 下拉关闭。

完整日志：`logcat_full.txt`
