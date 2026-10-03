import 'dart:io';
import 'dart:isolate';

import 'package:backup_sender/services/zip_worker.dart';
import 'package:flutter_test/flutter_test.dart';

Future<Map<dynamic, dynamic>> runWorker(
  Map<String, dynamic> args, {
  bool cancel = false,
  bool cancelAtCompression = false,
}) async {
  final port = ReceivePort();
  SendPort? control;
  final worker = await Isolate.spawn(zipWorker, {
    ...args,
    'sendPort': port.sendPort,
  });
  try {
    await for (final data in port.timeout(const Duration(seconds: 30))) {
      final message = data as Map;
      if (message['controlPort'] is SendPort) {
        control = message['controlPort'] as SendPort;
        if (cancel) control.send('cancel');
      }
      if (cancelAtCompression && message['status'] == '正在压缩...') {
        control!.send('cancel');
      }
      if (message.containsKey('path') || message.containsKey('error')) {
        return message;
      }
    }
    throw StateError('No worker result');
  } finally {
    port.close();
    worker.kill(priority: Isolate.immediate);
  }
}

void main() {
  late Directory temp, source, destination;
  setUp(() async {
    await Directory('build').create(recursive: true);
    temp = await Directory('build').createTemp('backup_test_');
    source = await Directory('${temp.path}/source').create();
    destination = await Directory('${temp.path}/destination').create();
    await File('${source.path}/example.txt').writeAsString('测试备份内容');
  });
  tearDown(() async => temp.delete(recursive: true));
  Map<String, dynamic> args(bool compressed) => {
    'folders': [source.absolute.path],
    'backupPath': destination.absolute.path,
    'backupName': 'backup_test',
    'enableCompression': compressed,
    'password': 'test-password',
    'maxBackupVersions': 5,
  };
  test('Copies complete file contents and removes staging files', () async {
    final result = await runWorker(args(false));
    expect(result['error'], isNull);
    final files = await Directory(result['path'] as String)
        .list(recursive: true)
        .where((item) => item is File)
        .toList();
    expect(files, hasLength(1));
    expect(await File(files.single.path).readAsString(), '测试备份内容');
    expect(await destination.list().length, 1);
  });
  test('Creates a valid password protected archive', () async {
    final result = await runWorker(args(true));
    expect(result['error'], isNull, reason: '${result['error']}');
    final archive = File(result['path'] as String);
    expect(await archive.length(), greaterThan(0));
    final check = await Process.run('tools/7za.exe', [
      't',
      archive.path,
      '-ptest-password',
    ]);
    expect(check.exitCode, 0);
    final wrong = await Process.run('tools/7za.exe', [
      't',
      archive.path,
      '-pwrong-password',
    ]);
    expect(wrong.exitCode, isNot(0));
    expect(await destination.list().length, 1);
  });
  test('Cancellation removes partial output and preserves source', () async {
    final result = await runWorker(args(true), cancel: true);
    expect(result['error'], '备份已取消');
    expect(await destination.list().length, 0);
    expect(await File('${source.path}/example.txt').exists(), isTrue);
  });
  test('Rejects destination inside source', () async {
    final result = await runWorker({
      ...args(false),
      'backupPath': '${source.absolute.path}/nested',
    });
    expect(result['error'], contains('不能位于源文件夹内'));
  });
  test(
    'Cancellation after staging removes the archive and staging tree',
    () async {
      final result = await runWorker(args(true), cancelAtCompression: true);
      expect(result['error'], '备份已取消');
      expect(await destination.list().length, 0);
      expect(await File('${source.path}/example.txt').readAsString(), '测试备份内容');
    },
  );
}
