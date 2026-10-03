


class ZipService {



  static Future<String> createZip(

    List<String> folders,

    {

      Function(double progress, String fileName)? onProgress

    }

  ) async {



    throw UnsupportedError(

      "当前版本使用 zip_worker 进行压缩"

    );


  }



}