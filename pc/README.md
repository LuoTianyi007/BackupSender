# Backup Sender

Flutter 文件夹备份应用，支持 Windows 和 Android。

## Windows 使用

- 设置 → Windows 开机启动：登录后自动运行。
- 设置 → 开机启动后隐藏到托盘：默认开启，仅影响开机自启。手动双击程序仍显示窗口。
- 点击托盘图标或再次双击程序可打开窗口；关闭窗口返回托盘，右键托盘选择“退出”结束程序。
- 每天定时备份：运行期间在设定时刻执行，每天最多一次。退出、关机或睡眠期间不执行，也不会补跑错过的时间。
- 旧版已启用自启的用户，打开新版一次即可更新启动项；下次登录使用新参数。
- 移动程序目录后需打开新版一次，让自启路径更新。

## 构建与验证

需要 Flutter SDK（Dart 3.13.1+）及带“使用 C++ 的桌面开发”工作负载的 Visual Studio。

```powershell
flutter pub get
flutter analyze
flutter test
flutter build windows --release
```

发布时复制整个 `build/windows/x64/runner/Release` 目录，不能只复制 exe。
CMake 会自动把 `tools/7za.exe` 和 `tools/7za.dll` 打包到发布目录。

Windows 压缩使用 7z，设置密码时加密文件名，创建后执行完整性检查。
7-Zip 非零退出码会报告失败；取消任务会等待进程停止，并清理本次临时目录。
保留版本清理请使用专用备份目录：会清理符合 `backup_` 前缀的历史目录或 zip/7z 文件。
