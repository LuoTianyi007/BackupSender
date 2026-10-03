import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../models/backup_config.dart';



class ConfigService {


  static const String configKey = "backup_config";



  // 加载配置

  static Future<BackupConfig> loadConfig() async {


    final prefs =
        await SharedPreferences.getInstance();



    final data =
        prefs.getString(configKey);



    if(data == null){


      return BackupConfig.defaultConfig();


    }



    try{


      final json =
          jsonDecode(data);



      return BackupConfig.fromJson(
        json
      );


    }
    catch(e){


      return BackupConfig.defaultConfig();


    }


  }






  // 保存配置

  static Future<void> saveConfig(
      BackupConfig config
  ) async {



    final prefs =
        await SharedPreferences.getInstance();



    await prefs.setString(

      configKey,

      jsonEncode(
        config.toJson()
      ),

    );


  }



}