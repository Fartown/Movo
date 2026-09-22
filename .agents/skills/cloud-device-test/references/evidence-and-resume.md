# 原生证据与恢复

下列 `<...>` 必须替换为实际路径/参数。

```text
tmp/tasks/<date>-<task>/
  scope.md / checkpoint.json
  environment.json                # 原生执行器采集
  environment-capture/            # 原始命令、退出码、输出
  cases/<case>.json
  <case>-r1/                      # 与 environment.json 同级
    meta.json / facts.jsonl / run.json
    evidence/<step>.<ext>
  report.html / report.md
```

## 动作独占和检查点

```bash
python3 <skill>/scripts/checkpoint.py init --path <task>/checkpoint.json --run-id <task-id> --serial <dora-serial> --owner <agent-name>
python3 <skill>/scripts/checkpoint.py acquire --path <task>/checkpoint.json
python3 <skill>/scripts/checkpoint.py show --path <task>/checkpoint.json
python3 <skill>/scripts/checkpoint.py begin --path <task>/checkpoint.json --case TC-01 --step S1 --action '发送本轮唯一标识消息'
```

操作后先将 fact 用 `workflow.py append` 写入对应 run，再提交检查点：

```bash
python3 <skill>/scripts/checkpoint.py complete --path <task>/checkpoint.json --fact <task>/<case>-r1/S1-fact.json --status PASS
python3 <skill>/scripts/checkpoint.py update --path <task>/checkpoint.json --patch <state-patch.json>
python3 <skill>/scripts/checkpoint.py release --path <task>/checkpoint.json
```

complete 的 status 是人工传入进度，不是脚本结论；最终由 workflow 推导。事实须对应 pending 的 step、被相邻 `facts.jsonl` 接收，`meta.json` 的 case ID 也须一致。pending 会阻止再次 begin/释放锁。确认动作未生效或需退出时，`resolve --note '<读回证据和下一步>'` 记录原因再解除 pending；脚本不重试设备动作。

pending 和事实时间使用同一台主机的带时区毫秒或微秒时间。先 begin，再记录本次动作 started_at；不能填旧 run 的时间或截断为整秒，complete 会拒绝早于 pending 的事实。设备日志自身时间单独保留，不充当本地动作起止时间。

update 只更新 `foreground`、`lease`、`artifact`、`config`、`remaining`、`resources`、`notes`。lease 包含实际到期时间，artifact/config 仅含版本/来源/非敏感指纹，resources 记录本轮创建或用户已有及保留要求，不写 Key。

锁默认放 `~/.cache/cloud-device-test/locks/`。同一检查点可重入，不自动到期/抢占。换设备需新检查点；其他 owner/run 拒绝。锁不能拦截绕过脚本的 ADB/用户操作，每步仍需核对画面。`checkpoint.py release` 仅释放本机动作锁，不释放 Dora 租约，也不代表用户同意释放设备。平台租约默认保留；释放前在 checkpoint 的 notes/resources 中记录用户明确确认的原话、对应设备及范围，恢复执行时必须核对该确认，不能从任务已完成或锁已释放推断授权。

## 原生环境和 test-workflow

仅设备执行 subagent 实际采集：

```bash
python3 <skill>/scripts/validate_evidence.py capture-env --adb-target <target> --package <package> --out <task>
python3 <test-workflow>/scripts/workflow.py init --case <task>/cases/TC-01.json --out <task>/TC-01-r1 --run-id <unique-id>
```

采集器执行只读 ADB：连接状态、serial、Android 版本/SDK、分辨率、包版本、ADB 版本；保存原始输出/hash。device_serial 是设备读回标识；Dora serial 在 checkpoint 中。已安装版本与 APK 构建/hash 分开记录，版本号不能证明包字节一致。

环境使用 `ui_platform: android-native` 和 `screen`，browser/viewport 为不适用。已有 environment/capture 时拒绝覆盖，环境变化建立新目录与新 run。test-workflow 若仍报必须用浏览器，则说明版本不兼容，不手造浏览器字段绕过。

append/finalize/render 沿用 test-workflow。查看内容后才能写媒体 verification。manual 必须填写 actual、status、assessor、rationale；机器断言不手填 status。

```bash
python3 <skill>/scripts/validate_evidence.py check-run --run-dir <task>/TC-01-r1
```

检查元数据、缺步骤、文件归属/hash，用 ffmpeg 解码媒体；输出 file_checks 和 missing_steps，不产生业务 PASS，不写 verification。缺 ffmpeg/解码失败属于证据检查未完成。解码成功仍需看关键内容；已生成 run.json 时一并检查 completion 录屏。

## 恢复与结论

读取 checkpoint → 核对平台租约/设备/包配置 → acquire → 检查 pending。未决动作先读事实/画面/请求标识，再 complete 或 resolve；禁止直接重发。变更用例/环境用新 run。

完整原生证据可 PASS，人工式审查与机器断言分别展示。旧 waiver run 保持原判定；报告运行 test-workflow 的 validate_report.py 校验，默认不打开页面，报告校验不计入产品通过数。
