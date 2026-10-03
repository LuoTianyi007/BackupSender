#include <flutter/dart_project.h>
#include <flutter/flutter_view_controller.h>
#include <windows.h>
#include <algorithm>

#include "flutter_window.h"
#include "utils.h"


constexpr wchar_t kBackupSenderMutexName[] =
    L"Local\\BackupSender_SingleInstance_v1";


HWND FindBackupSenderWindow() {
  HWND window =
      FindWindowW(
          nullptr,
          L"Backup Sender");

  if (window == nullptr) {
    window =
        FindWindowW(
            nullptr,
            L"backup_sender");
  }

  return window;
}


void RestoreBackupSenderWindow() {
  HWND window =
      FindBackupSenderWindow();

  if (window == nullptr) {
    return;
  }

  // Let the owning window restore itself and remove its tray icon.
  PostMessageW(window, WM_APP + 2, 0, 0);
}


int APIENTRY wWinMain(
    _In_ HINSTANCE instance,
    _In_opt_ HINSTANCE prev,
    _In_ wchar_t* command_line,
    _In_ int show_command) {

  std::vector<std::string> command_line_arguments = GetCommandLineArguments();
  const bool autostart = std::find(command_line_arguments.begin(),
      command_line_arguments.end(), "--autostart") != command_line_arguments.end();
  const bool start_in_tray = autostart &&
      std::find(command_line_arguments.begin(), command_line_arguments.end(),
                "--start-in-tray") != command_line_arguments.end();

  HANDLE single_instance_mutex =
      CreateMutexW(
          nullptr,
          FALSE,
          kBackupSenderMutexName);

  if (
      single_instance_mutex != nullptr &&
      GetLastError() == ERROR_ALREADY_EXISTS) {

    if (!autostart) RestoreBackupSenderWindow();

    CloseHandle(
        single_instance_mutex);

    return EXIT_SUCCESS;
  }


  if (
      !::AttachConsole(
          ATTACH_PARENT_PROCESS) &&
      ::IsDebuggerPresent()) {

    CreateAndAttachConsole();
  }


  ::CoInitializeEx(
      nullptr,
      COINIT_APARTMENTTHREADED);


  flutter::DartProject project(
      L"data");



  project.set_dart_entrypoint_arguments(
      std::move(
          command_line_arguments));


  FlutterWindow window(
      project, start_in_tray);


  Win32Window::Point origin(
      10,
      10);

  Win32Window::Size size(
      1280,
      720);


  if (
      !window.Create(
          L"Backup Sender",
          origin,
          size)) {

    if (
        single_instance_mutex !=
        nullptr) {

      CloseHandle(
          single_instance_mutex);
    }

    ::CoUninitialize();

    return EXIT_FAILURE;
  }


  window.SetQuitOnClose(
      true);


  ::MSG msg;

  while (
      ::GetMessage(
          &msg,
          nullptr,
          0,
          0)) {

    ::TranslateMessage(
        &msg);

    ::DispatchMessage(
        &msg);
  }


  ::CoUninitialize();


  if (
      single_instance_mutex !=
      nullptr) {

    CloseHandle(
        single_instance_mutex);
  }


  return EXIT_SUCCESS;
}