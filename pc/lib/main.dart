import 'dart:io';
import 'dart:isolate';
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:file_picker/file_picker.dart';

import 'models/backup_config.dart';
import 'pages/settings_page.dart';
import 'pages/backup_history_page.dart';
import 'services/config_service.dart';
import 'services/scheduler_service.dart';
import 'services/startup_service.dart';
import 'services/zip_worker.dart';
import 'services/android_folder_service.dart';
import 'services/backup_history_service.dart';

final messengerKey = GlobalKey<ScaffoldMessengerState>();
void main() => runApp(const BackupSenderApp());

class BackupSenderApp extends StatefulWidget {
  const BackupSenderApp({super.key});
  @override
  State<BackupSenderApp> createState() => _BackupSenderAppState();
}

class _BackupSenderAppState extends State<BackupSenderApp> {
  BackupConfig? config;
  final _scheduler = SchedulerService();
  static const _windowChannel = MethodChannel('backup_sender/window');
  Completer<void>? _backupCompletion;
  bool _exiting = false;
  bool isBackingUp = false;
  bool saving = false;
  double progress = 0;
  String status = '';
  String currentFile = '';
  int processed = 0;
  int total = 0;
  Timer? _compressionTimer;
  String? _compressionFile;
  int _compressionTotalBytes = 0;
  Isolate? _backupIsolate;
  SendPort? _backupCancelPort;
  ReceivePort? _backupPort;
  bool _cancelRequested = false;

  @override
  void initState() {
    super.initState();
    if (Platform.isWindows) {
      _windowChannel.setMethodCallHandler((call) async {
        if (call.method == 'requestExit') {
          _exiting = true;
          _scheduler.stop();
          final completion = _backupCompletion;
          await cancelBackup();
          await completion?.future;
          await _windowChannel.invokeMethod<void>('exit');
        }
      });
    }
    if (Platform.isAndroid) {
      AndroidFolderService.setBackupProgressHandler((data) {
        if (!mounted || !isBackingUp) return;
        setState(() {
          progress = ((data['progress'] as num?) ?? 0) / 100;
          status = data['status'] ?? '';
          currentFile = data['currentFile'] ?? '';
          processed = data['processedFiles'] ?? 0;
          total = data['totalFiles'] ?? 0;
        });
      });
    }
    load();
  }

  void message(String text) =>
      messengerKey.currentState?.showSnackBar(SnackBar(content: Text(text)));

  Future<void> load() async {
    final data = await ConfigService.loadConfig();
    if (!mounted) return;
    setState(() => config = data);
    _configureSchedule(data);
    if (Platform.isWindows && data.enableStartup) {
      try {
        // Migrate existing startup entries and refresh paths after moving the app.
        await StartupService.enableStartup(toTray: data.startupToTray);
      } catch (error) {
        message('更新开机启动设置失败：$error');
      }
    }
    if (data.autoBackup &&
        data.schedule == 'startup' &&
        data.enabledFolders.isNotEmpty) {
      await Future<void>.delayed(const Duration(seconds: 1));
      if (mounted) await startBackup(source: '启动备份');
    }
  }

  void _configureSchedule(BackupConfig data) {
    if (!_exiting &&
        Platform.isWindows &&
        data.autoBackup &&
        data.schedule == 'daily' &&
        data.enabledFolders.isNotEmpty) {
      _scheduler.startDailyTask(
        hour: data.backupHour,
        minute: data.backupMinute,
        callback: () => unawaited(startBackup(source: '定时备份')),
      );
    } else {
      _scheduler.stop();
    }
  }

  @override
  void dispose() {
    _scheduler.stop();
    _compressionTimer?.cancel();
    _backupCancelPort?.send('cancel');
    _backupPort?.close();
    if (Platform.isWindows) _windowChannel.setMethodCallHandler(null);
    if (Platform.isAndroid) {
      AndroidFolderService.setBackupProgressHandler((_) {});
    }
    super.dispose();
  }

