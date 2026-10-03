import 'package:backup_sender/main.dart';
import 'package:backup_sender/models/backup_config.dart';
import 'package:backup_sender/pages/settings_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));
  testWidgets('Loads the actual backup interface', (tester) async {
    await tester.pumpWidget(const BackupSenderApp());
    await tester.pumpAndSettle();
    expect(find.text('Backup Sender'), findsOneWidget);
    expect(find.text('还没有添加文件夹'), findsOneWidget);
    expect(find.text('开始备份'), findsOneWidget);
    expect(find.byIcon(Icons.settings), findsOneWidget);
  });
  testWidgets('Tray preference defaults on and is disabled without autostart', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(home: SettingsPage(config: BackupConfig.defaultConfig())),
    );
    await tester.pumpAndSettle();
    final tray = tester
        .widgetList<SwitchListTile>(find.byType(SwitchListTile))
        .singleWhere((tile) => (tile.title as Text).data == '开机启动后隐藏到托盘');
    expect(tray.value, isTrue);
    expect(tray.onChanged, isNull);
  });
}
