# Impeccable 来源

- 上游：[pbakaus/impeccable](https://github.com/pbakaus/impeccable)。
- 版本：4.7.0；提交：`5cb95eea9c96479f57afe0732a45470d30183326`。
- 收录路径：上游 `.agents/skills/impeccable/`，为已生成的 Codex skill。
- 许可与第三方声明：见同目录 `LICENSE`、`NOTICE.md`，均取自上述上游提交。

在本项目的 Codex 会话中使用 `$impeccable audit`、`$impeccable polish` 等命令。原生 Android 审计走 `reference/audit.native.md`。

首次运行启动器会下载与 `scripts/VERSION` 对应的平台引擎，并校验 SHA-256；引擎缓存在用户目录 `~/.impeccable/bin/`。本次配套引擎版本为 0.1.16。

更新时，从目标上游提交同步 skill 目录及许可文件，保留 `scripts/impeccable` 的可执行权限，并更新本文的版本和提交。
