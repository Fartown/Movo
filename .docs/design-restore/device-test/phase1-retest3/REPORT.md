# 第三轮验收（图标 / 名称 / M1 诊断 / M3 / M6）

- 设备：Redmi K80 Pro，Dora serial `c6688527`，session `20260926061201341554a10cdd696A9FB`，**未释放**，占用到 2026-09-26 16:12:01（`device_status.txt`）
- APK：sha1 `14aafcb61f8b`（改名前，包名仍为 io.github.mangi.eta），`adb install -r`；无障碍重新写入，悬浮窗 allow，默认助手 Movo；系统深色模式
- 执行：测试子代理（auto-tester）；本文件由主流程按其回报整理落盘

## 结论

| 项 | 结论 | 证据 |
|---|---|---|
| 1a 桌面图标与名称 | 通过：浅丁香底 + 光球 + 浅色 M，名称「Movo」 | `1a_home.png`、`1a_home_icon_crop.png` |
| 1b 系统各处名称 | 通过：最近任务、应用信息、默认数字助理均为 Movo；无障碍页被 Dora 密码锁，改用 APK 资源佐证（`agent_accessibility_label` = Movo Agent） | `1b_*.png`、`1b_apk_labels.txt` |
| 1c 主题 / 单色图标 | 本机不支持（HyperOS 只有「经典 / 无界」图标风格） | `1c_icon_style_options.png` |
| 1d 启动页图标 | 通过：深 / 浅色冷启动均为同一光球 + M | `2a_dark_strip.png`、`2c_light_strip.png` |
| 2 M1 启动页颜色 | 已诊断（见下） | `2a_dark_strip.png`、`2c_light_strip.png`、`2b_*.png`、`2d_*.txt` |
| 3 M3 展开到 App ×3 | 通过：无侧边栏、无灰色缩小窗口帧 | `3_r1_strip.png`、`3_r{1,2,3}_after.png` |
| 4 M6 切换语言 | 失败：冷启动后首次切到 English 仍 relaunch（`shouldRelaunchLocked = true`，`finishDrawing of relaunch … 237ms`），2 帧全黑；英文文案正确（Settings / About / Source code）；切回跟随系统不 relaunch | `4_en_black_strip.png`、`4_logcat_relaunch.txt`、`4_settings_en.png`、`4_settings_back_zh.png` |
| 5 快速回归 | 通过：飞成气泡、按句显现、侧边栏开合、浮层点遮罩关闭 | `5_*.png` |

## M1 诊断

- 只在系统深色模式下变深：深色时系统启动页约 #1B1A17（亮度约 55），App 接管后 #F3F2F0；浅色时启动页为正确的 #F4F3EF，无暗帧
- 本机 HyperOS OS2.0 没有按应用的深色模式开关，无法用开关验证
- 日志：启动器深色下 `mStartingWindowColor=ff000000`、浅色下 `ffffffff`；App 进程有 `ForceDarkHelperStubImpl … ForceDarkOrigin`；`dumpsys uimode` 深色时 `mNightMode=2`
- 推测（未验证）：系统绘制启动页时做了深色化，主题里的 `forceDarkAllowed=false` 对系统绘制的启动页不生效

## 新发现

1. 展开到 App 时闪 1 帧（约 67ms）深色启动页（#232221 + 光球），3/3 复现；每次展开都为 MainActivity 加启动页窗口且不走任务快照（`preAllowTaskSnapshot=false`），根因同 M1（`3_r1_strip.png`、`3_logcat_startingwindow.txt`）
2. M6 未修复（同上）
3. 轻微：点发送瞬间软键盘短暂下落再弹起约 2 帧（`5_send_strip.png`）

备注：`logcat_full.txt` 中一行疑似某系统应用的密钥已打码；录屏已删除。
