import 'dart:io';
import 'dart:isolate';

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
      activeProcess?.kill();
    }
  });
  sendPort.send({'controlPort': controlPort.sendPort});

  void checkCancelled() {
    if (cancelled) throw const BackupCancelled();
  }

  Future<void> runSevenZip(String executable, List<String> arguments) async {
    checkCancelled();
    // Start directly so kill() terminates 7-Zip itself instead of a shell.
    final process = await Process.start(executable, arguments);
    activeProcess = process;
    final stdoutDone = process.stdout.drain<void>();
    final stderrDone = process.stderr.drain<void>();
    if (cancelled) process.kill();
    final exitCode = await process.exitCode;
    await Future.wait([stdoutDone, stderrDone]);
    activeProcess = null;
    checkCancelled();
    if (exitCode != 0) throw Exception('7-Zip 操作失败（错误码 $exitCode）');
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
    var size = 0;
    var copiedFiles = 0;
    sendPort.send({'progress': 0.05, 'status': '正在复制文件...'});
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
        onFile: (file) {
          copiedFiles++;
          sendPort.send({
            'progress': 0.1,
            'status': '已复制 $copiedFiles 个文件',
            'currentFile': file,
            'processedFiles': copiedFiles,
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
      sendPort.send({'progress': 0.4, 'status': '正在压缩...'});
      await runSevenZip(sevenZip, [
        'a',
        '-t7z',
        archive.path,
        '${staged.path}\\*',
        '-mx=9',
        ...passwordArgs,
        if (password.isNotEmpty) '-mhe=on',
      ]);
      sendPort.send({'progress': 0.8, 'status': '正在校验备份...'});
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

Future<int> copyDirectory(
  Directory source,
  Directory target, {
  required void Function() checkCancelled,
  void Function(String)? onFile,
}) async {
  checkCancelled();
  await target.create(recursive: true);
  var size = 0;
  await for (final entity in source.list(followLinks: false)) {
    checkCancelled();
    final name = entity.uri.pathSegments.where((part) => part.isNotEmpty).last;
    if (entity is Directory) {
      size += await copyDirectory(
        entity,
        Directory('${target.path}\\$name'),
        checkCancelled: checkCancelled,
        onFile: onFile,
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
    } else if (entity is Link) {
      throw Exception('源文件夹包含不支持的符号链接：${entity.path}');
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
