import 'dart:async';
import 'dart:io';
import 'dart:isolate';
import 'dart:math';

import 'package:backup_sender/services/zip_worker.dart';
import 'package:flutter_test/flutter_test.dart';

Future<Map<dynamic, dynamic>> runBackup(
  Directory source,
  Directory destination, {
  bool compressed = true,
  String password = '',
  Future<void> Function(Map<dynamic, dynamic>, SendPort?)? onMessage,
}) async {
  final port = ReceivePort();
  final isolate = await Isolate.spawn(zipWorker, {
    'sendPort': port.sendPort,
    'folders': [source.path],
    'backupPath': destination.path,
    'backupName': 'backup_test',
    'enableCompression': compressed,
    'password': password,
  });
  SendPort? control;
  try {
    await for (final message in port.timeout(const Duration(seconds: 30))) {
      final data = message as Map;
      if (data['controlPort'] is SendPort) control = data['controlPort'];
      await onMessage?.call(data, control);
      if (data.containsKey('path') || data.containsKey('error')) return data;
    }
    throw StateError('Worker exited without a result');
  } finally {
    port.close();
    isolate.kill(priority: Isolate.immediate);
  }
}

void main() {
  group('Windows 真实文件备份', () {
    late Directory root;
    late Directory source;
    late Directory destination;
    setUp(() async {
      root = await Directory.systemTemp.createTemp('backup_sender_test_');
      source = await Directory('${root.path}\\source').create();
      destination = await Directory('${root.path}\\output').create();
      await File('${source.path}\\private-name.txt')
          .writeAsString('backup payload');
      await Directory('${source.path}\\empty').create();
    });
    tearDown(() async => root.delete(recursive: true));

    test('普通备份保留内容和空目录，并返回实际字节数', () async {
      final result = await runBackup(source, destination, compressed: false);
      expect(result['error'], isNull);
      expect(result['size'], 14);
      expect(
        await File('${result['path']}\\1_source\\private-name.txt')
            .readAsString(),
        'backup payload',
      );
      expect(
        await Directory('${result['path']}\\1_source\\empty').exists(),
        isTrue,
      );
      expect(await destination.list().length, 1);
    });

    test('加密压缩成功，无密码无法列出子文件名，有密码可解压', () async {
      final result = await runBackup(
        source,
        destination,
        password: 'test-password',
      );
      expect(result['error'], isNull);
      expect(result['size'], await File(result['path']).length());
      final executable = '${Directory.current.path}\\tools\\7za.exe';
      final blocked = await Process.run(executable, [
        'l',
        result['path'],
        '-pwrong-password',
      ]);
      expect(blocked.exitCode, isNot(0));
      expect(blocked.stdout.toString(), isNot(contains('private-name.txt')));
      final restore = '${root.path}\\restored';
      final restored = await Process.run(executable, [
        'x',
        result['path'],
        '-ptest-password',
        '-o$restore',
      ]);
      expect(restored.exitCode, 0);
      expect(
        await File('$restore\\1_source\\private-name.txt').readAsString(),
        'backup payload',
      );
      expect(await destination.list().length, 1);
    });

    test('取消复制会清理本次临时文件，保留已有备份', () async {
      await File('${destination.path}\\keep.txt').writeAsString('keep');
      final result = await runBackup(
        source,
        destination,
        compressed: false,
        onMessage: (data, control) async {
          if (data['controlPort'] != null) control!.send('cancel');
        },
      );
      expect(result['error'], '备份已取消');
      expect(
        await destination.list().map((e) => e.uri.pathSegments.last).toList(),
        ['keep.txt'],
      );
    });

    test('压缩过程中中止，不留下压缩进程占用或半成品', () async {
      final random = Random(1);
      final data = List<int>.generate(1024 * 1024, (_) => random.nextInt(256));
      final file = await File('${source.path}\\large.bin')
          .open(mode: FileMode.write);
      for (var i = 0; i < 24; i++) {
        await file.writeFrom(data);
      }
      await file.close();
      final result = await runBackup(
        source,
        destination,
        onMessage: (data, control) async {
          if (data['status'] == '正在压缩...') {
            await Future<void>.delayed(const Duration(milliseconds: 200));
            control!.send('cancel');
          }
        },
      );
      expect(result['error'], '备份已取消');
      expect(await destination.list().toList(), isEmpty);
    });

    test('源文件夹不存在时返回失败', () async {
      final result = await runBackup(
        Directory('${root.path}\\missing'),
        destination,
      );
      expect(result['error'], contains('源文件夹不存在'));
      expect(await destination.list().toList(), isEmpty);
    });
  }, skip: !Platform.isWindows);
}
