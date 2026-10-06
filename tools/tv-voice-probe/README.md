# Android 9 / armeabi-v7a 语音库探针

独立工程，复用产品当前的 sherpa-onnx 1.13.8、speechengine_tob 0.0.15.0 和 KWS 模型，minSdk 28。只打包 armeabi-v7a。测试包开启 debuggable，便于读取私有证据；不用于评估非调试产品包的性能。

```sh
gradle -p tools/tv-voice-probe assembleRelease
adb -s DEVICE install -r tools/tv-voice-probe/build/outputs/apk/release/MovoTvVoiceProbe-release.apk
adb -s DEVICE shell am start -n io.github.fartown.movo.tvvoiceprobe/.VoiceProbeActivity
# 等待 MovoTvVoice 的 RESULT 日志，再读取：
adb -s DEVICE shell run-as io.github.fartown.movo.tvvoiceprobe cat files/voice-results.json
```

需 Android SDK 36、Gradle 8.14.3、JDK 17。构建从产品 assets 复制四个 KWS 文件和 AEC 模型，不读取产品配置或凭据。构建工具版本沿用历史实验环境；它是独立工程，不使用产品的 Gradle wrapper。

- SpeechEngine：加载原生库、创建 Dialog 引擎并尝试无凭据初始化，记录返回值，然后销毁。不发起云会话，也不把无凭据错误视为原生库加载失败。
- sherpa：初始化产品 KWS 模型，按 1600 样本分块输入 5 秒数字静音，执行 decode 并读取结果，记录耗时、CPU 时间和 PSS，最后释放所有原生对象。

这个探针没有录音权限，不采集麦克风、不保存人声、不访问云识别服务。静音推理通过只能证明模型和 JNI 可运行，不能证明唤醒效果、真实拾音或完整 ASR/TTS 已通过。
