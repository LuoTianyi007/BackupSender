import 'package:flutter/services.dart';


class AndroidFolderService {


static void setBackupProgressHandler(
  void Function(
    Map<String, dynamic> data,
  ) onProgress,
) {

  _channel.setMethodCallHandler(
    (call) async {

      if (
          call.method ==
          'backupProgress'
      ) {

        final data =
            Map<String, dynamic>.from(
          call.arguments as Map,
        );

        onProgress(data);
      }
    },
  );
}

  static const MethodChannel _channel =
      MethodChannel('backup_sender/storage');

  static Future<String?> pickFolder() async {
    return await _channel.invokeMethod<String>(
      'pickFolder',
    );
  }

  static Future<String> getFolderName(
    String uri,
  ) async {
    final name =
        await _channel.invokeMethod<String>(
      'getFolderName',
      {
        'uri': uri,
      },
    );

    return name ?? uri;
  }

  static Future<List<Map<String, dynamic>>> listFiles(
    String uri,
  ) async {
    final result =
        await _channel.invokeMethod<List<dynamic>>(
      'listFiles',
      {
        'uri': uri,
      },
    );

    if (result == null) {
      return [];
    }

    return result
        .map(
          (item) => Map<String, dynamic>.from(
            item as Map,
          ),
        )
        .toList();
  }
  static Future<Map<String, dynamic>> copyFolderToTemp({
  required String uri,
  required int index,
}) async {
  final result =
      await _channel.invokeMethod<Map<dynamic, dynamic>>(
    'copyFolderToTemp',
    {
      'uri': uri,
      'index': index,
    },
  );

  if (result == null) {
    return {};
  }

  return Map<String, dynamic>.from(result);
}
static Future<Map<String, dynamic>> exportTempBackup({
  required String destinationUri,
  required String backupName,
  required int maxBackupVersions,
}) async {

  final result =
      await _channel.invokeMethod<Map<dynamic, dynamic>>(
    'exportTempBackup',
  {
  'destinationUri': destinationUri,
  'backupName': backupName,
  'maxBackupVersions': maxBackupVersions,
},
  );

  if (result == null) {
    return {};
  }

  return Map<String, dynamic>.from(result);
}




static Future<Map<String, dynamic>> compressTempBackup({
  required String destinationUri,
  required String backupName,
  required int maxBackupVersions,
  String password = '',
}) async {
  final result =
      await _channel.invokeMethod<Map<dynamic, dynamic>>(
    'compressTempBackup',
  {
  'destinationUri': destinationUri,
  'backupName': backupName,
  'maxBackupVersions': maxBackupVersions,
  'password': password,
},
);

  if (result == null) {
    return {};
  }

  return Map<String, dynamic>.from(result);
}

  static Future<Map<String, dynamic>> compressFoldersDirectly({
  required List<String> folders,
  required String destinationUri,
  required String backupName,
  required int maxBackupVersions,
  String password = '',
}) async {

  final result =
      await _channel.invokeMethod<
          Map<dynamic, dynamic>>(
    'compressFoldersDirectly',
    {
      'folders': folders,
      'destinationUri': destinationUri,
      'backupName': backupName,
      'maxBackupVersions':
          maxBackupVersions,
      'password': password,
    },
  );

  if (result == null) {
    return {};
  }

  return Map<String, dynamic>.from(
    result,
  );
}

static Future<void> scheduleDailyBackup({
  required int hour,
  required int minute,
  required String destinationUri,
  required List<String> folders,
  required bool enableCompression,
  required int maxBackupVersions,
    required String password,
  }) async {

  await _channel.invokeMethod(
    'scheduleDailyBackup',
    {
  'hour': hour,
  'minute': minute,
  'destinationUri': destinationUri,
  'folders': folders,
  'enableCompression': enableCompression,
  'maxBackupVersions': maxBackupVersions,
      'password': password,
      'format': '7z',
},
  );
}

static Future<void> openFolder(
  String uri,
) async {
  await _channel.invokeMethod(
    'openFolder',
    {
      'uri': uri,
    },
  );
}




  static Future<void> cancelDailyBackup() async {
  await _channel.invokeMethod(
    'cancelDailyBackup',
  );
  }

  static Future<void> cancelBackup() async {
    await _channel.invokeMethod('cancelBackup');
  }

static Future<String?> getDownloadPath() async {
  return await _channel.invokeMethod<String>(
    'getDownloadPath',
  );
}

static Future<bool> hasAllFilesAccess() async {
  final result =
      await _channel.invokeMethod<bool>(
    'hasAllFilesAccess',
  );

  return result ?? false;
}

static Future<void>
    requestAllFilesAccess() async {

  await _channel.invokeMethod(
    'requestAllFilesAccess',
  );
}
}

