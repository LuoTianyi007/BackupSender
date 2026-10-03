import 'dart:io';

class StartupService {
  static const String _appName = 'Backup Sender';

  static const String _runKey =
      r'HKCU\Software\Microsoft\Windows\CurrentVersion\Run';

  static Future<void> enableStartup() async {
    // Android、macOS、Linux 不执行
    if (!Platform.isWindows) {
      return;
    }

    final exePath = Platform.resolvedExecutable;

    // 路径可能包含空格，所以注册表内容加双引号
    final command = '"$exePath"';

    final result = await Process.run(
      'reg.exe',
      [
        'ADD',
        _runKey,
        '/v',
        _appName,
        '/t',
        'REG_SZ',
        '/d',
        command,
        '/f',
      ],
    );

    if (result.exitCode != 0) {
      throw Exception(
        '设置 Windows 开机启动失败：${result.stderr}',
      );
    }
  }

  static Future<void> disableStartup() async {
    // Android、macOS、Linux 不执行
    if (!Platform.isWindows) {
      return;
    }

    final result = await Process.run(
      'reg.exe',
      [
        'DELETE',
        _runKey,
        '/v',
        _appName,
        '/f',
      ],
    );

    // reg.exe 删除不存在的值时也会返回非 0，
    // 对关闭开机启动来说可以忽略。
    if (result.exitCode != 0) {
      print(
        'Windows 开机启动项可能原本就不存在：${result.stderr}',
      );
    }
  }
}