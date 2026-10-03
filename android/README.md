# BackupSender Android

本目录保留独立的安卓 Flutter 项目。包名 com.example.backup_sender，当前版本 1.0.0+1。公开预览 APK 为已有 Release 构建，使用 Android Debug 签名。

使用 Android SDK 36、JDK 17 和 Flutter（Dart 3.13.1+）构建。执行 flutter pub get，然后 flutter build apk --release。本机 SDK 路径需自行配置；发行签名需在 android/app/build.gradle.kts 中设置，签名密钥不得提交。

android/app/src/main/jniLibs 下保留原项目的 7-Zip JBinding 原生库，已与 Maven Central 的 16.02-2.4 AAR 逐字节核对。许可证与源码对应关系说明见仓库根目录 THIRD_PARTY.md。
