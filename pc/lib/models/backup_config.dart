import 'dart:io';

class BackupConfig {
  final List<String> folders;
  final List<String> disabledFolders;
  final String archiveName;
  List<String> get enabledFolders =>
      folders.where((f) => !disabledFolders.contains(f)).toList();
  static String cleanArchiveName(String value) => value
      .trim()
      .replaceFirst(RegExp(r'\.(zip|7z)$', caseSensitive: false), '')
      .replaceAll(RegExp(r'[<>:"/\\|?*\x00-\x1f]'), '_')
      .replaceAll(RegExp(r'[. ]+$'), '');
  String createBackupName(DateTime now) {
    String two(int n) => n.toString().padLeft(2, '0');
    final stamp =
        '${now.year}${two(now.month)}${two(now.day)}_${two(now.hour)}${two(now.minute)}${two(now.second)}_${now.millisecond.toString().padLeft(3, '0')}';
    final name = cleanArchiveName(archiveName);
    return 'backup_$stamp${name.isEmpty ? "" : "_$name"}';
  }

  final bool autoBackup;

  final String schedule;

  final String zipPassword;

  // 新增：备份保存位置
  final String backupPath;

  // 新增：是否启用压缩
  final bool enableCompression;

  final int backupHour;

  final int backupMinute;

  final int maxBackupVersions;

  final bool enableStartup;
  final bool startupToTray;

  BackupConfig({
    required this.folders,
    this.disabledFolders = const [],
    this.archiveName = "",

    required this.autoBackup,

    required this.schedule,

    this.zipPassword = "",

    required this.backupPath,

    this.enableCompression = true,

    required this.maxBackupVersions,

    this.enableStartup = false,
    this.startupToTray = true,

    this.backupHour = 2,
    this.backupMinute = 0,
  });

  factory BackupConfig.defaultConfig() {
    return BackupConfig(
      folders: [],

      autoBackup: false,

      schedule: "startup",

      zipPassword: "",

      // 默认还是桌面 BackupSender
      backupPath:
          '${Platform.environment['USERPROFILE']}\\Desktop\\BackupSender',

      enableCompression: true,

      backupHour: 22,

      backupMinute: 0,

      maxBackupVersions: 5,
      enableStartup: false,
    );
  }

  BackupConfig copyWith({
    List<String>? folders,
    List<String>? disabledFolders,
    String? archiveName,

    bool? autoBackup,

    String? schedule,

    String? zipPassword,

    String? backupPath,

    bool? enableCompression,

    int? backupHour,

    int? backupMinute,

    int? maxBackupVersions,

    bool? enableStartup,
    bool? startupToTray,
  }) {
    return BackupConfig(
      folders: folders ?? this.folders,
      disabledFolders: (disabledFolders ?? this.disabledFolders)
          .where((f) => (folders ?? this.folders).contains(f))
          .toList(),
      archiveName: archiveName ?? this.archiveName,

      autoBackup: autoBackup ?? this.autoBackup,

      schedule: schedule ?? this.schedule,

      zipPassword: zipPassword ?? this.zipPassword,

      backupPath: backupPath ?? this.backupPath,

      enableCompression: enableCompression ?? this.enableCompression,

      backupHour: backupHour ?? this.backupHour,

      backupMinute: backupMinute ?? this.backupMinute,

      maxBackupVersions: maxBackupVersions ?? this.maxBackupVersions,

      enableStartup: enableStartup ?? this.enableStartup,
      startupToTray: startupToTray ?? this.startupToTray,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      "folders": folders,
      "disabledFolders": disabledFolders,
      "archiveName": archiveName,

      "autoBackup": autoBackup,

      "schedule": schedule,

      "zipPassword": zipPassword,

      "backupPath": backupPath,

      "enableCompression": enableCompression,

      "backupHour": backupHour,

      "backupMinute": backupMinute,

      "maxBackupVersions": maxBackupVersions,

      "enableStartup": enableStartup,
      "startupToTray": startupToTray,
    };
  }

  factory BackupConfig.fromJson(Map<String, dynamic> json) {
    return BackupConfig(
      disabledFolders: List<String>.from(json["disabledFolders"] ?? []),
      archiveName: json["archiveName"] ?? "",

      folders: List<String>.from(json["folders"] ?? []),

      autoBackup: json["autoBackup"] ?? false,

      schedule: json["schedule"] ?? "startup",

      zipPassword: json["zipPassword"] ?? "",

      backupPath:
          json["backupPath"] ??
          '${Platform.environment['USERPROFILE']}\\Desktop\\BackupSender',

      enableCompression: json["enableCompression"] ?? true,

      backupHour: json["backupHour"] ?? 22,

      backupMinute: json["backupMinute"] ?? 0,

      maxBackupVersions: json["maxBackupVersions"] ?? 5,
      enableStartup: json["enableStartup"] ?? false,
      startupToTray: json["startupToTray"] ?? true,
    );
  }
}
