# ASB Android 应用沙盒

ASB 在 Android 宿主内启动一台独立的 32 位 x86 来宾虚拟机。第三方 APK 由来宾 Android 安装。v86 在本地 WebView 中将 x86 指令动态转译为 WebAssembly，并模拟磁盘、VGA 和输入设备。

- [GitHub Wiki 站点](https://z2bguoguo.github.io/asb/)
- [Android 发布与下载](https://github.com/z2bguoguo/asb/releases)
- [CI 检查及调试 APK](https://github.com/z2bguoguo/asb/actions/workflows/build.yml)
- [中文版操作与兼容性说明](wiki/index.html)

## 运行

宿主 Android 11+，搭配较新的 Android System WebView。选取 32 位 x86 ISO 或 RAW 磁盘。建议先尝试上游记录的 Android-x86 4.4-r2，再安装来宾 API 和 ABI 支持的旧 APK。应用不附带来宾镜像。现代 ARM / 64 位 APK、Google Play 服务、宿主摄像头和磁盘共享通常需要额外的后端。

选取个人文件后，ASB 将它作为只读光盘挂入来宾。自定义模式以同样的方式传入用户指定的 UTF-8 文本。来宾应用列表由来宾系统提供。控制台可在失焦时暂停来宾，也可以只停用输入。私有磁盘的改动通过保存检查点留存。网络、摄像头和麦克风均未接入来宾。Shizuku 只用于可选的宿主连接诊断。

ASB 控制台启用宿主防截屏。不提供绕过第三方应用隐私保护的录制。目前如需录屏，请使用来宾系统自带的受授权能力。隔离实现未经恶意样本或虚拟机逃逸审计。

## 本地构建

需要 JDK 17、Gradle 8.13、Node 22+、Android SDK Platform 36 和 Build Tools 36.0.0。

```sh
npm --prefix vm ci
npm --prefix vm run build
npm --prefix vm test
gradle :app:assembleDebug :app:lintDebug
npm --prefix wiki ci
npm --prefix wiki run build
```

调试 APK 使用独立的 `cn.z2b.asb.debug` 应用 ID，正式版包名为 `cn.z2b.asb`。Wiki 通过 `npm --prefix wiki run dev` 预览。

## GitHub Actions

在 [Pages 设置](https://github.com/z2bguoguo/asb/settings/pages) 将 Build and deployment 的 Source 设为 **GitHub Actions**。`Publish Wiki` 工作流发布 Vite 构建结果。

正式版维护者在自己的设备上生成并备份一次私钥。以后每次发布都使用这把钥匙。

```sh
keytool -genkeypair -keystore asb-release.p12 -storetype PKCS12 -alias asb \
  -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=ASB Android Release'
keytool -list -v -keystore asb-release.p12 -alias asb
```

在仓库的 Actions 设置中配置：

| 类型 | 名称 | 值 |
| --- | --- | --- |
| Secret | `ASB_KEYSTORE_BASE64` | `base64 -w0 asb-release.p12` 的结果 |
| Secret | `ASB_KEYSTORE_PASSWORD` | 密钥库密码 |
| Variable | `ASB_SIGNER_SHA256` | 证书 SHA-256 指纹去掉冒号并转小写 |

`Publish ASB` 在 main 更新后构建，验证发布证书后将 APK 和 SHA-256 上传到 GitHub Releases。`versionCode` 使用此工作流的递增运行编号。正式版在后台检查发布，下载后校验 APK 的完整性、签名、包名和版本，再交给 Android 安装器确认。

## 第三方项目

- [v86 源码及许可证](https://github.com/copy/v86)
- [SeaBIOS 源码](https://github.com/coreboot/seabios)和 [VGABIOS 源码及固件构建说明](https://github.com/copy/v86/tree/9457e7a2f6890f3133bb6ac0f749e588f63c4c36/bios)
- [Shizuku API](https://github.com/RikkaApps/Shizuku-API)
