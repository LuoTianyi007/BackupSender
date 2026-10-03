import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

class BackupHistoryService {
  static const channel = MethodChannel('backup_sender/storage');
  static Future<List<Map<String, dynamic>>> load() async {
    final String raw;
    if (Platform.isAndroid) {
      raw = await channel.invokeMethod<String>('getBackupHistory') ?? '[]';
    } else {
      final prefs = await SharedPreferences.getInstance();
      raw = prefs.getString('backup_history') ?? '[]';
    }
    return (jsonDecode(raw) as List)
        .map((e) => Map<String, dynamic>.from(e as Map))
        .toList();
  }

  static Future<void> add(Map<String, dynamic> record) async {
    if (Platform.isAndroid) {
      await channel.invokeMethod('addBackupHistory', {
        'record': jsonEncode(record),
      });
    } else {
      final records = [record, ...await load()];
      final prefs = await SharedPreferences.getInstance();
      if (!await prefs.setString(
        'backup_history',
        jsonEncode(records.take(200).toList()),
      )) {
        throw Exception('无法保存备份记录');
      }
    }
  }
}
