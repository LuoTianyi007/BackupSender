import 'dart:io';
import 'dart:isolate';
import 'dart:convert';
import 'dart:async';

void zipWorker(Map<String, dynamic> args) async {
  final sendPort = args['sendPort'] as SendPort;
  final controlPort = ReceivePort();
  var cancelled = false;
  Process? activeProcess;
  Directory? workspace;
  Map<String, dynamic> result;
  final subscription = controlPort.listen((message) {
    if (message == 'cancel') {
      cancelled = true;
      final process = activeProcess;
      process?.kill();
    }
  });
  sendPort.send({'controlPort': controlPort.sendPort});

  void checkCancelled() {
    if (cancelled) throw const BackupCancelled();
  }

  Future<void> runSevenZip(
    String executable,
    List<String> arguments, {
    double? progressStart,
    double? progressEnd,
    String? progressStatus,
    int processedFiles = 0,
    int totalFiles = 0,
    String? progressFile,
    int totalBytes = 0,
  }) async {
    checkCancelled();
    // Start directly so kill() terminates 7-Zip itself instead of a shell.
    final process = await Process.start(executable, arguments);
    activeProcess = process;
    Timer? sizeTimer;
    if (progressFile != null) {
      final startedAt = DateTime.now();
      sizeTimer = Timer.periodic(const Duration(milliseconds: 500), (_) async {
        try {
          final bytes = await File(progressFile).length();
          final ratio = totalBytes > 0
              ? (bytes / totalBytes).clamp(0.0, 0.99)
              : 0.0;
          final elapsed = DateTime.now().difference(startedAt).inSeconds;
          final sizeMb = (bytes / (1024 * 1024)).toStringAsFixed(1);
          sendPort.send({
            'progress': ratio > 0
                ? (progressStart ?? 0.7) +
                      ((progressEnd ?? 0.93) - (progressStart ?? 0.7)) * ratio
                : -1.0,
            'status': '$progressStatus（已用时 ${elapsed}s，压缩包 $sizeMb MB）',
            'processedFiles': processedFiles,
            'totalFiles': totalFiles,
          });
        } catch (_) {}
      });
    }
    void reportProgress(String text) {
      if (progressStart == null || progressEnd == null) return;
      for (final match in RegExp(r'(\d{1,3})%').allMatches(text)) {
        final percent = int.tryParse(match.group(1)!) ?? 0;
        sendPort.send({
          'progress':
              progressStart + (progressEnd - progressStart) * percent / 100,
          'status': '$progressStatus $percent%',
          'processedFiles': processedFiles,
          'totalFiles': totalFiles,
        });
      }
    }

    final stdoutDone = process.stdout
        .transform(const Utf8Decoder(allowMalformed: true))
        .listen(reportProgress, onError: (_) {})
        .asFuture<void>();
    final stderrDone = process.stderr
        .transform(const Utf8Decoder(allowMalformed: true))
        .listen(reportProgress, onError: (_) {})
        .asFuture<void>();
    try {
      if (cancelled) process.kill();
      final exitCode = await process.exitCode;
      await Future.wait([stdoutDone, stderrDone]);
      checkCancelled();
      if (exitCode != 0) {
        throw Exception('7-Zip 执行失败（退出码 $exitCode），请检查文件权限和可用空间');
      }
    } finally {
      sizeTimer?.cancel();
      activeProcess = null;
    }
  }

  try {
    final folders = List<String>.from(args['folders']);
    if (folders.isEmpty) throw Exception('请至少开启一个文件夹的备份开关');
    final backupFolder = Directory(args['backupPath'] as String).absolute;
    final name =
        args['backupName'] as String? ??
        'backup_${DateTime.now().millisecondsSinceEpoch}';
    if (name.isEmpty || name.contains(RegExp(r'[\\/:]')) || name == '..') {
      throw Exception('备份名称无效');
    }
    final compressed = args['enableCompression'] as bool? ?? true;
    final password = args['password'] as String? ?? '';
    final finalPath = '${backupFolder.path}\\$name${compressed ? '.7z' : ''}';
    if (await FileSystemEntity.type(finalPath) !=
        FileSystemEntityType.notFound) {
      throw Exception('同名备份已存在');
    }
    for (final folder in folders) {
      final source = Directory(folder).absolute.path
          .replaceAll('/', '\\')
          .toLowerCase();
      final destination = backupFolder.path.replaceAll('/', '\\').toLowerCase();
      final prefix = source.endsWith('\\') ? source : '$source\\';
      if (destination == source || destination.startsWith(prefix)) {
        throw Exception('备份保存位置不能位于源文件夹内');
      }
      if (!await Directory(folder).exists()) throw Exception('源文件夹不存在：$folder');
    }
    await backupFolder.create(recursive: true);
    workspace = await backupFolder.createTemp('.backup_work_');
    final staged = await Directory('${workspace.path}\\files').create();
    final exclude = BackupExclude(workspace: workspace);
    var totalFiles = 0;
    var totalBytes = 0;
    sendPort.send({'progress': 0.01, 'status': '正在统计文件...'});
    for (final folder in folders) {
      totalFiles += await countFiles(
        Directory(folder),
        checkCancelled: checkCancelled,
        exclude: exclude,
      );
      totalBytes += await directorySize(
        Directory(folder),
        checkCancelled: checkCancelled,
        exclude: exclude,
      );
    }
    checkCancelled();
    if (totalFiles == 0) {
      throw Exception('没有可备份的文件');
    }
    var size = 0;
    var copiedFiles = 0;
    final copyEndProgress = compressed ? 0.7 : 0.95;
    sendPort.send({
      'progress': 0.05,
      'status': '发现 $totalFiles 个文件，开始复制...',
      'processedFiles': copiedFiles,
      'totalFiles': totalFiles,
    });
    for (var i = 0; i < folders.length; i++) {
      checkCancelled();
      final source = Directory(folders[i]);
      final folderName = source.uri.pathSegments
          .where((part) => part.isNotEmpty)
          .last;
      size += await copyDirectory(
        source,
        Directory('${staged.path}\\${i + 1}_$folderName'),
        checkCancelled: checkCancelled,
        exclude: exclude,
        onFile: (file) {
          copiedFiles++;
          final copyRatio = copiedFiles / totalFiles;
          sendPort.send({
            'progress': 0.05 + (copyEndProgress - 0.05) * copyRatio,
            'status': '已复制 $copiedFiles 个文件',
            'currentFile': file,
            'processedFiles': copiedFiles,
            'totalFiles': totalFiles,
          });
        },
        onSkip: (file) {
          sendPort.send({
            'progress': 0.05,
            'status': '已跳过不适合备份的目录或链接',
            'currentFile': file,
            'processedFiles': copiedFiles,
            'totalFiles': totalFiles,
          });
        },
      );
    }
    checkCancelled();
    if (compressed) {
      final bundled = File(
        '${File(Platform.resolvedExecutable).parent.path}\\tools\\7za.exe',
      );
      final development = File('${Directory.current.path}\\tools\\7za.exe');
      final sevenZip = await bundled.exists() ? bundled.path : development.path;
      if (!await File(sevenZip).exists()) throw Exception('找不到 tools\\7za.exe');
      final archive = File('${workspace.path}\\archive.7z');
      final passwordArgs = password.isEmpty ? <String>[] : ['-p$password'];
      sendPort.send({
        'progress': -1.0,
        'status': '正在压缩...',
        'progressFile': archive.path,
        'totalBytes': totalBytes,
        'processedFiles': copiedFiles,
        'totalFiles': totalFiles,
      });
      await runSevenZip(
        sevenZip,
        [
          'a',
          '-t7z',
          archive.path,
          '${staged.path}\\*',
          // Speed-first settings: avoid solid-block serialization for many
          // small files and use the fastest practical compression level.
          '-mx=1',
          '-ms=off',
          '-mmt=on',
          '-bsp1',
          ...passwordArgs,
          if (password.isNotEmpty) '-mhe=on',
        ],
        progressStart: 0.70,
        progressEnd: 0.90,
        progressStatus: '正在压缩',
        processedFiles: copiedFiles,
        totalFiles: totalFiles,
        progressFile: archive.path,
        totalBytes: totalBytes,
      );
      sendPort.send({
        'progress': 0.93,
        'status': '正在校验备份...',
        'processedFiles': copiedFiles,
        'totalFiles': totalFiles,
      });
      await runSevenZip(sevenZip, ['t', archive.path, ...passwordArgs]);
      size = await archive.length();
      checkCancelled();
      await archive.rename(finalPath);
    } else {
      checkCancelled();
      await staged.rename(finalPath);
    }
    await cleanOldBackups(backupFolder, args['maxBackupVersions'] as int? ?? 5);
    result = {
      'progress': 1.0,
      'status': '备份完成',
      'path': finalPath,
      'size': size,
      'processedFiles': copiedFiles,
      'totalFiles': totalFiles,
    };
  } catch (error) {
    result = {'error': cancelled ? '备份已取消' : error.toString()};
  } finally {
    final process = activeProcess;
    if (process != null) {
      process.kill();
      await process.exitCode;
    }
    // Only remove this task's staging directory.
    try {
      if (workspace != null && await workspace.exists()) {
        // The directory is created uniquely under backupFolder by this worker.
        await workspace.delete(recursive: true);
      }
    } catch (_) {
      // Cleanup failure must not prevent delivery of the task result.
    }
    await subscription.cancel();
    controlPort.close();
  }
  sendPort.send(result);
}

