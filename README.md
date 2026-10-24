# pebble-app-mod

My personal version of the Core Devices Pebble app ([coredevices/mobileapp](https://github.com/coredevices/mobileapp), GPL-3.0). It is built from the newest upstream release:

1. The patches in `patches/` are applied in order.
2. The files in `overlay/` are copied over the result. These are whole files that replace upstream ones that differ between versions.

| Patch | What it changes |
|---|---|
| `001-self-hosted-dictation` | Watch dictation goes to a self-hosted recognizer, reached through my relay, instead of WisprFlow, so Chinese works without foreign services. "Cloud Only" no longer needs a Core account. |
| `002-settings-backup-and-updates` | Long-press the app icon → 备份与更新. Export or import all settings as one zip; import restarts the app. Every 12 h the app checks the relay for a newer build and notifies. |
| `003-on-device-sensevoice` + `overlay/` | On-device dictation. The "System (On-Device)" speech mode runs SenseVoice-Small (Chinese, English, Japanese, Korean, Cantonese) on the phone through sherpa-onnx. The model (~230 MB) downloads once from hf-mirror.com. If on-device recognition fails, it falls back to the cloud recognizer. |
| `008-backup-update-settings-entries` | Android Settings → General adds Backup and updates and Check for updates, reusing the existing launcher-shortcut activity. |

Adding a change: put `003-....patch` (a `git format-patch` against an upstream tag) in `patches/`, then push. The workflow (`.github/workflows/build.yml`) builds the APK, uploads it to the relay, and the app's update check offers it.

The repository holds no secrets. Tokens, the relay address and the signing key are Actions secrets (listed at the top of the workflow). The APK carries a token, so it is never published in the open: it goes to the relay only, plus a GitHub release if the repository is private.

## Phone

- **First install:** uninstall the official app (the signature differs), install the APK, and pair the watch again. Then go to Settings → Speech → Offline Speech Recognition → **System (On-Device)**, which downloads the model once. **Cloud Only** uses the relay instead.
- **Updates:** tap the notification and install over the old one. Settings stay.

No Firebase config is included, so features that need a Core Devices account (sign-in, cloud sync) don't work in this build.
# 中文、日文与显示语言

设置 → 常规 → App 显示语言（设置按设备分类时位于“手机”下），可选择跟随系统、简体中文、日本語、English；选择会保存，切换后立即刷新界面。Android 和 iOS 共用翻译与设置，平台语言环境各自实现。

`localization/catalog.json` 是翻译来源，按原文匹配，保留格式参数。`tools/localize.py` 在构建时用 Kotlin 语法树处理已确认的界面文字，生成标准 Compose `values-zh`、`values-ja` 资源及运行时文本表。界面代码的整页副本不放进 overlay；上游挪动代码时无需重新定位行号。语言入口保留为独立的 `004` 补丁。

上游新增文案默认回退英文，并记录到 Actions 的 `localization-report`，便于补译。生成的资源可单独整理成上游翻译提交。脚本不会翻译用户输入、录音转写或协议标识。

当前翻译表有 1,434 项，覆盖设置、引导、权限说明、手表选项和 Index 界面。
`005` 让设置分类名称随语言更新，`007` 刷新缓存文案和标题；资源查找单独刷新，
保留当前导航与表单状态。上游已有的中文、日文资源会保留。iOS 实现尚未在 macOS 上编译验收。

`006` 将固件的字体能力位 60、表情回落能力位 61 暴露给 PebbleKit JS，供 Claw 等应用协商使用。
上游能力位 27（App 权限）保持原意。官方固件不会误开启这些功能。

本地检查：`python -m pip install -r requirements-localization.txt -r requirements-transfer.txt`，然后 `python -m unittest discover -s tests -v`。构建继续沿用现有私有 relay 分发流程，APK 不放公开附件。

跨境上传较慢时，Actions 另存一份加密 APK 附件（保留一天），使用 AES-256-GCM，
随机密钥由 RSA-OAEP 加密。仓库只有公钥 `tools/apk-transfer-public.pem`；
私钥保存在本机 `../pebble-signing/apk-transfer-private.pem`，需与签名密钥一起备份，绝不提交。
构建编号、版本、大小与 SHA-256 均经过认证，附件内容或元数据被修改时会拒绝解密。

`tools/finish-private-build.ps1 -RunId <Actions运行ID>` 自动下载并验证 APK；
加 `-Publish` 可经现有 `tc` SSH 通道补传，校验文件后原子替换服务器上的 APK 与更新信息。
CI 会识别同一构建已发布并结束上传。整个过程不公开 APK 明文或访问令牌，手机仍使用原来的更新入口。
本地检查加密传输还需 `pip install -r requirements-transfer.txt`。

安卓“设置 → 常规”也提供“备份与更新”和“检查更新”：前者打开现有导出、导入及更新菜单，
后者立即检查此定制版 App 的更新。长按图标的两个入口继续保留；两处使用同一份备份与更新逻辑。
这两个入口及说明随 App 显示语言切换。新增平台桥接文件独立放在 `overlay/pebble/`，
设置页只增加 `008` 的小补丁。原备份/更新功能为安卓专用，iOS 不显示这些入口。


## Live dictation (PT2 firmware extension)

Patch 009 adds commands 0x04 (replace the current preview) and 0x05 (stop capture).
Only dictation sessions explicitly requesting the extension (setup flag 31) use it;
official firmware keeps the existing single-result path. PT2 firmware advertises bit 62.
The existing selected recognizer handles incremental full-clip snapshots, with one
inference in flight and a conflated queue. PCM-based endpointing waits for speech,
then 700 ms of silence; recording remains bounded to 15 s. Final results still use
command 0x02. Model/network speed determines preview latency; real-device timing is
not implied by the protocol tests. Recognition failure never sends a partial as final.
Cancellation closes the decoder and cancels pending work. The original uncommitted
SenseVoice warm-up draft is kept separate from these changes.