  Future<void> saveFolders(BackupConfig updated) async {
    setState(() => saving = true);
    try {
      await ConfigService.saveConfig(updated);
      if (mounted) {
        setState(() => config = updated);
      }
    } catch (e) {
      message('保存失败：$e');
    } finally {
      if (mounted) {
        setState(() => saving = false);
        if (config != null) _configureSchedule(config!);
      }
    }
  }

  Future<void> toggleFolder(String folder, bool enabled) async {
    final disabled = config!.disabledFolders.toSet();
    enabled ? disabled.remove(folder) : disabled.add(folder);
    await saveFolders(config!.copyWith(disabledFolders: disabled.toList()));
  }

  Future<void> addFolder({bool download = false}) async {
    try {
      String? path;
      if (Platform.isAndroid) {
        if (download) {
          if (!await AndroidFolderService.hasAllFilesAccess()) {
            await AndroidFolderService.requestAllFilesAccess();
            message('请开启“允许管理所有文件”，然后再次点击“添加 Download”');
            return;
          }
          path = await AndroidFolderService.getDownloadPath();
        } else {
          path = await AndroidFolderService.pickFolder();
        }
      } else {
        path = await FilePicker.getDirectoryPath();
      }
      if (!mounted || path == null || path.isEmpty) return;
      if (config!.folders.contains(path)) {
        message('该文件夹已在列表中');
        return;
      }
      await saveFolders(config!.copyWith(folders: [...config!.folders, path]));
    } catch (e) {
      message('添加失败：$e');
    }
  }

