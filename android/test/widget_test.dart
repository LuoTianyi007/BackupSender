import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:backup_sender/main.dart';
import 'package:backup_sender/models/backup_config.dart';
import 'package:backup_sender/services/config_service.dart';
import 'package:backup_sender/services/backup_history_service.dart';
import 'package:backup_sender/pages/backup_history_page.dart';
import 'package:backup_sender/pages/settings_page.dart';

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));
  test('旧配置默认全选，开关和文件名可持久保存', () async {
    final old = BackupConfig.fromJson({
      'folders': ['a', 'b'],
    });
    expect(old.enabledFolders, ['a', 'b']);
    await ConfigService.saveConfig(
      old.copyWith(disabledFolders: ['b'], archiveName: '照片'),
    );
    final loaded = await ConfigService.loadConfig();
    expect(loaded.enabledFolders, ['a']);
    expect(loaded.archiveName, '照片');
    expect(loaded.copyWith(folders: ['a']).disabledFolders, isEmpty);
    expect(
      loaded.copyWith(disabledFolders: ['a', 'b']).enabledFolders,
      isEmpty,
    );
  });
  test('文件名保留时间排序并清理非法字符和扩展名', () {
    final config = BackupConfig.defaultConfig().copyWith(
      archiveName: '照片/文档.zip',
    );
    expect(
      config.createBackupName(DateTime(2026, 9, 9, 12, 30, 1, 2)),
      'backup_20260909_123001_002_照片_文档',
    );
    expect(BackupConfig.cleanArchiveName('  a:b?.ZIP  '), 'a_b_');
  });
  test('成功和失败记录持久化，最新在前，限制200条', () async {
    for (var i = 0; i < 201; i++) {
      await BackupHistoryService.add({
        'time': i,
        'success': i.isEven,
        'error': i.isEven ? '' : '读取失败',
      });
    }
    final records = await BackupHistoryService.load();
    expect(records.length, 200);
    expect(records.first['time'], 200);
    expect(records[1]['success'], false);
    expect(records[1]['error'], '读取失败');
  });
  testWidgets('关闭全部文件夹后不会开始备份', (tester) async {
    await ConfigService.saveConfig(
      BackupConfig.defaultConfig().copyWith(folders: ['example']),
    );
    await tester.pumpWidget(const BackupSenderApp());
    await tester.pumpAndSettle();
    await tester.tap(find.byType(Switch));
    await tester.pumpAndSettle();
    expect((await ConfigService.loadConfig()).enabledFolders, isEmpty);
    await tester.tap(find.text('开始备份'));
    await tester.pumpAndSettle();
    expect(find.text('请至少开启一个文件夹的备份开关'), findsOneWidget);
    expect(await BackupHistoryService.load(), isEmpty);
  });

  testWidgets('备份记录正确显示大小单位并兼容旧记录', (tester) async {
    await BackupHistoryService.add({'time': 1, 'success': true});
    await BackupHistoryService.add({'time': 2, 'success': true, 'size': 1024});
    await BackupHistoryService.add({
      'time': 3,
      'success': false,
      'size': 1048576,
    });
    await tester.pumpWidget(const MaterialApp(home: BackupHistoryPage()));
    await tester.pumpAndSettle();
    expect(find.textContaining('大小：1.0 KB'), findsOneWidget);
    expect(find.textContaining('大小：1.0 MB'), findsOneWidget);
    expect(find.textContaining('大小：未知'), findsOneWidget);
  });

  testWidgets('设置快速连续修改并立即退出仍保存最后输入', (tester) async {
    BackupConfig? current;
    await tester.pumpWidget(
      MaterialApp(
        home: SettingsPage(
          config: BackupConfig.defaultConfig(),
          onChanged: (config) => current = config,
        ),
      ),
    );
    await tester.enterText(find.byType(TextField).first, '第一版');
    await tester.enterText(find.byType(TextField).first, '最后一版');
    expect(current!.archiveName, '最后一版');
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
    expect((await ConfigService.loadConfig()).archiveName, '最后一版');
  });
}