class BackupCancelled implements Exception {
  const BackupCancelled();
  @override
  String toString() => '备份已取消';
}

class BackupExclude {
  BackupExclude({required Directory workspace})
    : workspacePath = normalizePath(workspace.path);

  final String workspacePath;

  bool shouldSkip(FileSystemEntity entity) {
    final path = normalizePath(entity.path);
    return isSameOrInside(path, workspacePath);
  }
}

String normalizePath(String path) {
  return path
      .replaceAll('/', '\\')
      .replaceFirst(RegExp(r'\\+$'), '')
      .toLowerCase();
}

bool isSameOrInside(String path, String folder) {
  if (folder.isEmpty) return false;
  final prefix = folder.endsWith('\\') ? folder : '$folder\\';
  return path == folder || path.startsWith(prefix);
}

Future<int> countFiles(
  Directory source, {
  required void Function() checkCancelled,
  required BackupExclude exclude,
}) async {
  checkCancelled();
  var count = 0;
  await for (final entity in source.list(followLinks: false)) {
    checkCancelled();
    if (entity is Link || exclude.shouldSkip(entity)) {
      continue;
    }
    if (entity is Directory) {
      count += await countFiles(
        entity,
        checkCancelled: checkCancelled,
        exclude: exclude,
      );
    } else if (entity is File) {
      count++;
    }
  }
  return count;
}

