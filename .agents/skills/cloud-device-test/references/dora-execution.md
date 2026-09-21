# Dora 执行路径

先读当前 `bytedcli/SKILL.md` 及其 `references/subskills/bytedance-dora/GUIDE.md`。实际平台调用交给设备执行 subagent。参数以当前 help 为准。

## 设备与租约

1. `bytedcli --json dora device list --scope occupied` 查现有占用；`device get --serial <serial>` 核对机型/系统/在线/session/到期时间。平台 serial 和 ADB target 不同。
2. 新设备按用户品牌/系统过滤 `device list`；`device occupy` 后再 `get` 和 occupied 列表读回。优先使用小米，其次红米，最后选其他品牌。
3. 续期遵守用户时长和平台上限；长任务按当前 Dora 指南安排守护，记录 PID、日志、到期时间和归属。释放前停止本任务守护。
4. 持有本机动作锁后才操作。锁不能因时间久直接删除；确认原执行者结束并完成交接后由持有者释放。用户正在操作时暂停相关用例。

## 工具选择

| 需要 | 路径 |
| --- | --- |
| 当前画面点击/滑动、中文多行粘贴、按键 | `device control tap/swipe/text/key`，随后截图读回 |
| 状态截图/真录屏 | `device screen export --format png/mp4`，同一执行者协调录制和动作 |
| 文本/resource-id/content-desc 唯一定位 | `device ui get/execute`，依赖 ADB，显式 package |
| 包版本、系统读数、logcat、原生环境 | `device adb` 取址，验证后每条 `adb -s <target>` |
| 实时画面交接 | 打开当前有效远控 URL 并确认画面，历史 session URL 不证明租约有效 |

`control` 的 `sent` 没有设备动作 ACK。坐标来自本轮截图，不复用上次坐标。节点不可见时重新看图，不盲点。GUI 权限/通知对话框先稳定，再断言产品状态。

Dora 导出的画面可能经过缩放，媒体像素不一定等于设备坐标。先核对截图尺寸、设备分辨率与方向；无裁剪/黑边时按 `x_device = x_image × device_width / image_width`、`y_device = y_image × device_height / image_height` 映射，再取整并检查边界。有旋转、裁剪或黑边时先确认显示区域，不直接套缩放。浏览器远控画面还可能有页面缩放，不能把网页坐标直接传给设备控制。

原生 MP4 有时长限制，后台录制覆盖动作；长用例分段并标注时间范围和空档。ADB screenrecord 也要核对实际支持时长，不假设无限录制。截图轮播不是录屏。

## 安装与配置

复用正确已安装包。需要安装时：已有可达 HTTPS URL 可用原生部署；需要摘要校验时用 ADB 设备侧下载；只有本地 APK 时走 ADB，跟踪实际进度。原生 URL 部署没有 hash 校验，不能声称校验了设备包摘要。不要默认将私有 APK 上传公开服务。

安装后核对包名/版本并启动。签名冲突且需保留用户数据时不默认卸载。模型配置写入后在 App 发本轮标识消息，记录响应与工具事件；命令行 HTTP 200 不代表 App 链路通过。

采证避开明文 Key。模型不支持图片、测试 URL 404、网络失败均记录具体事实，不自动归为产品缺陷。
