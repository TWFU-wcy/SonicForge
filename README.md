# 音量增强 VolumeBoost

解决手机（尤其小米 HyperOS）外放/媒体声音过小的问题。两条路一起走：

1. **普通模式**：在 `audioSession = 0` 挂音效链，全局放大所有 App 的输出（无需 ROOT）
2. **ROOT 模式**：把同一条音效链搬进 uid 0 的守护进程，绕开厂商音效独占 session 0 的限制，并可用 tinymix 抬高 codec 硬件增益

![主页 浅色](docs/screenshots/home-light.png)

<details>
<summary>更多截图：深色主页 / 均衡器 / 设置</summary>

![主页 深色](docs/screenshots/home-dark.png)

![均衡器](docs/screenshots/equalizer.png)

![设置](docs/screenshots/settings.png)

</details>

> 截图来自 Android 16（API 36）模拟器，真机上的毛玻璃与光斑效果会更明显。

## 功能

- 🔊 全局音量增益 0 ~ +18 dB（`DynamicsProcessing` 输入增益，挂载全局混音 session 0）
- 🛡️ 增益与均衡都在限幅器**之前**，峰值被压到 -1.5 dBFS 以下，不会削波爆音
- 🎛️ 硬件均衡器：频段数量由系统真实返回（10/8/6/5/3 自动适配），不是写死的十段
- 🎸 低音补偿：直接叠加在最低几个频段上，同样受限幅器保护
- 🎧 虚拟环绕：仅在插入耳机/蓝牙时可用，扬声器下自动禁用
- 🎙️ 单声道：写系统 `master_mono`（无障碍 → 单声道音频）；ROOT 下无需授权
- 🎼 4 个预设：标准增强 / 人声增强 / 外放最大 / 夜间清晰
- 🔓 **ROOT 硬件增强**：tinymix 直接抬高 codec 的 RX/SPK Digital Volume（最高 +12 dB，可一键恢复）
- 🔓 **释放全局音效通道**：临时禁用厂商音效包并重启 audioserver，让本应用的音效能挂上 session 0
- 🌙 Material 3 明暗主题、单页面简洁 UI

## 环境要求

- **Android 版本**：9.0（API 28）及以上；测试目标 Android 17 / 小米 18 Pro Max
- **构建工具**：Android Studio Ladybug+，或命令行 JDK 17 + Android SDK 35
- **Gradle**：8.9（wrapper 已配置），AGP 8.7.3，Kotlin 2.0.21

## 构建

