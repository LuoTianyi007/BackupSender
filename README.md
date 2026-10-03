# BackupSender

Windows 与 Android 本地文件夹备份工具。选择需要备份的文件夹和输出位置，支持加密压缩、定时任务、备份历史与保留版本数量。

## 下载

在 [GitHub Releases](https://github.com/LuoTianyi007/BackupSender/releases/tag/v1.0.0-preview.1) 下载。首次公开发布标为预览版：Windows 包采用 2026-10-02 构建；Android APK 保留原有 1.0.0 测试签名。

| 文件 | 用途 |
| --- | --- |
| BackupSender-Windows-x64-1.0.0-preview.1.zip | Windows 64 位便携包，解压后运行 backup_sender.exe |
| BackupSender-Android-1.0.0-preview.1.apk | Android 7.0 及以上安装包 |
| BackupSender-source-1.0.0-preview.1.zip | 两个平台的源码与第三方许可资料 |
| SHA256SUMS.txt | 文件校验值 |

Windows 必须保留解压后的完整文件夹，包括 data、tools 和 flutter_windows.dll。移动程序目录后，打开一次程序以更新开机启动路径。

Android 请通过系统文件夹选择器授权备份源和输出目录；如系统提示，授予应用所需的文件访问权限。后台定时任务受系统省电和厂商后台限制影响。此 APK 使用 Android Debug 签名，仅用于测试和直接下载安装；正式商店发布前需配置长期维护的发行签名。

## 功能与版本差异

- 两个平台支持多个备份文件夹、启用/禁用单个文件夹、备份命名、压缩密码、备份历史和保留版本数量。
- Windows 支持开机启动、开机后隐藏到托盘、手动启动显示窗口。每天定时任务在应用运行时执行，每天最多一次；退出、关机或睡眠时不会执行，也不会补跑。
- Windows 通过 7-Zip 创建 7z 文件，设置密码时加密文件名，完成后执行完整性检查。
- Android 使用原生存储接口、WorkManager 和 7-Zip JBinding，保留独立的安卓实现；Windows 的近期托盘与调度修改没有套用到安卓版本。
- 请使用专门的备份输出文件夹，保留版本清理会处理符合 backup_ 前缀的历史备份。

## 源码与构建

pc/ 是 Windows 项目；android/ 是 Android 项目。两者各有 pubspec.yaml，分别进入对应目录构建。未提交编译缓存、个人测试压缩包、本机路径配置和签名密钥。

需要 Flutter SDK（Dart 3.13.1+）。Windows 还需要 Visual Studio 的“使用 C++ 的桌面开发”工作负载；Android 需要 JDK 17、Android SDK 36 和 Flutter 对应的 NDK。

```powershell
cd pc
flutter pub get
flutter analyze
flutter test
flutter build windows --release
```

```powershell
cd android
flutter pub get
flutter build apk --release
```

Android 当前 Gradle 文件保留原项目的调试签名设置，重新构建前应按自己的发布安排配置签名。不要把私钥或 key.properties 提交到 GitHub。

## 发布验证

Windows 当前源码的 40 个关键文件与 2026-10-02 构建项目逐字节一致；2026-10-03 重新运行静态检查通过，11 项已有测试通过。发行包完整性和附带 7-Zip 文件已核对。托盘菜单的全部点击路径尚未进行完整人工测试。

Android 发布原目录中的现有 APK；已验证 APK 签名、包名、最低 Android 版本和 ABI，未在本机重新编译或进行手机端端到端测试。

第三方组件、源代码来源以及尚未核实的原生库源码对应关系见 [THIRD_PARTY.md](THIRD_PARTY.md)。
