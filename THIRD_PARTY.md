# Third-party components

## Windows 7-Zip

pc/tools/7za.exe 与 7za.dll 为未修改的 7-Zip 26.02 文件，已与官方 7z2602-extra.7z 逐字节比较。Copyright (C) Igor Pavlov。许可为 LGPL 2.1 与 BSD，部分解压代码另受 unRAR 限制；完整条款保留于 third-party/7zip-26.02-LICENSE.txt。

- 官方版本：https://github.com/ip7z/7zip/releases/tag/26.02
- 对应官方源代码：https://github.com/ip7z/7zip/releases/download/26.02/7z2602-src.tar.xz
- 本次 Release 同时附带该源代码压缩包。

## Android 7-Zip JBinding

Gradle 坐标 com.sorrowblue.sevenzipjbinding:7-Zip-JBinding-4Android:16.02-2.4。Maven POM 声明 GNU LGPL 2.1。原项目 jniLibs 中的四个 .so 文件与同一版本 Maven AAR 完全一致。APK 内原生库经过符号裁剪，与原始 AAR 的完整文件哈希不同。

- 发布者声明的源码仓库：https://github.com/SorrowBlue/7-Zip-JBinding-4Android
- 发布者的 Maven 文件目录：https://repo.maven.apache.org/maven2/com/sorrowblue/sevenzipjbinding/7-Zip-JBinding-4Android/16.02-2.4/
- 对应版本的 Java sources.jar 与 POM 已保留于 third-party/。
- Java sources.jar 不包含 C/C++ 原生库源码。发布者声明的 GitHub 仓库在本次核对时无法访问，尚未确认该预编译原生库对应的完整 C/C++ 源码提交。当前资料不能视为完整原生库对应源码包；后续维护应从可追溯源码重新构建并补齐资料。

## Flutter 与 Dart 依赖

Flutter 为 BSD 3-Clause，许可文本见 third-party/Flutter-LICENSE.txt。Dart 直接/间接依赖版本记录在各项目 pubspec.lock；发行文件夹中的 data/flutter_assets/NOTICES.Z 与 APK 的 Flutter 资源包含依赖许可公告。

## Android Java / AndroidX

- Zip4j 2.11.5：Apache 2.0，https://github.com/srikanth-lingala/zip4j/tree/v2.11.5
- AndroidX WorkManager 2.11.2：Apache 2.0，https://android.googlesource.com/platform/frameworks/support/
- Android/Kotlin 运行时与 AndroidX 的版本由 Gradle 和 Flutter 插件管理。

本仓库未另行指定自有应用代码的开源许可证；第三方组件继续采用各自许可。