```bash
# Android Studio：直接 Open 本目录即可
# 命令行（需已安装 Android SDK，设置 ANDROID_HOME）：
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 安装到手机

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

小米 18 Pro Max（HyperOS）注意：首次安装后如提示"限制后台"，请在 设置 → 应用管理 → 音量增强 → 省电策略 选「无限制」，否则切后台后效果可能被系统冻结。

## 使用

1. 打开 App → 选择预设（推荐先用「标准增强」）
2. 打开「增强开关」
3. 播放任意音乐/视频，拖动「主音量增益」滑块到合适位置
4. 需要更响的外放场景切「外放最大」
5. 关闭开关即完全恢复原声，重启手机后效果自动消失

> **通知栏**：ROOT 模式下音效由 uid 0 的守护进程承载，**不会出现常驻通知**，也不需要 App 留在后台；只有非 ROOT（进程内效果链）模式才需要用前台服务保活，此时才会显示「增强运行中」的通知。

## ROOT 增强（可选）

临时 ROOT（KernelSU / SukiSU 等注入的 `su`）可用时，App 会自动优先走 ROOT 模式，设置页会出现「ROOT 硬件增强」卡片。

### 三条能力

| 能力 | 做法 | 说明 |
|---|---|---|
| **ROOT 全局音效** | 用 `su` 起一个 uid 0 的 `app_process` 守护进程（`RootDaemon`），在进程内把 `DynamicsProcessing` 挂到 session 0 | 普通 App 创建全局音效会被 AudioFlinger 以缺少 `MODIFY_AUDIO_ROUTING` 拒绝；uid 0 进程不受此限制，这是真机报「系统拒绝了音效创建」的正解 |
| **硬件增益** | `tinymix` 读写 codec 的 `* Digital Volume` 控件 | 唯一能突破「数字信号已到 0 dBFS」天花板的手段，最高 +12 dB；以首次应用前的原始值为基准，反复拖动不叠加，可一键恢复 |
| **释放全局音效通道** | `pm disable-user --user 0 <厂商音效包>` + `setprop ctl.restart audioserver` | 杜比/DTS/小米音效会独占 session 0 的音效链路，禁掉后本应用的音效才能挂上去；可随时恢复 |

### 前提

- `su` 可用且已授权本应用（首次点开关会弹 KernelSU 授权框，请点「允许」）
- 设备自带 `tinymix`（`/vendor/bin`、`/system/bin` 或 `/system/xbin` 下）；没有则硬件增益不可用，其余功能不受影响
- 临时 ROOT **重启即失效**：重启后重新注入 ROOT，再打开 App 点「重新检测」即可

### 守护进程

- 位置：`com.volumeboost.app.daemon.RootDaemon`，通过 `app_process` 以 uid 0 启动
- 通信：文件轮询（`files/vb_daemon.cmd` → `files/vb_daemon.status`），App 被杀不影响它
- 心跳：守护进程每 2 秒刷新一次状态文件的时间戳；App 启动时读心跳，若发现它还在跑就自动接管界面状态（不会出现「显示已关闭但声音还在增强」）
- 生命周期：开关关闭时写 `active=0`，守护进程自行释放音效并退出；配置文件被删除超过 5 秒也会自动退出
- 后台常驻：守护进程用 `setsid` 脱离 App 会话，并把 `oom_score_adj` 调到 `-900`，App 退出/被划掉后仍继续工作；**ROOT 模式因此不需要前台服务，也不显示常驻通知**
- 仍会失效的两种情况：手动杀掉守护进程；手机重启（临时 ROOT 本身失效）
- 日志：`files/vb_daemon.log`，也可在设置页「音效诊断」里查看尾部

## 原理与限制

- 效果链挂在 `audioSession = 0`（全局混音输出），对所有 App 的媒体流生效。
- 主链路是一条 `DynamicsProcessing`：输入增益 → preEq → limiter → 输出。顺序由系统保证，增益和均衡都在限幅器之前，所以推上去的峰值一定会被压回 0 dBFS 以下。
- 若机型拒绝创建 `DynamicsProcessing`，自动降级为单个 `LoudnessEnhancer`（无限幅），此时增益上限压到 +8 dB，并在界面提示。
- 两者都失败时，界面显示「系统拒绝了音效创建」，不会假装成功。
- 均衡器频段数量与中心频率全部来自系统返回，界面按真实频段渲染；当前模式不支持的控件自动置灰。
- 单声道写的是系统设置 `master_mono`，普通模式需要「修改系统设置」权限，未授权时自动跳转授权页；ROOT 模式下直接写，无需授权。
- 不申请 `RECORD_AUDIO`，不捕获任何音频数据，纯输出侧处理。
- 有线/蓝牙耳机同样有效，但耳机端音量独立控制，需两端同时调。
- 长期高增益 + 高音量有损扬声器风险，建议增益 ≤ +12 dB、系统媒体音量 ≤ 80%。
- App 本身不含任何提权或漏洞利用代码，只在系统已经提供 `su` 时使用它。

## 工程结构

```
app/src/main/java/com/volumeboost/app/
├── VolumeBoostApp.kt              # Application，预初始化引擎并注入 ROOT 客户端
├── audio/
│   ├── AudioEngine.kt             # 效果链核心（ROOT / FULL / DEGRADED / EQ_BOOST 四级）
│   └── Preset.kt                  # 4 个预设参数
├── daemon/
│   └── RootDaemon.kt              # uid 0 守护进程：在 session 0 建 DynamicsProcessing 链
├── root/
│   ├── RootShell.kt               # su 探测与命令执行
│   ├── RootDaemonClient.kt        # 守护进程的启停与文件通信
│   ├── RootAudioManager.kt        # tinymix 硬件增益 / 单声道 / 厂商音效开关
│   └── MixerControl.kt            # tinymix 输出解析
├── service/
│   └── BoostForegroundService.kt  # 前台服务（mediaPlayback）保活效果链
└── ui/
    ├── MainActivity.kt            # 单 Activity + Compose 入口
    ├── App.kt                     # 简洁主界面
    └── theme/Theme.kt             # Material 3 主题
```

## 许可

MIT
