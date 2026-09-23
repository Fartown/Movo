# 随包默认模型

在项目根目录复制 `.env.example` 为 `.env`，填写 `ETA_DEFAULT_API_KEY`，按需编辑服务地址、模型 ID 和名称。Gradle 构建 debug/release 时会把这些值编入 APK；同名环境变量优先于 `.env`，未提供完整的 URL、Key、模型 ID 时沿用原有提供商初始化。

`.env` 使用单行 `NAME=value`，值可以带一对单引号或双引号；不展开变量，不处理行尾注释。Key 只保存在本地 `.env`，该文件已加入 Git ignore。**APK 包含默认 Key，拿到 APK 的人能够提取它。**

首次安装且模型提供商数据为空时，App 自动创建并选中这组默认模型。它是普通的可编辑提供商：可在「模型提供商」中修改 Key、URL 和模型，也可删除。后续启动或覆盖安装保留已保存的配置，不覆盖用户编辑，不重新补回已删除的默认提供商。已安装 App 的现有配置优先。

示例对应 Ark 的 Responses 接口；`ETA_DEFAULT_BASE_URL` 填 API 根地址，不追加 `/responses`。`ETA_DEFAULT_ENDPOINT_MODE` 支持 `responses`、`chat_completions`。上下文长度和托管搜索开关也可通过 `.env` 配置。

构建方式与原工程一致：`./gradlew :app:assembleRelease`。正式签名仍使用 `ETA_RELEASE_STORE_FILE`、`ETA_RELEASE_STORE_PASSWORD`、`ETA_RELEASE_KEY_ALIAS`、`ETA_RELEASE_KEY_PASSWORD`；模型默认配置不改变签名方式。

Release 和 Debug 按 CPU 架构分别出包，手机默认使用 `app-arm64-v8a-release.apk`；CI 的 `app-release.apk` 也是 ARM64 包。其他架构在 `release-other-abis` 制品中提供；豆包语音 SDK 仅提供 ARM 原生库，x86 系列包不能作为完整语音功能验收包。需要通用包时显式添加 `-PuniversalApk=true`，默认不生成。

正式 Release 保留离线唤醒模型，排除未被 JNI 使用的 Sherpa C/C++ 接口库。只有 `-PvoiceTestRelease` 验收构建才应用 `proguard-voice-test.pro` 的 Kotlin ABI 保留规则；发布包不使用这个参数。正式包仍需独立做启动、唤醒和语音回归，不能以验收构建代替。

CI 输出 `apk-size-report` 并在构建摘要显示各架构总大小、原生库、DEX 和唤醒资源占用；ARM64 发布包上限为 34 MB（十进制）。本地可运行 `python3 scripts/apk-size-report.py app/build/outputs/apk/release/app-*-release.apk --output tmp/apk-sizes.json --max-arm64-mb 34` 检查。此检查要求单架构正式包，不包含可选 universal 包。
