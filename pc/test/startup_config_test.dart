import 'package:backup_sender/models/backup_config.dart';
import 'package:backup_sender/services/startup_service.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('Migrates tray preference and preserves it through serialization', () {
    final config = BackupConfig.fromJson({});
    expect(config.startupToTray, isTrue);
    expect(config.enableStartup, isFalse);
    final updated = config.copyWith(startupToTray: false);
    expect(BackupConfig.fromJson(updated.toJson()).startupToTray, isFalse);
    expect(updated.copyWith(archiveName: 'photos').startupToTray, isFalse);
  });
  test('Quotes executable paths and distinguishes visible startup', () {
    const path = r'C:\Program Files\Backup Sender\backup_sender.exe';
    expect(
      StartupService.startupCommand(path),
      '"$path" --autostart --start-in-tray',
    );
    expect(
      StartupService.startupCommand(path, toTray: false),
      '"$path" --autostart',
    );
  });
}
