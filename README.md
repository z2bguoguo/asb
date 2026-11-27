# ASB 应用沙盒

ASB 使用 Android 工作资料运行应用的独立副本。支持 Android 11 及以上，录屏需要 Android 14 或以上。

- 从个人版 ASB 创建工作资料，然后通过桌面「工作」标签打开带公文包标记的 ASB。
- 支持安装单文件 APK、启动和暂停工作应用、管理应用声明的运行时权限，以及对工作资料执行摄像头、截屏录屏和剪贴板规则。
- 提供可按应用启用的测试接口。焦点、应用列表、摄像头图片和文件文本支持阻止、自定义与穿透。调用方须接入测试接口，穿透时使用原生 Android API。
- 可通过系统选择器录制用户选定的单个应用为无声 MP4。系统决定选项与保护画面处理。选择带公文包标记的应用进行录制。
- 支持 Shizuku 连接诊断。工作资料使用 Android 系统权限，本项目无需 Shizuku 授权。
- 定期检查 GitHub Releases。下载后核对包名、版本和签名，再交由系统安装器确认。

完整操作和测试接口说明位于 [Vite Wiki](wiki/index.html)。

## 构建

需要 JDK 17、Android SDK 35 和 Node.js 22.12 或更高版本。CI 使用 Node.js 24。

```sh
./gradlew :app:assembleDebug :app:lintDebug
cd wiki
npm ci
npm run dev
```

CI 会上传 `ASB-debug` 和 `ASB-wiki`。启用 Wiki 发布时，在 [Pages 设置](https://github.com/z2bguoguo/asb/settings/pages) 将 Source 设为 GitHub Actions。之后推送 `main` 或手动运行构建工作流。

## 正式发布与升级

在 [Actions Secrets](https://github.com/z2bguoguo/asb/settings/secrets/actions) 设置以下仓库密钥。正式版应一直沿用同一签名密钥。

| 参数 | 内容 |
| --- | --- |
| `ASB_KEYSTORE_BASE64` | keystore 文件的 Base64 |
| `ASB_KEYSTORE_PASSWORD` | keystore 密码 |
| `ASB_KEY_ALIAS` | 密钥别名 |
| `ASB_KEY_PASSWORD` | 密钥密码 |

在 Actions variables 添加 `ASB_SIGNER_SHA256`，值为发布密钥证书的 SHA-256 指纹。可使用 `keytool -list -v -keystore <keystore> -alias <alias>` 查看。发布工作流会核对实际 APK 的签名指纹。

从 `main` 上的提交创建并推送语义版本标签，例如 `v0.1.0`。版本必须递增，自动计算的 Android versionCode 为 `major * 1000000 + minor * 1000 + patch`。工作流发布名为 `ASB.apk` 的资源。由同一密钥签名的正式版支持覆盖升级。首次使用正式版时请直接安装正式 APK，以便后续版本沿用同一签名。
