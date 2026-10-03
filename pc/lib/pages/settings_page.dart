import 'package:flutter/material.dart';

import '../models/backup_config.dart';
import '../services/config_service.dart';

import 'package:file_picker/file_picker.dart';

import '../services/startup_service.dart';

import 'dart:io';

import 'package:backup_sender/services/android_folder_service.dart';

class SettingsPage extends StatefulWidget {
  final BackupConfig config;
  final ValueChanged<BackupConfig>? onChanged;

  const SettingsPage({super.key, required this.config, this.onChanged});

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  bool _persisting = false;
  BackupConfig? _pendingConfig;
  late bool _startupApplied;
  late bool _startupToTrayApplied;

  void _requestPersist() {
    final config = widget.config.copyWith(
      archiveName: BackupConfig.cleanArchiveName(archiveNameController.text),
      enableStartup: enableStartup,
      startupToTray: startupToTray,
      autoBackup: autoBackup,
      schedule: schedule,
      backupHour: backupHour,
      backupMinute: backupMinute,
      zipPassword: passwordController.text,
      backupPath: backupPath,
      enableCompression: enableCompression,
      maxBackupVersions: maxBackupVersions,
    );
    _pendingConfig = config;
    widget.onChanged?.call(config);
    _persistCurrent();
  }

  @override
  void setState(VoidCallback fn) {
    super.setState(fn);
    _requestPersist();
  }

  Future<void> _persistCurrent() async {
    if (_persisting || _pendingConfig == null) return;
    _persisting = true;
    try {
      while (_pendingConfig != null) {
        final config = _pendingConfig!;
        _pendingConfig = null;
        try {
          await ConfigService.saveConfig(config);
          await _applySideEffects(config, showErrors: true);
        } catch (error) {
          if (mounted) {
            ScaffoldMessenger.of(context)
                .showSnackBar(SnackBar(content: Text('设置保存失败：$error')));
          }
        }
      }
    } finally {
      _persisting = false;
    }
  }

