# 媒体会话探针

独立工程，验证某个 App 的系统媒体会话能不能跳到指定时间、调倍速、切集，以及能不能读回进度。不读取通知内容，只用通知使用权取得会话。

```sh
./gradlew -p tools/media-session-probe assembleDebug
adb -s DEVICE install -r tools/media-session-probe/build/outputs/apk/debug/MovoMediaSessionProbe-debug.apk
adb -s DEVICE shell cmd notification allow_listener io.github.fartown.movo.mediaprobe/io.github.fartown.movo.mediaprobe.ProbeListener
adb -s DEVICE shell am broadcast -n io.github.fartown.movo.mediaprobe/.ProbeReceiver --es cmd seek --el ms 300000 --es pkg tv.danmaku.bili
adb -s DEVICE logcat -s MediaProbe
```

cmd：status、seek（--el ms）、speed（--ef speed）、next、previous、pause、play。
