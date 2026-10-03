import 'package:backup_sender/services/scheduler_service.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('Runs once per day across repeated settings updates and stops', () {
    var now = DateTime(2026, 10, 2, 21, 59);
    var calls = 0;
    final scheduler = SchedulerService(now: () => now);
    addTearDown(scheduler.stop);
    void configure() =>
        scheduler.startDailyTask(hour: 22, minute: 0, callback: () => calls++);
    configure();
    expect(calls, 0);
    now = DateTime(2026, 10, 2, 22);
    scheduler.checkNow();
    scheduler.checkNow();
    configure();
    expect(calls, 1);
    now = DateTime(2026, 10, 3, 22);
    scheduler.checkNow();
    expect(calls, 2);
    scheduler.stop();
    now = DateTime(2026, 10, 4, 22);
    scheduler.checkNow();
    expect(calls, 2);
  });
  test('Replacing the time removes the old schedule', () {
    var now = DateTime(2026, 10, 2, 21);
    var calls = 0;
    final scheduler = SchedulerService(now: () => now);
    addTearDown(scheduler.stop);
    scheduler.startDailyTask(hour: 22, minute: 0, callback: () => calls++);
    scheduler.startDailyTask(hour: 23, minute: 30, callback: () => calls++);
    now = DateTime(2026, 10, 2, 22);
    scheduler.checkNow();
    expect(calls, 0);
    now = DateTime(2026, 10, 2, 23, 30);
    scheduler.checkNow();
    expect(calls, 1);
  });
}
