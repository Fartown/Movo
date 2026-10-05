# 全量真机测试说明（给测试执行代理）

被测包与用例清单见同目录 `test-plan.md`。本文件只写通用做法。

## 约束

- 只用小米设备：Dora 云真机或本机小米。用 bytedcli 申请和释放；测完必须释放，并恢复改过的系统设置。
- 不点「在微信告诉妈妈」这类卡；不真实外发、不付款；点击前确认前台页面。
- 「发送 / 支付」类审批只验证卡片出现和「拒绝」路径，不点「允许」。
- 不读、不打印仓库根目录 `.env`；logcat 归档前删掉含 key、token、Authorization、api_key、Bearer 的行（不区分大小写）。
- 证据放 `.docs/full-review-1005/device/<用例编号>/`：截图 + 过滤后的 logcat（只留 `io.github.fartown.movo` 进程或 tag 含 Movo、Agent 的行）。

## 安装与权限

1. 卸载旧版（如有）后安装被测包。
2. 记下原值后开启：
   - 无障碍：`settings put secure enabled_accessibility_services io.github.fartown.movo/io.github.fartown.movo.agent.accessibility.AgentAccessibilityService`，`accessibility_enabled=1`
   - 悬浮窗：`appops set io.github.fartown.movo SYSTEM_ALERT_WINDOW allow`
   - 通知权限、忽略电池优化
3. 首次启动按引导走完；默认模型已注入 Key，可直接用。

## 发中文指令

`adb input text` 不支持中文，用分享：

```
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "<中文指令>" \
  -n io.github.fartown.movo/.ui.share.ShareReceiverActivity
```

然后在分享面板里点发送。也可以用其他可靠的中文输入方式，汇报里写明。

## 每条用例要记录

- 弹过几次审批卡、卡上原文（截图）
- 执行卡每一步的标题与 ✓ / ✕
- 最终回复原文
- 悬浮球、光晕、展开卡在关键时刻的截图（跨应用用例）
