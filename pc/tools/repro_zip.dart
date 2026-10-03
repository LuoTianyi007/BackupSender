import 'dart:async';
import 'dart:io';
import 'dart:isolate';
import 'dart:math';

import 'package:backup_sender/services/zip_worker.dart';

Future<void> main() async {
  final root = await Directory.systemTemp.createTemp('backup_repro_cn_');
  late final Directory source;
  if (Platform.environment['REPRO_ACTUAL'] == '1') {
    source = Directory(r'C:\Users\15176\Desktop\android');
  } else {
    source = Directory('${root.path}\\源数据\\中文目录')..createSync(recursive: true);
    final random = Random(42);
    for (var i = 0; i < 12; i++) {
      final file = File('${source.path}\\文件_${i}_中文.txt');
      final sink = file.openWrite();
      final block = List<int>.generate(1024 * 1024, (_) => random.nextInt(256));
      for (var blockIndex = 0; blockIndex < 40; blockIndex++) {
        sink.add(block);
      }
      await sink.close();
    }
  }
  final output = Directory('${root.path}\\输出')..createSync(recursive: true);
  final receive = ReceivePort();
  final errors = ReceivePort();
  final exits = ReceivePort();
  final isolate = await Isolate.spawn<Map<String, dynamic>>(
    zipWorker,
    <String, dynamic>{
      'sendPort': receive.sendPort,
      'folders': <String>[source.path],
      'backupPath': output.path,
      'backupName': '测试备份',
      'enableCompression': true,
      'password': '',
      'maxBackupVersions': 0,
    },
    onError: errors.sendPort,
    onExit: exits.sendPort,
    errorsAreFatal: true,
  );
  final started = DateTime.now();
  var last = DateTime.now();
  final done = Completer<void>();
  receive.listen((message) {
    if (message is Map && message['controlPort'] is SendPort) {
      stdout.writeln('control port ready');
    } else if (message is Map) {
      final now = DateTime.now();
      if (now.difference(last).inMilliseconds >= 250 ||
          message['error'] != null) {
        last = now;
        stdout.writeln('${now.difference(started).inMilliseconds}ms $message');
      }
      if (message['path'] != null || message['error'] != null) done.complete();
    }
  });
  errors.listen((message) {
    stderr.writeln('ISOLATE ERROR: $message');
    if (!done.isCompleted) done.completeError(message);
  });
  exits.listen((_) {
    stdout.writeln(
      'ISOLATE EXIT at ${DateTime.now().difference(started).inMilliseconds}ms',
    );
  });
  try {
    await done.future.timeout(const Duration(minutes: 5));
  } finally {
    isolate.kill(priority: Isolate.immediate);
    receive.close();
    errors.close();
    exits.close();
    stdout.writeln('fixture: ${root.path}');
    stdout.writeln(
      'archive files: ${output.listSync(recursive: true).map((e) => '${e.path} ${e is File ? e.lengthSync() : 0}').join('; ')}',
    );
  }
}
