import 'dart:io';
import 'package:crypto/crypto.dart';


class VerifyService {


  static Future<String> calculateHash(
      File file
      ) async {


    final bytes =
        await file.readAsBytes();


    final digest =
        sha256.convert(bytes);


    return digest.toString();

  }



  static Future<Map<String,String>>
  generateFolderHash(
      Directory folder
      ) async {


    Map<String,String> result={};


    await for(final entity in
        folder.list(
          recursive:true
        )){


      if(entity is File){


        final hash =
        await calculateHash(entity);


        final relative =
        entity.path.substring(
            folder.path.length
        );


        result[relative]=hash;


      }


    }


    return result;

  }


}