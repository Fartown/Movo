# Movo 能否自己打开 TCL 电视的 ADB（2026-10-07）

APK、反编译源码和设备快照在本地 `.docs/tv-adb-tool/`（证据，不入库）。用户 10-07 定：不做这个工具。

结论：现在不能。等 Movo 能向前台发真实按键（例如 TCL 6553 按键通道）后，可以做一个只在 TCL 上用的工具，代价是要写死 TCL 的隐藏操作流程。

## 证据

### 1. TCL 设置的 ADB 开关改了什么
来源：`SystemSettings.apk`（`com.tcl.settings`），`SystemData.setCurrentADBState`。

- 打开时：
  - `SystemProperties.set("persist.sys.usb.config","adb")`
  - `SystemProperties.set("sys.usb.config","adb")`
  - `SystemProperties.set("persist.sys.service.adbd.enable", "1")`
  - `Settings.Global.adb_enabled = 1`
- 关闭时：四项全部改回。

### 2. 真正启动 adbd 的只有 TCL 的属性
来源：电视上的 `/init.usb.rc`。

- `on property:persist.sys.service.adbd.enable=1` → `start adbd`。
- `on property:persist.sys.service.adbd.enable=0` → `stop adbd`。
- 原生 `sys.usb.config=adb` 触发下的 `start adbd` 全被注释掉了。
- `on boot` 里执行 `setprop persist.sys.service.adbd.enable 0`，所以每次开机 ADB 都会关掉。10-07 傍晚断连就是这个原因：开机原因 `reboot,adb`，开机约 19:22。
- `service.adb.tcp.port=5555` 是开机时就设好的，adbd 一启动就监听网络端口。

### 3. Movo 能做什么
- Movo 有 `WRITE_SECURE_SETTINGS`，能写 `adb_enabled`。但这一项启动不了 adbd。
- `persist.sys.*` 属性只有 system 进程能写；TCL 设置是系统应用，Movo 不是。

### 4. 剩下的路：像人一样操作 TCL 设置
- ADB 开关在「系统信息」页，标记 `adb`，默认隐藏（`AdbViewModel.isNeedHide` → `ListSettingPageView.mHideDeviceMore`）。
- 显示隐藏项的方法：焦点在设备信息上时，相邻两键间隔都在 1 秒内，依次按 上 下 左 右（`DeviceInfoViewModel.HIDE_KEY_LIST = 19, 20, 21, 22`，`CLICK_INTERVAL_TIME = 1000`）。
- 这些按键必须作为真实按键事件送到 TCL 设置的界面。
  - Movo 现在在 Android 9 上只能用无障碍的全局动作（返回、主页）和移动焦点，发不出方向键事件。
  - TCL 6553 按键通道正由电视 Agent 重设计评估。
- TCL 设置能用 extra 跳到指定页面（`ViewControlManager`：`INTENT_SETTING_ITEM` / `INTENT_SETTING_CATEGORY`），具体取值还没核实。

## 如果要做
前提：有了真实按键通道。

做法：
1. 打开 TCL 设置的「系统信息」页；
2. 发上下左右；
3. 找到「ADB」行并切到「开」；
4. 回读 `adb_enabled` 和端口 5555 确认结果。

注意：
- 只在 TCL 上提供。
- 这是写死的私有流程，TCL 系统升级后可能失效。