  Future<void> startBackup({String source = '手动备份'}) async {
    if (_exiting || isBackingUp || saving || config == null) return;
    final current = config!;
    final selected = current.enabledFolders;
    if (selected.isEmpty) {
      message('请至少开启一个文件夹的备份开关');
      return;
    }
    final started = DateTime.now();
    _backupCompletion = Completer<void>();
    final name = current.createBackupName(started);
    String? error;
    bool cancelled = false;
    int backupSize = 0;
    var recordName =
        '$name${current.enableCompression ? (Platform.isWindows ? ".7z" : ".zip") : ""}';
    _cancelRequested = false;
    setState(() {
      isBackingUp = true;
      status = '正在准备备份...';
      progress = 0;
      currentFile = '';
      processed = total = 0;
    });
    try {
      if (current.backupPath.isEmpty ||
          (Platform.isAndroid &&
              !current.backupPath.startsWith('content://'))) {
        throw Exception('请先在设置中选择备份保存位置');
      }
      if (selected.contains(current.backupPath)) {
        throw Exception('源文件夹不能与备份目录相同');
      }
      if (Platform.isAndroid) {
        Map<String, dynamic> result;
        if (current.enableCompression) {
          result = await AndroidFolderService.compressFoldersDirectly(
            folders: selected,
            destinationUri: current.backupPath,
            backupName: name,
            maxBackupVersions: current.maxBackupVersions,
            password: current.zipPassword,
          );
        } else {
          for (var i = 0; i < selected.length; i++) {
            await AndroidFolderService.copyFolderToTemp(
              uri: selected[i],
              index: i + 1,
            );
          }
          result = await AndroidFolderService.exportTempBackup(
            destinationUri: current.backupPath,
            backupName: name,
            maxBackupVersions: current.maxBackupVersions,
          );
        }
        if (result.isEmpty) throw Exception('未收到备份完成结果');
        backupSize = (result['size'] as num?)?.toInt() ?? 0;
        if (result['name'] is String) {
          recordName = result['name'] as String;
        }
        if (_cancelRequested) throw Exception('备份已取消');
      } else {
        final port = ReceivePort();
        _backupPort = port;
        try {
          _backupIsolate = await Isolate.spawn(zipWorker, {
            'sendPort': port.sendPort,
            'folders': selected,
            'backupPath': current.backupPath,
            'backupName': name,
            'enableCompression': current.enableCompression,
            'password': current.zipPassword,
            'maxBackupVersions': current.maxBackupVersions,
          });
          await for (final message in port) {
            if (message['controlPort'] is SendPort) {
              _backupCancelPort = message['controlPort'] as SendPort;
              if (_cancelRequested) _backupCancelPort!.send('cancel');
              continue;
            }
            if (message['error'] != null) throw Exception(message['error']);
            if (mounted) {
              setState(() {
                progress = ((message['progress'] as num?) ?? 0).toDouble();
                status = message['status'] ?? status;
                currentFile = message['currentFile'] ?? currentFile;
                processed =
                    (message['processedFiles'] as num?)?.toInt() ?? processed;
                total = (message['totalFiles'] as num?)?.toInt() ?? total;
              });
            }
            if (message['progressFile'] is String) {
              _compressionFile = message['progressFile'] as String;
              _compressionTotalBytes =
                  (message['totalBytes'] as num?)?.toInt() ?? 0;
              _compressionTimer?.cancel();
              _compressionTimer = Timer.periodic(
                const Duration(milliseconds: 400),
                (_) async {
                  final path = _compressionFile;
                  if (path == null || !mounted) return;
                  try {
                    final bytes = await File(path).length();
                    final totalBytes = _compressionTotalBytes;
                    final ratio = totalBytes > 0
                        ? (bytes / totalBytes).clamp(0.0, 0.99)
                        : 0.0;
                    setState(() {
                      progress = ratio > 0 ? 0.70 + 0.20 * ratio : -1;
                      status =
                          '正在压缩（已生成 ${(bytes / 1048576).toStringAsFixed(1)} MB）';
                    });
                  } catch (_) {}
                },
              );
            }
            if (message['path'] != null) {
              backupSize = (message['size'] as num?)?.toInt() ?? 0;
              break;
            }
          }
          if (_cancelRequested) throw Exception('备份已取消');
        } finally {
          port.close();
          _compressionTimer?.cancel();
          _compressionTimer = null;
          _compressionFile = null;
          _backupPort = null;
          _backupCancelPort = null;
          _backupIsolate?.kill(priority: Isolate.immediate);
          _backupIsolate = null;
        }
      }
    } catch (e) {
      error = e.toString();
      cancelled = _cancelRequested || error.toLowerCase().contains('cancel');
      if (cancelled) error = '备份已取消';
    }
    try {
      await BackupHistoryService.add({
        'time': started.millisecondsSinceEpoch,
        'finishedAt': DateTime.now().millisecondsSinceEpoch,
        'success': error == null,
        'name': recordName,
        'size': backupSize,
        'folders': selected,
        'error': error ?? '',
        'source': source,
      });
    } catch (e) {
      message('备份记录保存失败：$e');
    }
    _backupCompletion?.complete();
    _backupCompletion = null;
    if (!mounted) return;
    setState(() {
      isBackingUp = false;
      status = error == null ? '备份完成' : (cancelled ? '备份已取消' : '备份失败：$error');
      if (error == null) progress = 1;
    });
    message(status);
  }

  Future<void> cancelBackup() async {
    if (!isBackingUp || _cancelRequested) return;
    _cancelRequested = true;
    setState(() => status = '正在取消备份...');
    if (Platform.isAndroid) {
      await AndroidFolderService.cancelBackup();
    } else {
      _backupCancelPort?.send('cancel');
      // Keep the worker alive until it stops 7-Zip and removes staging files.
      // If its control port has not arrived, startBackup forwards this request.
    }
  }

