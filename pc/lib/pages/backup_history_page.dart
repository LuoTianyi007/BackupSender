import 'package:flutter/material.dart';

import '../services/backup_history_service.dart';

class BackupHistoryPage extends StatefulWidget {
  const BackupHistoryPage({super.key});
  @override
  State<BackupHistoryPage> createState() => _BackupHistoryPageState();
}

class _BackupHistoryPageState extends State<BackupHistoryPage>
    with WidgetsBindingObserver {
  late Future<List<Map<String, dynamic>>> records;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    records = BackupHistoryService.load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) refresh();
  }

  void refresh() => setState(() => records = BackupHistoryService.load());

  String formatSize(int bytes) {
    if (bytes < 1024) return '$bytes B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    var value = bytes.toDouble();
    var unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit++;
    }
    return '${value.toStringAsFixed(value >= 10 ? 0 : 1)} ${units[unit]}';
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('备份记录'),
      actions: [
        IconButton(
          tooltip: '刷新',
          onPressed: refresh,
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: FutureBuilder<List<Map<String, dynamic>>>(
      future: records,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return Center(child: Text('读取记录失败：${snapshot.error}'));
        }
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final items = snapshot.data!;
        if (items.isEmpty) return const Center(child: Text('暂无备份记录'));
        return ListView.builder(
          itemCount: items.length + 1,
          itemBuilder: (context, index) {
            if (index == 0) {
              return const Padding(
                padding: EdgeInsets.all(16),
                child: Text('保留最近 200 条记录；后台重试会分别记录。'),
              );
            }
            final item = items[index - 1];
            final success = item['success'] == true;
            final time = DateTime.fromMillisecondsSinceEpoch(
              (item['time'] as num).toInt(),
            ).toLocal().toString().split('.').first;
            final folders = List<String>.from(item['folders'] ?? []);
            final size = (item['size'] as num?)?.toInt();
            final sizeLabel = size == null ? '未知' : formatSize(size);
            return Card(
              child: ExpansionTile(
                leading: Icon(
                  success ? Icons.check_circle : Icons.error,
                  color: success ? Colors.green : Colors.red,
                ),
                title: Text('${success ? "成功" : "失败"} · $time'),
                subtitle: Text(
                  '${item['source'] ?? ''}\n${item['name'] ?? ''}\n大小：$sizeLabel',
                ),
                children: [
                  Padding(
                    padding: const EdgeInsets.all(16),
                    child: Align(
                      alignment: Alignment.centerLeft,
                      child: SelectableText(
                        [
                          if (!success) '失败原因：${item['error']}',
                          '参与备份的文件夹（${folders.length}）：',
                          ...folders.map(Uri.decodeFull),
                        ].join('\n'),
                      ),
                    ),
                  ),
                ],
              ),
            );
          },
        );
      },
    ),
  );
}