  Future<void> _applySideEffects(
    BackupConfig config, {
    required bool showErrors,
  }) async {
    try {
      if (Platform.isAndroid) {
        if (config.autoBackup && config.schedule == 'daily') {
          if (config.backupPath.isNotEmpty &&
              config.backupPath.startsWith('content://')) {
            await AndroidFolderService.scheduleDailyBackup(
              hour: config.backupHour,
              minute: config.backupMinute,
              destinationUri: config.backupPath,
              folders: config.enabledFolders,
              enableCompression: config.enableCompression,
              maxBackupVersions: config.maxBackupVersions,
              password: config.zipPassword,
            );
          }
        } else {
          await AndroidFolderService.cancelDailyBackup();
        }
      }
      if (Platform.isWindows &&
          (_startupApplied != config.enableStartup ||
              _startupToTrayApplied != config.startupToTray)) {
        if (config.enableStartup) {
          await StartupService.enableStartup(toTray: config.startupToTray);
        } else {
          await StartupService.disableStartup();
        }
        _startupApplied = config.enableStartup;
        _startupToTrayApplied = config.startupToTray;
      }
    } catch (e) {
      if (showErrors && mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('应用设置失败：$e')));
      }
    }
  }

  late TextEditingController archiveNameController;
  late bool autoBackup;

  late String schedule;

  late int backupHour;
  late int backupMinute;

  late int maxBackupVersions;

  late TextEditingController passwordController;

  late String backupPath;

  late bool enableCompression;

  late bool enableStartup;
  late bool startupToTray;

  @override
  void dispose() {
    archiveNameController.removeListener(_onTextChanged);
    passwordController.removeListener(_onTextChanged);
    archiveNameController.dispose();
    passwordController.dispose();
    super.dispose();
  }

  @override
  void initState() {
    super.initState();
    archiveNameController = TextEditingController(
      text: widget.config.archiveName,
    );

    backupHour = widget.config.backupHour;
    backupMinute = widget.config.backupMinute;

    autoBackup = widget.config.autoBackup;

    schedule = widget.config.schedule;

    maxBackupVersions = widget.config.maxBackupVersions;

    passwordController = TextEditingController(text: widget.config.zipPassword);
    archiveNameController.addListener(_onTextChanged);
    passwordController.addListener(_onTextChanged);
    backupPath = widget.config.backupPath;

    enableCompression = widget.config.enableCompression;

    enableStartup = widget.config.enableStartup;
    _startupApplied = enableStartup;
    startupToTray = widget.config.startupToTray;
    _startupToTrayApplied = startupToTray;
  }

  void _onTextChanged() {
    _requestPersist();
  }

  Future<void> pickBackupTime() async {
    final selectedTime = await showTimePicker(
      context: context,
      initialTime: TimeOfDay(hour: backupHour, minute: backupMinute),
    );

    if (selectedTime == null) {
      return;
    }

    setState(() {
      backupHour = selectedTime.hour;
      backupMinute = selectedTime.minute;
    });
  }

  Future<void> selectBackupPath() async {
    String? path;

    if (Platform.isAndroid) {
      path = await AndroidFolderService.pickFolder();
    } else {
      path = await FilePicker.getDirectoryPath();
    }

    if (path != null) {
      setState(() {
        backupPath = path!;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text("备份设置")),

      body: Padding(
        padding: const EdgeInsets.all(20),

        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,

            children: [
              TextField(
                controller: archiveNameController,
                maxLength: 60,
                decoration: const InputDecoration(
                  labelText: '压缩文件名',
                  hintText: '例如：照片备份',
                  helperText: '生成 backup_时间_自定义名称；扩展名自动添加，留空使用默认名称',
                  helperMaxLines: 2,
                ),
              ),
              const SizedBox(height: 16),

              SwitchListTile(
                title: const Text("自动备份"),

                value: autoBackup,

                onChanged: (v) {
                  setState(() {
                    autoBackup = v;
                  });
                },
              ),

              if (Platform.isWindows)
                SwitchListTile(
                  title: const Text('Windows开机启动'),
                  subtitle: const Text('登录系统后自动启动 Backup Sender'),
                  value: enableStartup,
                  onChanged: (value) {
                    setState(() {
                      enableStartup = value;
                    });
                  },
                ),

              if (Platform.isWindows)
                SwitchListTile(
                  title: const Text('开机启动后隐藏到托盘'),
                  subtitle: const Text('后台运行备份；点击右下角托盘图标打开窗口。手动启动仍显示窗口。'),
                  value: startupToTray,
                  onChanged: enableStartup
                      ? (value) => setState(() => startupToTray = value)
                      : null,
                ),
              if (Platform.isWindows && autoBackup && schedule == 'daily')
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 8),
                  child: Text('定时备份需要程序保持运行。关闭窗口会进入托盘；从托盘退出后停止定时备份。'),
                ),

              const SizedBox(height: 20),

              const Text(
                "备份模式",

                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
              ),

              RadioGroup<String>(
                groupValue: schedule,
                onChanged: (value) {
                  if (value != null) setState(() => schedule = value);
                },
                child: const Column(
                  children: [
                    RadioListTile<String>(
                      title: Text('启动时备份'),
                      value: 'startup',
                    ),
                    RadioListTile<String>(
                      title: Text('每天定时备份'),
                      value: 'daily',
                    ),
                  ],
                ),
              ),

              if (schedule == "daily")
                Row(
                  children: [
                    const Text("时间:"),

                    const SizedBox(width: 20),

                    DropdownButton<int>(
                      value: backupHour,

                      items: List.generate(
                        24,

                        (i) => DropdownMenuItem(value: i, child: Text("$i 点")),
                      ),

                      onChanged: (v) {
                        setState(() {
                          backupHour = v!;
                        });
                      },
                    ),

                    DropdownButton<int>(
                      value: backupMinute,

                      items: List.generate(
                        60,
                        (i) => DropdownMenuItem<int>(
                          value: i,
                          child: Text('${i.toString().padLeft(2, '0')} 分'),
                        ),
                      ),

                      onChanged: (v) {
                        setState(() {
                          backupMinute = v!;
                        });
                      },
                    ),
                  ],
                ),

              const SizedBox(height: 30),

              const Text(
                "保留备份版本数量",

                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
              ),

              DropdownButton<int>(
                value: maxBackupVersions,

                items: [3, 5, 7, 10, 20]
                    .map(
                      (e) => DropdownMenuItem<int>(
                        value: e,

                        child: Text("保存 $e 个版本"),
                      ),
                    )
                    .toList(),

                onChanged: (v) {
                  setState(() {
                    maxBackupVersions = v!;
                  });
                },
              ),

              const SizedBox(height: 30),

              const Text(
                "备份保存位置",
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
              ),

              const SizedBox(height: 10),

              Row(
                children: [
                  Expanded(
                    child: Text(backupPath, overflow: TextOverflow.ellipsis),
                  ),

                  ElevatedButton(
                    onPressed: selectBackupPath,

                    child: const Text("选择目录"),
                  ),
                ],
              ),

              if (Platform.isAndroid)
                ListTile(
                  title: const Text('所有文件访问权限'),
                  subtitle: const Text('用于备份整个 Download 等共享存储目录'),
                  trailing: TextButton(
                    onPressed: () async {
                      final granted =
                          await AndroidFolderService.hasAllFilesAccess();

                      if (!granted) {
                        await AndroidFolderService.requestAllFilesAccess();
                      }

                      if (!context.mounted) return;

                      ScaffoldMessenger.of(context).showSnackBar(
                        SnackBar(
                          content: Text(
                            granted ? '已经拥有所有文件访问权限' : '请在系统设置中开启“允许管理所有文件”',
                          ),
                        ),
                      );
                    },
                    child: const Text('授权'),
                  ),
                ),

              const SizedBox(height: 30),

              SwitchListTile(
                title: const Text("压缩备份"),

                subtitle: const Text("关闭后直接复制文件"),

                value: enableCompression,

                onChanged: (v) {
                  setState(() {
                    enableCompression = v;
                  });
                },
              ),

              const SizedBox(height: 30),

              TextField(
                controller: passwordController,

                obscureText: true,

                decoration: const InputDecoration(
                  labelText: "压缩密码",
                  helperText: "设置密码后会隐藏压缩包内的文件名",

                  border: OutlineInputBorder(),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