  Future<void> openBackupFolder() async {
    try {
      final path = config!.backupPath;
      if (path.isEmpty) {
        message('请先选择备份保存位置');
        return;
      }
      if (Platform.isAndroid) {
        await AndroidFolderService.openFolder(path);
      } else if (Platform.isWindows) {
        await Directory(path).create(recursive: true);
        await Process.run('explorer.exe', [path]);
      }
    } catch (e) {
      message('打开备份目录失败：$e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final busy = isBackingUp || saving || config == null;
    return MaterialApp(
      scaffoldMessengerKey: messengerKey,
      debugShowCheckedModeBanner: false,
      title: 'Backup Sender',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
        useMaterial3: true,
      ),
      home: Builder(
        builder: (context) => Scaffold(
          appBar: AppBar(
            title: const Text('Backup Sender'),
            actions: [
              IconButton(
                tooltip: '备份记录',
                icon: const Icon(Icons.history),
                onPressed: () => Navigator.push(
                  context,
                  MaterialPageRoute(builder: (_) => const BackupHistoryPage()),
                ),
              ),
              IconButton(
                tooltip: '设置',
                icon: const Icon(Icons.settings),
                onPressed: busy
                    ? null
                    : () async {
                        final result = await Navigator.push<BackupConfig>(
                          context,
                          MaterialPageRoute(
                            builder: (_) => SettingsPage(
                              config: config!,
                              onChanged: (updated) {
                                if (mounted) {
                                  setState(() => config = updated);
                                  _configureSchedule(updated);
                                }
                              },
                            ),
                          ),
                        );
                        if (mounted && result != null) {
                          setState(() => config = result);
                        }
                      },
              ),
            ],
          ),
          body: config == null
              ? const Center(child: CircularProgressIndicator())
              : ListView(
                  padding: const EdgeInsets.all(20),
                  children: [
                    Text(
                      '备份文件夹（已开启 ${config!.enabledFolders.length}/${config!.folders.length}）',
                      style: const TextStyle(
                        fontSize: 22,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 8),
                    const Text('开关决定本次及自动备份包含的文件夹，关闭后仍保留在列表中。'),
                    const SizedBox(height: 16),
                    Wrap(
                      spacing: 10,
                      runSpacing: 10,
                      children: [
                        ElevatedButton.icon(
                          onPressed: busy ? null : () => addFolder(),
                          icon: const Icon(Icons.folder_open),
                          label: const Text('添加文件夹'),
                        ),
                        if (Platform.isAndroid)
                          ElevatedButton.icon(
                            onPressed: busy
                                ? null
                                : () => addFolder(download: true),
                            icon: const Icon(Icons.download),
                            label: const Text('添加 Download'),
                          ),
                        FilledButton.icon(
                          onPressed: isBackingUp
                              ? cancelBackup
                              : (busy ? null : () => startBackup()),
                          icon: Icon(isBackingUp ? Icons.stop : Icons.archive),
                          label: Text(isBackingUp ? '备份中...' : '开始备份'),
                        ),
                        OutlinedButton.icon(
                          onPressed: config == null ? null : openBackupFolder,
                          icon: const Icon(Icons.folder_open),
                          label: const Text('打开备份目录'),
                        ),
                      ],
                    ),
                    if (status.isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 16),
                        child: Text(status),
                      ),
                    if (isBackingUp) ...[
                      LinearProgressIndicator(
                        value: progress < 0 ? null : progress.clamp(0, 1),
                      ),
                      Text(
                        progress < 0
                            ? '正在压缩，请稍候...  已处理 $processed / $total'
                            : '${(progress * 100).toStringAsFixed(1)}%  已处理 $processed / $total',
                      ),
                      if (currentFile.isNotEmpty)
                        Text(
                          currentFile,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                    ],
                    const SizedBox(height: 16),
                    if (config!.folders.isEmpty) const Text('还没有添加文件夹'),
                    for (final folder in config!.folders)
                      Card(
                        child: ListTile(
                          leading: Switch(
                            value: !config!.disabledFolders.contains(folder),
                            onChanged: busy
                                ? null
                                : (value) => toggleFolder(folder, value),
                          ),
                          title: Text(Uri.decodeFull(folder)),
                          subtitle: Text(
                            config!.disabledFolders.contains(folder)
                                ? '不参与备份'
                                : '参与备份',
                          ),
                          trailing: IconButton(
                            tooltip: '移除文件夹',
                            icon: const Icon(Icons.delete_outline),
                            onPressed: busy
                                ? null
                                : () => saveFolders(
                                    config!.copyWith(
                                      folders: config!.folders
                                          .where((f) => f != folder)
                                          .toList(),
                                    ),
                                  ),
                          ),
                        ),
                      ),
                  ],
                ),
        ),
      ),
    );
  }
}