Future<int> directorySize(
  Directory source, {
  required void Function() checkCancelled,
  required BackupExclude exclude,
}) async {
  var total = 0;
  await for (final entity in source.list(followLinks: false)) {
    checkCancelled();
    if (entity is Link || exclude.shouldSkip(entity)) continue;
    if (entity is File) {
      total += await entity.length();
    } else if (entity is Directory) {
      total += await directorySize(
        entity,
        checkCancelled: checkCancelled,
        exclude: exclude,
      );
    }
  }
  return total;
}

Future<int> copyDirectory(
  Directory source,
  Directory target, {
  required void Function() checkCancelled,
  required BackupExclude exclude,
  void Function(String)? onFile,
  void Function(String)? onSkip,
}) async {
  checkCancelled();
  await target.create(recursive: true);
  var size = 0;
  await for (final entity in source.list(followLinks: false)) {
    checkCancelled();
    final name = entity.uri.pathSegments.where((part) => part.isNotEmpty).last;
    if (entity is Link || exclude.shouldSkip(entity)) {
      onSkip?.call(entity.path);
      continue;
    }
    if (entity is Directory) {
      size += await copyDirectory(
        entity,
        Directory('${target.path}\\$name'),
        checkCancelled: checkCancelled,
        exclude: exclude,
        onFile: onFile,
        onSkip: onSkip,
      );
    } else if (entity is File) {
      final targetFile = File('${target.path}\\$name');
      final output = await targetFile.open(mode: FileMode.write);
      try {
        await for (final chunk in entity.openRead()) {
          checkCancelled();
          await output.writeFrom(chunk);
          size += chunk.length;
        }
      } finally {
        await output.close();
      }
      checkCancelled();
      onFile?.call(entity.path);
    }
  }
  return size;
}

Future<void> cleanOldBackups(Directory folder, int maxVersions) async {
  if (maxVersions < 1) return;
  final backups = await folder.list().where((entity) {
    final name = entity.uri.pathSegments.where((part) => part.isNotEmpty).last;
    return name.startsWith('backup_') &&
        (entity is Directory ||
            (entity is File &&
                (name.endsWith('.7z') || name.endsWith('.zip'))));
  }).toList();
  final dates = <String, DateTime>{};
  for (final backup in backups) {
    dates[backup.path] = (await backup.stat()).modified;
  }
  backups.sort((a, b) => dates[b.path]!.compareTo(dates[a.path]!));
  for (final backup in backups.skip(maxVersions)) {
    await backup.delete(recursive: true);
  }
}
