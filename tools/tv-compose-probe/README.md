# Android 9 TV 焦点列表性能探针

独立实验工程，比较 100 项列表中的 tv-material Button 和 foundation 轻量焦点态。用于复核 [P0 性能数据](../../docs/research/tv-voice-app/P0真机验证-TCL-ak30a5.md)，不接入产品构建，不含模型凭据、录音或语音播报。

需要 Gradle 8.14.3、JDK 17、Android SDK 36；在仓库根目录运行：

```sh
gradle -p tools/tv-compose-probe assembleRelease
adb -s DEVICE install -r tools/tv-compose-probe/build/outputs/apk/release/MovoTvComposeProbe-release.apk
adb -s DEVICE logcat -v threadtime -s MovoTvCompose:I > compose-metrics.log
```

另开终端启动页面，初始化后等待 3 秒，再持续用遥控器方向键上下移动焦点：

```sh
adb -s DEVICE shell am start -S -n io.github.fartown.movo.tvcomposeprobe/.BenchmarkActivity --es variant default
# 保存本轮日志后，另开一轮测试：
adb -s DEVICE shell am start -S -n io.github.fartown.movo.tvcomposeprobe/.BenchmarkActivity --es variant lightweight
```

程序排除前 3 秒预热，于第 40 秒输出 `METRICS`：帧数、P50/P95/最大帧耗时、超过 16.67ms 的帧数、焦点变更数和丢弃报告数。离开页面还会输出 `reason=pause`；比较时选择两轮完整的 `reason=40_seconds`，同时检查操作次数，不能把更少操作当成性能改善。release 使用 debug 签名但关闭 debuggable，不作为发布包。

P0 使用过 shell 常驻按键进程；它属于已淘汰的 ADB 控制原型，没有并入产品或本探针。这里使用实体遥控器，因此新测量只能作为新的样本，不能声称精确复现旧的按键节奏。不要从这个单页实验外推整个产品的性能。
