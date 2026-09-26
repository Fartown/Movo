# 改名后冒烟测试（io.github.fartown.movo）

- 设备：Redmi K80 Pro，Dora serial `c6688527`，session `20260926061201341554a10cdd696A9FB`，**未释放**，占用到 2026-09-26 16:12:01（`device_status.json`）
- 旧包 `io.github.mangi.eta` 已卸载；新包 sha1 `f30b6ae74292`，3.0.9（versionCode 2026092306，appId 10359）；麦克风 / 通知 / 悬浮窗 / 无障碍已授权，默认助手 = io.github.fartown.movo
- 执行：测试子代理（auto-tester）；本文件由主流程按其回报整理落盘；本地与设备均无 mp4

## 结论（9 项：8 通过，第 8 项拿到诊断）

| # | 项 | 结论 | 现象 | 证据 |
|---|---|---|---|---|
| 1 | 冷启动 / 图标 / 名称 | 通过 | 冷启动 660ms；桌面「Movo」；安装目录有 `libmovo_pty.so` | `1/launcher_icon_crop.png`、`2/home.png`、`1_dumpsys_package.txt` |
| 2 | 首页与对话 | 通过 | 「1+1」答 2；「你是谁」答「我是 Movo，运行在你 Android 手机上的 AI 助手……」 | `2/answer1.png`、`2/answer2_whoami.png` |
| 3 | 设置 / 二级页 / 侧边栏 | 通过 | 记忆、外观、权限可进可退，侧边栏开合正常 | `3/*.png` |
| 4 | 终端 | 通过 | 输出 `movo-ok` 与 `aarch64`；无 pty 错误、无 `libeta_pty` 残留 | `4/terminal_output.png`、`4_pty_errors.txt` |
| 5 | 浮层 | 通过 | `KEYCODE_ASSIST` 唤起，点遮罩回桌面 | `5/assist_overlay.png`、`5_after_scrim_tap.txt` |
| 6 | 打开系统设置 | 通过（有异常） | 悬浮球 + 边缘光晕，系统设置被打开，完成 ✓，点球打开结果；第一步 open_uri 报「uri 缺少 scheme」后 Agent 换方式成功 | `6/frame03_running_ball.png`、`6/frame06_edge_glow.png`、`6/frame29_ball_done.png`、`6/ball_tapped.png` |
| 7 | 数据备份导出 | 通过 | `/sdcard/Download/Movo-backup-20260926-0901.movo-backup.json`（66211 B，含 API Key，未拉回本地） | `7/export_picker.png`、`7_backup_file.txt` |
| 8 | 语言切换诊断 | 拿到根因；有黑帧 | 冷启动后首次切 English 被重建，30fps 录屏第 56–60 帧整屏黑约 167ms；切回跟随系统不重建 | `8_locale_diag.txt`、`8_frame_luma.txt`、`8/f05*.png`、`8/f061_after.png` |
| 9 | 崩溃 / ANR | 通过 | FATAL / ANR 0 条，与 Movo 相关 0 条 | `9_crash_anr_all.txt` |

## 第 8 项日志

```
09-26 09:02:18.362 ActivityTaskManager: shouldRelaunchLocked = true, ...
09-26 09:02:18.442 I Movo: MainActivity relaunched: config diff=0x2104 (locale|screenLayout|layoutDirection)
09-26 09:02:18.626 WindowManager: finishDrawing of relaunch: Window{449a384 u0 io.github.fartown.movo/io.github.fartown.movo.ui.MainActivity} 264ms
```

根因：切换语言时 `Configuration.screenLayout` 里的布局方向位随之变化（CONFIG_SCREEN_LAYOUT），而 MainActivity 未声明 `screenLayout`，系统因此重建页面。

## 新发现

1. 语言切换黑帧（见上，待修）
2. 「打开系统设置」第一步 open_uri 因 uri 缺少 scheme 失败，Agent 自行换方式成功（模型参数问题，非界面问题）
