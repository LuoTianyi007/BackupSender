import 'dart:async';

/// Runs once per calendar day during the configured minute while the app runs.
/// Checking the current clock also tolerates sleep/resume and clock changes.
class SchedulerService {
  SchedulerService({DateTime Function()? now}) : _now = now ?? DateTime.now;

  final DateTime Function() _now;
  Timer? _timer;
  int? _hour;
  int? _minute;
  String? _lastRunDay;
  void Function()? _callback;

  void startDailyTask({
    required int hour,
    required int minute,
    required void Function() callback,
  }) {
    if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
      throw ArgumentError('Invalid backup time');
    }
    stop();
    _hour = hour;
    _minute = minute;
    _callback = callback;
    _timer = Timer.periodic(const Duration(seconds: 15), (_) => checkNow());
    checkNow();
  }

  void checkNow() {
    final now = _now();
    final day = '${now.year}-${now.month}-${now.day}';
    if (_callback != null &&
        now.hour == _hour &&
        now.minute == _minute &&
        day != _lastRunDay) {
      _lastRunDay = day;
      _callback!();
    }
  }

  void stop() {
    _timer?.cancel();
    _timer = null;
    _callback = null;
  }
}
