package com.example.backup_sender

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.io.FileOutputStream
import java.net.URLConnection
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.io.inputstream.ZipInputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.sf.sevenzipjbinding.IOutCreateCallback
import net.sf.sevenzipjbinding.IOutItem7z
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.ISequentialInStream
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.InputStreamSequentialInStream
import net.sf.sevenzipjbinding.impl.OutItemFactory
import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream
import net.sf.sevenzipjbinding.SevenZip
import java.security.MessageDigest
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Data


import java.util.Calendar
import android.os.Build
import android.os.Environment
import android.provider.Settings

import java.io.InputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.security.DigestInputStream
import java.security.DigestOutputStream

@Volatile
private var sevenZipInitialized = false

class MainActivity : FlutterActivity() {

private lateinit var methodChannel: MethodChannel

    private val channelName =
        "backup_sender/storage"

    private val pickFolderRequest =
        1001

    private var pendingResult:
            MethodChannel.Result? = null
    @Volatile private var activeBackupThread: Thread? = null


    override fun configureFlutterEngine(
        flutterEngine: FlutterEngine
    ) {
        super.configureFlutterEngine(
            flutterEngine
        )

       methodChannel =
    MethodChannel(
        flutterEngine.dartExecutor.binaryMessenger,
        channelName
    )

methodChannel
    .setMethodCallHandler { call, result ->

            when (call.method) {
            "getBackupHistory" -> result.success(BackupHistoryStore.load(applicationContext))
            "addBackupHistory" -> {
                try {
                    BackupHistoryStore.add(applicationContext, org.json.JSONObject(call.argument<String>("record") ?: "{}"))
                    result.success(true)
                } catch (e: Exception) { result.error("HISTORY_ERROR", e.message, null) }
            }

            "cancelBackup" -> {
                activeBackupThread?.interrupt()
                result.success(true)
            }

            "cancelDailyBackup" -> {

    WorkManager
        .getInstance(
            applicationContext
        )
        .cancelUniqueWork(
            "backup_sender_daily"
        )

    println(
        "每天定时备份任务已取消"
    )

    result.success(true)
}

"getDownloadPath" -> {

    val downloadDirectory =
        Environment
            .getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )

    result.success(
        downloadDirectory.absolutePath
    )
}




"hasAllFilesAccess" -> {

    val granted =
        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.R
        ) {
            Environment
                .isExternalStorageManager()
        } else {
            true
        }

    result.success(granted)
}


"requestAllFilesAccess" -> {

    if (Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.R
    ) {

        try {

            val intent =
                Intent(
                    Settings
                        .ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                )

            intent.data =
                Uri.parse(
                    "package:$packageName"
                )

            startActivity(intent)

        } catch (e: Exception) {

            val intent =
                Intent(
                    Settings
                        .ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                )

            startActivity(intent)
        }
    }

    result.success(true)
}


"openFolder" -> {

    val uriString =
        call.argument<String>("uri")

    if (uriString.isNullOrBlank()) {
        result.error(
            "INVALID_URI",
            "备份目录不能为空",
            null
        )

        return@setMethodCallHandler
    }

    try {

        val treeUri =
            Uri.parse(uriString)

        val documentId =
            DocumentsContract
                .getTreeDocumentId(
                    treeUri
                )

        val documentUri =
            DocumentsContract
                .buildDocumentUriUsingTree(
                    treeUri,
                    documentId
                )

        val intent =
            Intent(
                Intent.ACTION_VIEW
            ).apply {

                setDataAndType(
                    documentUri,
                    DocumentsContract
                        .Document
                        .MIME_TYPE_DIR
                )

                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                addFlags(
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }

        try {

            startActivity(intent)

        } catch (e: Exception) {

            // 某些手机文件管理器不支持 ACTION_VIEW，
            // 就退回到系统文件夹选择器，并定位到该目录。
            val fallbackIntent =
                Intent(
                    Intent.ACTION_OPEN_DOCUMENT_TREE
                )

            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O) {

                fallbackIntent.putExtra(
                    "android.provider.extra.INITIAL_URI",
                    treeUri
                )
            }

            startActivity(
                fallbackIntent
            )
        }

        result.success(true)

    } catch (e: Exception) {

        result.error(
            "OPEN_FOLDER_FAILED",
            "无法打开备份目录",
            e.message
        )
    }
}


            "scheduleDailyBackup" -> {

    val hour =
        call.argument<Int>("hour") ?: 2

    


val folders =
    call.argument<List<String>>("folders")
        ?: emptyList()

val enableCompression =
    call.argument<Boolean>(
        "enableCompression"
    ) ?: false

val maxBackupVersions =
    call.argument<Int>(
        "maxBackupVersions"
    ) ?: 5

val password =
    call.argument<String>(
        "password"
    ) ?: ""

println(
    "MainActivity收到源文件夹数量：${folders.size}"
)

folders.forEachIndexed { index, folder ->
    println(
        "MainActivity源文件夹${index + 1}：$folder"
    )
}

println(
    "后台压缩设置：$enableCompression"
)

println(
    "后台保留版本数量：$maxBackupVersions"
)

println(
    "后台是否设置ZIP密码：${password.isNotEmpty()}"
)







    val minute =
        call.argument<Int>("minute") ?: 0
        val destinationUriString =
    call.argument<String>(
        "destinationUri"
    )

if (
    destinationUriString.isNullOrBlank()
) {

    result.error(
        "INVALID_DESTINATION",
        "备份目录不能为空",
        null
    )

    return@setMethodCallHandler
}

    val now =
        Calendar.getInstance()

    val target =
        Calendar.getInstance().apply {

            set(
                Calendar.HOUR_OF_DAY,
                hour
            )

            set(
                Calendar.MINUTE,
                minute
            )

            set(
                Calendar.SECOND,
                0
            )

            set(
                Calendar.MILLISECOND,
                0
            )

            if (timeInMillis <= now.timeInMillis) {
                add(
                    Calendar.DAY_OF_YEAR,
                    1
                )
            }
        }

    val initialDelay =
        target.timeInMillis -
        now.timeInMillis

     val workerData =
    Data.Builder()
        .putString(
            "destinationUri",
            destinationUriString
        )
        .putStringArray(
            "folders",
            folders.toTypedArray()
        )
        .putBoolean(
            "enableCompression",
            enableCompression
        )
        .putInt(
            "maxBackupVersions",
            maxBackupVersions
        )
        .putString(
            "password",
            password
        )
        .build()
        

   val request =
    PeriodicWorkRequestBuilder<BackupWorker>(
        24,
        TimeUnit.HOURS
    )
        .setInputData(
            workerData
        )
        .setInitialDelay(
            initialDelay,
            TimeUnit.MILLISECONDS
        )
        .addTag(
            "backup_sender_daily"
        )
        .build()

    WorkManager
        .getInstance(
            applicationContext
        )
        .enqueueUniquePeriodicWork(
            "backup_sender_daily",
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            request
        )

    println(
        "每天定时任务已设置：%02d:%02d"
            .format(hour, minute)
    )

    result.success(true)
}

         




                // =====================================================
                // 选择 SAF 文件夹
                // =====================================================
                "pickFolder" -> {

                    pendingResult = result

                    val intent =
                        Intent(
                            Intent.ACTION_OPEN_DOCUMENT_TREE
                        )

                    intent.addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )

                    intent.addFlags(
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )

                    intent.addFlags(
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )

                    intent.addFlags(
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    )

                    startActivityForResult(
                        intent,
                        pickFolderRequest
                    )
                }


                // =====================================================
                // 获取 SAF 文件夹名称
                // =====================================================
                "getFolderName" -> {

                    val uriString =
                        call.argument<String>("uri")

                    if (uriString == null) {

                        result.error(
                            "INVALID_URI",
                            "URI不能为空",
                            null
                        )

                        return@setMethodCallHandler
                    }

                   try {

    if (
        uriString.startsWith(
            "content://"
        )
    ) {

        val treeUri =
            Uri.parse(
                uriString
            )

        result.success(
            getFolderName(
                treeUri
            )
        )

    } else {

        val directory =
            File(
                uriString
            )

        result.success(
            directory.name
                .ifBlank {
                    uriString
                }
        )
    }

} catch (e: Exception) {

                        result.error(
                            "GET_NAME_FAILED",
                            e.message,
                            null
                        )
                    }
                }


                // =====================================================
                // 递归列出 SAF 文件
                // =====================================================
                "listFiles" -> {

                    val uriString =
                        call.argument<String>("uri")

                    if (uriString == null) {

                        result.error(
                            "INVALID_URI",
                            "URI不能为空",
                            null
                        )

                        return@setMethodCallHandler
                    }

                    Thread {

                        try {

                            val treeUri =
                                Uri.parse(uriString)

                            val rootDocumentId =
                                DocumentsContract
                                    .getTreeDocumentId(
                                        treeUri
                                    )

                            val files =
                                mutableListOf<
                                        Map<String, Any?>>()

                            scanDirectory(
                                treeUri,
                                rootDocumentId,
                                "",
                                files
                            )

                            runOnUiThread {
                                result.success(files)
                            }

                        } catch (e: Exception) {

                            runOnUiThread {

                                result.error(
                                    "READ_FAILED",
                                    e.message,
                                    null
                                )
                            }
                        }

                    }.also { activeBackupThread = it }.start()
                }


                // =====================================================
                // SAF 源文件夹复制到 cache/temp_backup
                // =====================================================
                "copyFolderToTemp" -> {

    val sourceString =
        call.argument<String>("uri")

    val index =
        call.argument<Int>("index")
            ?: 1

    if (sourceString.isNullOrBlank()) {

        result.error(
            "INVALID_SOURCE",
            "源目录不能为空",
            null
        )

        return@setMethodCallHandler
    }

    Thread {

        try {

            val tempRoot =
                File(
                    cacheDir,
                    "temp_backup"
                )

            // 第一个源目录开始时，
            // 清除上一次残留的临时备份
            if (
                index == 1 &&
                tempRoot.exists()
            ) {
                tempRoot.deleteRecursively()
            }

            tempRoot.mkdirs()


            // ==========================================
            // SAF：content://...
            // ==========================================
            if (
                sourceString.startsWith(
                    "content://"
                )
            ) {

                val treeUri =
                    Uri.parse(
                        sourceString
                    )

                val rootDocumentId =
                    DocumentsContract
                        .getTreeDocumentId(
                            treeUri
                        )

                val folderName =
                    getFolderName(
                        treeUri
                    )

                val targetRoot =
                    File(
                        tempRoot,
                        "${index}_$folderName"
                    )

                targetRoot.mkdirs()

                val copiedFiles =
                    copyDirectoryToFileSystem(
                        treeUri,
                        rootDocumentId,
                        targetRoot
                    )

                runOnUiThread {

                    result.success(
                        mapOf(
                            "files" to copiedFiles,
                            "path" to
                                targetRoot.absolutePath,
                            "tempRoot" to
                                tempRoot.absolutePath
                        )
                    )
                }

                return@Thread
            }


            // ==========================================
            // 普通文件路径：
            // /storage/emulated/0/Download
            // ==========================================

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.R &&
                !Environment
                    .isExternalStorageManager()
            ) {
                throw Exception(
                    "没有“所有文件访问权限”"
                )
            }

            val sourceDirectory =
                File(
                    sourceString
                )

            if (
                !sourceDirectory.exists()
            ) {
                throw Exception(
                    "源目录不存在：$sourceString"
                )
            }

            if (
                !sourceDirectory.isDirectory
            ) {
                throw Exception(
                    "源路径不是文件夹：$sourceString"
                )
            }

            val folderName =
                sourceDirectory.name
                    .ifBlank {
                        "Download"
                    }

            val targetRoot =
                File(
                    tempRoot,
                    "${index}_$folderName"
                )

            targetRoot.mkdirs()

            val copiedFiles =
                copyPhysicalDirectoryToTemp(
                    sourceDirectory,
                    targetRoot
                )

            println(
                "普通路径复制完成：$sourceString，"
                    + "共 $copiedFiles 个文件"
            )

            runOnUiThread {

                result.success(
                    mapOf(
                        "files" to copiedFiles,
                        "path" to
                            targetRoot.absolutePath,
                        "tempRoot" to
                            tempRoot.absolutePath
                    )
                )
            }

        } catch (e: Exception) {

            runOnUiThread {

                result.error(
                    "COPY_FAILED",
                    e.message,
                    null
                )
            }
        }

    }.also { activeBackupThread = it }.start()
}

                // =====================================================
                // cache/temp_backup 写入用户选择的 SAF 备份目录
                // =====================================================
                "exportTempBackup" -> {

                    val destinationUriString =
                        call.argument<String>(
                            "destinationUri"
                        )

                    val backupName =
                        call.argument<String>(
                            "backupName"
                        )
                    val maxBackupVersions =
    call.argument<Int>("maxBackupVersions") ?: 5

                    if (
                        destinationUriString == null ||
                        backupName == null
                    ) {

                        result.error(
                            "INVALID_ARGUMENT",
                            "备份目录或备份名称不能为空",
                            null
                        )

                        return@setMethodCallHandler
                    }

                    Thread {

                        try {

                            val destinationTreeUri =
                                Uri.parse(
                                    destinationUriString
                                )

                            val rootDocumentId =
                                DocumentsContract
                                    .getTreeDocumentId(
                                        destinationTreeUri
                                    )

                            val rootDocumentUri =
                                DocumentsContract
                                    .buildDocumentUriUsingTree(
                                        destinationTreeUri,
                                        rootDocumentId
                                    )

                            val tempRoot =
                                File(
                                    cacheDir,
                                    "temp_backup"
                                )

                            if (!tempRoot.exists()) {

                                throw Exception(
                                    "temp_backup不存在"
                                )
                            }

                            val backupDirectoryUri =
                                DocumentsContract
                                    .createDocument(
                                        contentResolver,
                                        rootDocumentUri,
                                        DocumentsContract
                                            .Document
                                            .MIME_TYPE_DIR,
                                        backupName
                                    )
                                    ?: throw Exception(
                                        "无法创建最终备份目录"
                                    )

                            val copiedFiles =
                                copyFileSystemToSaf(
                                    tempRoot,
                                    backupDirectoryUri
                                )

                            println(
    "普通备份SHA-256校验通过：$copiedFiles 个文件"
)
                            val backupSize =
                                tempRoot.walkTopDown()
                                    .filter { it.isFile }
                                    .sumOf { it.length() }
cleanupOldBackups(
    destinationUriString,
    maxBackupVersions
)
                            runOnUiThread {

                                result.success(
                                    mapOf(
                                        "files" to copiedFiles,
                                        "name" to backupName,
                                        "uri" to
                                                backupDirectoryUri
                                                    .toString()
                                        ,
                                        "size" to backupSize
                                    )
                                )
                            }

                        } catch (e: Exception) {

                        

                            runOnUiThread {

                                result.error(
                                    "EXPORT_FAILED",
                                    e.message,
                                    null
                                )
                            }
                        }
finally {

    try {

        val tempRoot =
            File(
                cacheDir,
                "temp_backup"
            )

        if (tempRoot.exists()) {

            tempRoot
                .deleteRecursively()
        }

        println(
            "普通备份临时缓存已清理"
        )

    } catch (e: Exception) {

        println(
            "清理临时缓存失败：${e.message}"
        )
    }
}





                    }.also { activeBackupThread = it }.start()
                }



"compressFoldersDirectly" -> {

    println(
        "=== DIRECT_SAF_STREAMING_FAST_V2 ==="
    )

    val folders =
        call.argument<List<String>>(
            "folders"
        ) ?: emptyList()

    val destinationUriString =
        call.argument<String>(
            "destinationUri"
        )

    val backupName =
        call.argument<String>(
            "backupName"
        )

    val password =
        call.argument<String>(
            "password"
        ) ?: ""

    val maxBackupVersions =
        call.argument<Int>(
            "maxBackupVersions"
        ) ?: 5

    if (
        folders.isEmpty() ||
        destinationUriString.isNullOrBlank() ||
        backupName.isNullOrBlank()
    ) {
        result.error(
            "INVALID_ARGUMENT",
            "源目录、备份目录或备份名称不能为空",
            null
        )
        return@setMethodCallHandler
    }

    Thread backupThread@{

        var finalZipUri: Uri? = null

        try {
            // =====================================================
            // 1. 只收集源文件引用，不复制任何文件到 App cache
            // =====================================================

            sendBackupProgress(
                progress = 1,
                status = "正在扫描源文件..."
            )

            val sources =
                mutableListOf<DirectZipSource>()

            folders.forEachIndexed { index, sourcePath ->
                if (Thread.currentThread().isInterrupted) throw InterruptedException("backup cancelled")

                if (sourcePath.startsWith("content://")) {
                    val treeUri = Uri.parse(sourcePath)

                    val rootDocumentId =
                        DocumentsContract
                            .getTreeDocumentId(treeUri)

                    val folderName =
                        getFolderName(treeUri)

                    val topFolder =
                        "${index + 1}_$folderName"

                    sources.add(
                        DirectZipSource(
                            zipPath = "$topFolder/",
                            isDirectory = true
                        )
                    )

                    collectSafDirectZipSources(
                        treeUri = treeUri,
                        parentDocumentId = rootDocumentId,
                        zipPrefix = topFolder,
                        result = sources
                    )

                } else {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        !Environment.isExternalStorageManager()
                    ) {
                        throw Exception(
                            "没有“所有文件访问权限”"
                        )
                    }

                    val sourceDirectory =
                        File(sourcePath)

                    if (!sourceDirectory.exists()) {
                        throw Exception(
                            "源目录不存在：$sourcePath"
                        )
                    }

                    if (!sourceDirectory.isDirectory) {
                        throw Exception(
                            "源路径不是文件夹：$sourcePath"
                        )
                    }

                    if (!sourceDirectory.canRead()) {
                        throw Exception(
                            "无法读取源目录：$sourcePath"
                        )
                    }

                    val folderName =
                        sourceDirectory.name.ifBlank {
                            "Download"
                        }

                    val topFolder =
                        "${index + 1}_$folderName"

                    sources.add(
                        DirectZipSource(
                            zipPath = "$topFolder/",
                            isDirectory = true
                        )
                    )

                    collectPhysicalDirectZipSources(
                        directory = sourceDirectory,
                        zipPrefix = topFolder,
                        result = sources
                    )
                }
            }

            val totalFiles =
                sources.count {
                    !it.isDirectory
                }

            val storeFiles =
                sources.count {
                    !it.isDirectory &&
                    it.size >= 0L &&
                    shouldStoreWithoutCompression(
                        it.zipPath
                    )
                }

            println(
                "流式直写ZIP文件总数：$totalFiles"
            )

            println(
                "快速STORE文件：$storeFiles，DEFLATE文件：${totalFiles - storeFiles}"
            )

            // A 7z archive without a password exposes its entry names. Do
            // not silently fall back to ZIP when filename privacy is enabled.
            if (password.isEmpty()) {
                throw IllegalArgumentException("启用7z压缩前请先设置密码，否则无法隐藏文件名")
            }

            val sevenZipResult = createSevenZipBackup(
                sources = sources,
                destinationUriString = destinationUriString,
                backupName = backupName,
                password = password,
                maxBackupVersions = maxBackupVersions
            )
            runOnUiThread {
                result.success(sevenZipResult)
            }
            return@backupThread

            // =====================================================
            // 2. 直接在用户选择的 SAF 目录创建最终 ZIP
            //    不创建 cacheDir/backup_xxx.zip
            // =====================================================

            val destinationTreeUri =
                Uri.parse(destinationUriString)

            val rootDocumentId =
                DocumentsContract
                    .getTreeDocumentId(
                        destinationTreeUri
                    )

            val rootDocumentUri =
                DocumentsContract
                    .buildDocumentUriUsingTree(
                        destinationTreeUri,
                        rootDocumentId
                    )

            finalZipUri =
                DocumentsContract
                    .createDocument(
                        contentResolver,
                        rootDocumentUri,
                        "application/zip",
                        "$backupName.zip"
                    )
                    ?: throw Exception(
                        "无法创建最终ZIP文件"
                    )

            val rawOutput =
                contentResolver
                    .openOutputStream(
                        finalZipUri,
                        "w"
                    )
                    ?: throw Exception(
                        "无法打开最终ZIP写入流"
                    )

            val sourceHashes =
                linkedMapOf<String, String>()

            var processedFiles = 0

            // 1 MiB 缓冲区，减少 SAF / FUSE 小块 I/O。
            val buffer =
                ByteArray(1024 * 1024)

            BufferedOutputStream(
                rawOutput,
                1024 * 1024
            ).use { bufferedOutput ->

                val zipOutputStream =
                    if (password.isNotEmpty()) {
                        ZipOutputStream(
                            bufferedOutput,
                            password.toCharArray()
                        )
                    } else {
                        ZipOutputStream(
                            bufferedOutput
                        )
                    }

                zipOutputStream.use { zipStream ->

                    for (source in sources) {

                        val parameters =
                            createDirectZipParameters(
                                zipPath = source.zipPath,
                                password = password,
                                isDirectory = source.isDirectory,
                                entrySize = source.size
                            )

                        zipStream.putNextEntry(
                            parameters
                        )

                        if (source.isDirectory) {
                            zipStream.closeEntry()
                            continue
                        }

                        val digest =
                            MessageDigest
                                .getInstance(
                                    "SHA-256"
                                )

                        openDirectZipSource(
                            source
                        ).use { rawInput ->

                            BufferedInputStream(
                                rawInput,
                                1024 * 1024
                            ).use { input ->

                                while (true) {
                                    if (Thread.currentThread().isInterrupted) {
                                        throw InterruptedException("backup cancelled")
                                    }
                                    val count =
                                        input.read(buffer)

                                    if (count == -1) {
                                        break
                                    }

                                    if (count > 0) {
                                        digest.update(
                                            buffer,
                                            0,
                                            count
                                        )

                                        zipStream.write(
                                            buffer,
                                            0,
                                            count
                                        )
                                    }
                                }
                            }
                        }

                        zipStream.closeEntry()

                        sourceHashes[source.zipPath] =
                            digestToHex(
                                digest.digest()
                            )

                        processedFiles++

                        val progress =
                            if (totalFiles <= 0) {
                                75
                            } else {
                                5 +
                                    (
                                        processedFiles
                                            .toDouble() /
                                            totalFiles
                                    * 70.0
                                    ).toInt()
                            }

                        val useStore =
                            source.size >= 0L &&
                            shouldStoreWithoutCompression(
                                source.zipPath
                            )

                        sendBackupProgress(
                            progress = progress.coerceIn(5, 75),
                            status =
                                if (useStore) {
                                    "正在快速打包..."
                                } else {
                                    "正在压缩..."
                                },
                            currentFile = source.zipPath,
                            processedFiles = processedFiles,
                            totalFiles = totalFiles
                        )

                        println(
                            "流式处理：$processedFiles / $totalFiles " +
                                "${if (useStore) "STORE" else "DEFLATE"} " +
                                source.zipPath
                        )
                    }
                }
            }

            println(
                "最终ZIP已直接写入SAF目录"
            )

            // =====================================================
            // 3. 直接重新读取最终 SAF ZIP，逐条目做 SHA-256 校验
            //    不解压、不复制回 App cache
            // =====================================================

            sendBackupProgress(
                progress = 76,
                status = "正在校验ZIP...",
                processedFiles = 0,
                totalFiles = totalFiles
            )

            val rawZipInput =
                contentResolver
                    .openInputStream(
                        finalZipUri
                    )
                    ?: throw Exception(
                        "无法读取最终ZIP文件"
                    )

            var verifiedFiles = 0

            BufferedInputStream(
                rawZipInput,
                1024 * 1024
            ).use { bufferedInput ->

                val zipInputStream =
                    if (password.isNotEmpty()) {
                        ZipInputStream(
                            bufferedInput,
                            password.toCharArray()
                        )
                    } else {
                        ZipInputStream(
                            bufferedInput
                        )
                    }

                zipInputStream.use { zipStream ->

                    while (true) {
                        val header =
                            zipStream.nextEntry
                                ?: break

                        val zipPath =
                            header.fileName
                                .replace(
                                    '\\',
                                    '/'
                                )

                        if (header.isDirectory) {
                            continue
                        }

                        val expectedHash =
                            sourceHashes[zipPath]
                                ?: throw Exception(
                                    "ZIP校验失败：出现未知文件 $zipPath"
                                )

                        val digest =
                            MessageDigest
                                .getInstance(
                                    "SHA-256"
                                )

                        while (true) {
                            if (Thread.currentThread().isInterrupted) {
                                throw InterruptedException("backup cancelled")
                            }
                            val count =
                                zipStream.read(buffer)

                            if (count == -1) {
                                break
                            }

                            if (count > 0) {
                                digest.update(
                                    buffer,
                                    0,
                                    count
                                )
                            }
                        }

                        val actualHash =
                            digestToHex(
                                digest.digest()
                            )

                        if (expectedHash != actualHash) {
                            throw Exception(
                                "ZIP SHA-256校验失败：$zipPath"
                            )
                        }

                        verifiedFiles++

                        val verifyProgress =
                            if (totalFiles <= 0) {
                                96
                            } else {
                                76 +
                                    (
                                        verifiedFiles
                                            .toDouble() /
                                            totalFiles
                                    * 20.0
                                    ).toInt()
                            }

                        sendBackupProgress(
                            progress = verifyProgress.coerceIn(76, 96),
                            status = "正在校验ZIP...",
                            currentFile = zipPath,
                            processedFiles = verifiedFiles,
                            totalFiles = totalFiles
                        )

                        println(
                            "流式ZIP校验：$verifiedFiles / $totalFiles $zipPath"
                        )
                    }
                }
            }

            if (verifiedFiles != sourceHashes.size) {
                throw Exception(
                    "ZIP校验失败：文件数量不一致，源=${sourceHashes.size}，ZIP=$verifiedFiles"
                )
            }

            println(
                "最终SAF ZIP严格SHA-256校验通过"
            )

            // =====================================================
            // 4. 清理旧备份并返回
            // =====================================================

            sendBackupProgress(
                progress = 98,
                status = "正在清理旧备份...",
                processedFiles = verifiedFiles,
                totalFiles = totalFiles
            )

            cleanupOldBackups(
                destinationUriString,
                maxBackupVersions
            )

            val zipSize =
                getDocumentSize(
                    finalZipUri
                )

            sendBackupProgress(
                progress = 100,
                status = "备份完成",
                processedFiles = totalFiles,
                totalFiles = totalFiles
            )

            runOnUiThread {
                result.success(
                    mapOf(
                        "name" to "$backupName.zip",
                        "uri" to finalZipUri.toString(),
                        "size" to zipSize,
                        "encrypted" to password.isNotEmpty(),
                        "files" to totalFiles
                    )
                )
            }

        } catch (e: Throwable) {

            println(
                "Android 7z备份失败：${e.message}"
            )

            // 出错时删除已经写了一部分的最终 ZIP。
            finalZipUri?.let { uri ->
                try {
                    DocumentsContract
                        .deleteDocument(
                            contentResolver,
                            uri
                        )
                } catch (_: Exception) {
                }
            }

            runOnUiThread {
                result.error(
                    "SEVEN_ZIP_FAILED",
                    e.message ?: e.javaClass.simpleName,
                    null
                )
            }
        } finally {
            if (activeBackupThread === Thread.currentThread()) {
                activeBackupThread = null
            }
        }

    }.also { activeBackupThread = it }.start()
}


"compressTempBackup" -> {

    // Legacy compression path intentionally disabled.
    // Compressed Android backups must use compressFoldersDirectly,
    // which streams directly to the final SAF ZIP and does not create
    // temp_backup or cacheDir/backup_xxx.zip.

    println(
        "!!! LEGACY compressTempBackup BLOCKED !!!"
    )

    result.error(
        "LEGACY_COMPRESSION_DISABLED",
        "旧压缩接口已禁用，请使用 compressFoldersDirectly",
        null
    )
}

                else -> {
                    result.notImplemented()
                }
            }
        }
    }
private fun verifyZipFile(
    zipFile: File,
    password: String
): Boolean {

    if (!zipFile.exists()) {
        return false
    }

    if (zipFile.length() <= 0) {
        return false
    }

    return try {

        val zip =
            if (password.isNotEmpty()) {
                ZipFile(
                    zipFile,
                    password.toCharArray()
                )
            } else {
                ZipFile(zipFile)
            }

        // 先检查 ZIP 基本结构
        if (!zip.isValidZipFile) {
            return false
        }

        // 真正把 ZIP 中每一个文件读取一遍
        // 这样可以检查损坏、CRC、加密数据等问题
        for (fileHeader in zip.fileHeaders) {

            if (fileHeader.isDirectory) {
                continue
            }

            zip.getInputStream(
                fileHeader
            ).use { input ->

                val buffer =
                    ByteArray(
                        1024 * 1024
                    )

                while (true) {

                    val read =
                        input.read(buffer)

                    if (read == -1) {
                        break
                    }
                }
            }
        }

        true

    } catch (e: Exception) {

        println(
            "ZIP校验失败：${e.message}"
        )

        false
    }

    
}
private fun sha256File(file: File): String {

    val digest =
        MessageDigest.getInstance("SHA-256")

    FileInputStream(file).use { input ->

        val buffer =
            ByteArray(1024 * 1024)

        while (true) {

            val read =
                input.read(buffer)

            if (read == -1) {
                break
            }

            digest.update(
                buffer,
                0,
                read
            )
        }
    }

    return digest
        .digest()
        .joinToString("") {
            "%02x".format(it)
        }
}


private fun sha256Uri(uri: Uri): String {

    val digest =
        MessageDigest.getInstance("SHA-256")

    val inputStream =
        contentResolver
            .openInputStream(uri)
            ?: throw Exception(
                "无法读取最终ZIP进行校验"
            )

    inputStream.use { input ->

        val buffer =
            ByteArray(1024 * 1024)

        while (true) {

            val read =
                input.read(buffer)

            if (read == -1) {
                break
            }

            digest.update(
                buffer,
                0,
                read
            )
        }
    }

    return digest
        .digest()
        .joinToString("") {
            "%02x".format(it)
        }
}

private fun createSevenZipBackup(
    sources: List<DirectZipSource>,
    destinationUriString: String,
    backupName: String,
    password: String,
    maxBackupVersions: Int
): Map<String, Any> {
    val totalFiles = sources.count { !it.isDirectory }
    val totalBytes = sources
        .filter { !it.isDirectory && it.size > 0L }
        .sumOf { it.size }
    val processedFiles = AtomicInteger(0)
    val lastProgress = AtomicInteger(5)
    val completedEntries = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    val currentPath = java.util.concurrent.atomic.AtomicReference("")
    val ownerThread = Thread.currentThread()
    val openedStreams = java.util.Collections.synchronizedList(mutableListOf<InputStream>())
    fun reportProgress() {
        sendBackupProgress(
            progress = lastProgress.get(), status = "正在压缩7z...",
            currentFile = currentPath.get(),
            processedFiles = processedFiles.get(), totalFiles = totalFiles
        )
    }
    fun completeFile(index: Int) {
        if (completedEntries.add(index)) {
            processedFiles.incrementAndGet()
            reportProgress()
        }
    }
    val tempArchive = File.createTempFile("backup_", ".7z", cacheDir)
    var finalUri: Uri? = null

    try {
        sendBackupProgress(
            progress = 2,
            status = "正在生成7z压缩包...",
            processedFiles = 0,
            totalFiles = totalFiles
        )

        RandomAccessFile(tempArchive, "rw").use { randomAccessFile ->
            val outStream = RandomAccessFileOutStream(randomAccessFile)
            ensureSevenZipInitialized()
            val archive = SevenZip.openOutArchive7z()
            try {
                // Each native SetProperties call resets the previous options.
                // The binding applies MT and solid AFTER HE, disabling header
                // encryption. Use only HE so it remains the final property.
                archive.setHeaderEncryption(true)

                val callback = object : IOutCreateCallback<IOutItem7z>,
                    ICryptoGetTextPassword {
                    override fun cryptoGetTextPassword(): String = password

                    override fun setTotal(total: Long) {
                    }

                    override fun setCompleted(complete: Long) {
                        if (ownerThread.isInterrupted) {
                            throw SevenZipException("backup cancelled")
                        }
                        val progress =
                            if (totalBytes <= 0L) 5
                            else 5 + (complete.toDouble() / totalBytes * 85.0).toInt()
                        lastProgress.updateAndGet { maxOf(it, progress.coerceIn(5, 90)) }
                        reportProgress()
                    }

                    override fun setOperationResult(operationResultOk: Boolean) {
                        if (!operationResultOk) {
                            throw SevenZipException("7z条目压缩失败")
                        }
                    }

                    override fun getItemInformation(
                        index: Int,
                        outItemFactory: OutItemFactory<IOutItem7z>
                    ): IOutItem7z {
                        if (ownerThread.isInterrupted) {
                            throw SevenZipException("backup cancelled")
                        }
                        val source = sources[index]
                        val item = outItemFactory.createOutItem()
                        item.setPropertyPath(source.zipPath)
                        if (source.isDirectory) {
                            item.setPropertyIsDir(true)
                        } else if (source.size >= 0L) {
                            item.setDataSize(source.size)
                            if (source.size == 0L) completeFile(index)
                        }
                        return item
                    }

                    override fun getStream(index: Int): ISequentialInStream? {
                        if (ownerThread.isInterrupted) {
                            throw SevenZipException("backup cancelled")
                        }
                        val source = sources[index]
                        if (source.isDirectory) return null
                        currentPath.set(source.zipPath)
                        reportProgress()
                        val stream = InterruptAwareInputStream(
                            openDirectZipSource(source), ownerThread, source.size
                        ) { completeFile(index) }
                        openedStreams.add(stream)
                        return InputStreamSequentialInStream(
                            stream
                        )
                    }
                }

                synchronized(SevenZip::class.java) {
                    archive.createArchive(outStream, sources.size, callback)
                }
            } finally {
                archive.close()
            }
        }

        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("backup cancelled")
        }

        sendBackupProgress(91, "正在校验文件名加密...", processedFiles = totalFiles, totalFiles = totalFiles)
        SevenZipPrivacy.verify(tempArchive, password, sources.size)

        val destinationTreeUri = Uri.parse(destinationUriString)
        val rootDocumentId = DocumentsContract.getTreeDocumentId(destinationTreeUri)
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            destinationTreeUri,
            rootDocumentId
        )
        finalUri = DocumentsContract.createDocument(
            contentResolver,
            rootDocumentUri,
            "application/x-7z-compressed",
            "$backupName.7z"
        ) ?: throw Exception("无法创建最终7z文件")

        sendBackupProgress(
            progress = 92,
            status = "正在写入备份文件...",
            processedFiles = totalFiles,
            totalFiles = totalFiles
        )
        val output = contentResolver.openOutputStream(finalUri, "w")
            ?: throw Exception("无法打开最终7z写入流")
        FileInputStream(tempArchive).use { input ->
            output.use { rawOutput ->
                BufferedOutputStream(rawOutput, 1024 * 1024).use { bufferedOutput ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedException("backup cancelled")
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) bufferedOutput.write(buffer, 0, count)
                    }
                }
            }
        }

        if (ownerThread.isInterrupted) throw InterruptedException("backup cancelled")
        cleanupOldBackups(destinationUriString, maxBackupVersions)
        val archiveSize = getDocumentSize(finalUri)
        sendBackupProgress(
            progress = 100,
            status = "备份完成",
            processedFiles = totalFiles,
            totalFiles = totalFiles
        )
        return mapOf(
            "name" to "$backupName.7z",
            "uri" to finalUri.toString(),
            "size" to archiveSize,
            "encrypted" to password.isNotEmpty(),
            "files" to totalFiles,
            "format" to "7z"
        )
    } catch (e: Exception) {
        finalUri?.let {
            try {
                DocumentsContract.deleteDocument(contentResolver, it)
            } catch (_: Exception) {
            }
        }
        throw e
    } finally {
        openedStreams.forEach { try { it.close() } catch (_: Exception) {} }
        if (tempArchive.exists()) {
            tempArchive.delete()
        }
    }
}

private class InterruptAwareInputStream(
    private val delegate: InputStream,
    private val ownerThread: Thread,
    private val expectedSize: Long,
    private val onComplete: () -> Unit
) : InputStream() {
    private var ended = false
    private var bytesRead = 0L
    override fun read(): Int {
        checkCancelled()
        if (ended) return -1
        val result = delegate.read()
        if (result == -1) finish() else consumed(1)
        return result
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkCancelled()
        if (length == 0) return 0
        if (ended) return -1
        val result = delegate.read(buffer, offset, length)
        if (result == -1) finish() else consumed(result)
        return result
    }

    override fun close() {
        delegate.close()
    }

    private fun checkCancelled() {
        if (ownerThread.isInterrupted) {
            throw java.io.IOException("backup cancelled")
        }
    }

    private fun finish() {
        if (!ended) {
            ended = true
            delegate.close()
            onComplete()
        }
    }

    private fun consumed(count: Int) {
        bytesRead += count
        // 7z may stop reading at dataSize without asking for an EOF read.
        if (expectedSize >= 0 && bytesRead >= expectedSize) finish()
    }
}

@Synchronized
private fun ensureSevenZipInitialized() {
    if (sevenZipInitialized) {
        return
    }
    try {
        // The Android artifact already packages the native library under
        // lib/<abi>. Loading it through the platform-JAR extractor makes the
        // library look for a non-existent lib/arm64 path and caused the
        // process to abort before Kotlin could report an exception.
        val nativeLibrary = File(
            applicationInfo.nativeLibraryDir,
            "lib7-Zip-JBinding.so"
        )
        if (nativeLibrary.isFile) {
            System.load(nativeLibrary.absolutePath)
        } else {
            System.loadLibrary("7-Zip-JBinding")
        }
        SevenZip.initLoadedLibraries()
        if (!SevenZip.isInitializedSuccessfully()) {
            throw IllegalStateException(
                SevenZip.getLastInitializationException()?.message
                    ?: "native library initialization failed"
            )
        }
        sevenZipInitialized = true
    } catch (t: Throwable) {
        throw IllegalStateException("7z引擎初始化失败：${t.message}", t)
    }
}

private fun extractSevenZipLibraryFromApk(): File {
    val output = File(cacheDir, "lib7-Zip-JBinding.so")
    if (output.exists() && output.length() > 0L) {
        return output
    }
    val apk = java.util.zip.ZipFile(applicationInfo.sourceDir)
    apk.use { zip ->
        var entry = zip.getEntry(
            "lib/${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"}/lib7-Zip-JBinding.so"
        )
        if (entry == null) {
            for (abi in Build.SUPPORTED_ABIS) {
                entry = zip.getEntry("lib/$abi/lib7-Zip-JBinding.so")
                if (entry != null) break
            }
        }
        val selected = entry ?: throw IllegalStateException(
            "APK中没有找到7z native库，ABI=${Build.SUPPORTED_ABIS.joinToString()}"
        )
        zip.getInputStream(selected).use { input ->
            FileOutputStream(output).use { outputStream ->
                input.copyTo(outputStream, 1024 * 1024)
            }
        }
    }
    return output
}

private data class DirectZipSource(
    val zipPath: String,
    val uri: Uri? = null,
    val file: File? = null,
    val isDirectory: Boolean = false,
    val size: Long = -1L
)

private fun collectSafDirectZipSources(
    treeUri: Uri,
    parentDocumentId: String,
    zipPrefix: String,
    result: MutableList<DirectZipSource>
) {
    val childrenUri =
        DocumentsContract
            .buildChildDocumentsUriUsingTree(
                treeUri,
                parentDocumentId
            )

    val projection =
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )

    contentResolver
        .query(
            childrenUri,
            projection,
            null,
            null,
            null
        )
        ?.use { cursor ->

            val idIndex =
                cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID
                )

            val nameIndex =
                cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                )

            val mimeIndex =
                cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                )

            val sizeIndex =
                cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_SIZE
                )

            if (
                idIndex < 0 ||
                nameIndex < 0 ||
                mimeIndex < 0
            ) {
                return@use
            }

            while (cursor.moveToNext()) {

                val documentId =
                    cursor.getString(
                        idIndex
                    )

                val name =
                    cursor.getString(
                        nameIndex
                    ) ?: continue

                val mimeType =
                    cursor.getString(
                        mimeIndex
                    ) ?: "application/octet-stream"

                val fileSize =
                    if (
                        sizeIndex >= 0 &&
                        !cursor.isNull(sizeIndex)
                    ) {
                        cursor.getLong(sizeIndex)
                    } else {
                        -1L
                    }

                val zipPath =
                    "$zipPrefix/$name"
                        .replace(
                            '\\',
                            '/'
                        )

                if (
                    mimeType ==
                    DocumentsContract.Document.MIME_TYPE_DIR
                ) {
                    result.add(
                        DirectZipSource(
                            zipPath = "$zipPath/",
                            isDirectory = true
                        )
                    )

                    collectSafDirectZipSources(
                        treeUri = treeUri,
                        parentDocumentId = documentId,
                        zipPrefix = zipPath,
                        result = result
                    )

                } else {
                    val fileUri =
                        DocumentsContract
                            .buildDocumentUriUsingTree(
                                treeUri,
                                documentId
                            )

                    result.add(
                        DirectZipSource(
                            zipPath = zipPath,
                            uri = fileUri,
                            size = fileSize
                        )
                    )
                }
            }
        }
}

private fun collectPhysicalDirectZipSources(
    directory: File,
    zipPrefix: String,
    result: MutableList<DirectZipSource>
) {
    val children =
        directory.listFiles()
            ?: throw Exception(
                "无法读取目录：${directory.absolutePath}"
            )

    for (
        child in children
            .sortedBy {
                it.name
            }
    ) {
        val zipPath =
            "$zipPrefix/${child.name}"
                .replace(
                    '\\',
                    '/'
                )

        if (child.isDirectory) {
            result.add(
                DirectZipSource(
                    zipPath = "$zipPath/",
                    isDirectory = true
                )
            )

            collectPhysicalDirectZipSources(
                directory = child,
                zipPrefix = zipPath,
                result = result
            )

        } else if (child.isFile) {
            result.add(
                DirectZipSource(
                    zipPath = zipPath,
                    file = child,
                    size = child.length()
                )
            )
        }
    }
}

private fun openDirectZipSource(
    source: DirectZipSource
): InputStream {
    source.uri?.let { uri ->
        return contentResolver
            .openInputStream(
                uri
            )
            ?: throw Exception(
                "无法读取文件：${source.zipPath}"
            )
    }

    source.file?.let { file ->
        return FileInputStream(
            file
        )
    }

    throw Exception(
        "无效的压缩源：${source.zipPath}"
    )
}

private fun shouldStoreWithoutCompression(
    zipPath: String
): Boolean {
    val extension =
        zipPath
            .substringAfterLast(
                '.',
                ""
            )
            .lowercase()

    return when (extension) {
        // 图片：通常已经经过高效压缩，再 DEFLATE 收益很小。
        "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "avif" -> true

        // 视频：容器内的视频流通常已经压缩。
        "mp4", "m4v", "mov", "mkv", "webm", "3gp" -> true

        // 音频：这些格式本身就是压缩格式。
        "mp3", "aac", "m4a", "ogg", "opus", "flac" -> true

        // 压缩包 / 基于 ZIP 的应用与文档格式。
        "zip", "7z", "rar", "gz", "tgz", "bz2", "xz", "zst",
        "apk", "aab", "jar", "epub", "docx", "xlsx", "pptx" -> true

        // PDF 内部通常已经包含压缩流；优先速度。
        "pdf" -> true

        else -> false
    }
}

private fun createDirectZipParameters(
    zipPath: String,
    password: String,
    isDirectory: Boolean,
    entrySize: Long
): ZipParameters {
    val parameters =
        ZipParameters()

    parameters.fileNameInZip =
        zipPath.replace(
            '\\',
            '/'
        )

    if (isDirectory) {
        parameters.compressionMethod =
            CompressionMethod.STORE

        parameters.isEncryptFiles =
            false

        parameters.entrySize =
            0L

        return parameters
    }

    val useStore =
        entrySize >= 0L &&
        shouldStoreWithoutCompression(
            zipPath
        )

    if (useStore) {
        // Zip4j 使用 ZipOutputStream + STORE 时，必须提前知道未压缩大小。
        parameters.compressionMethod =
            CompressionMethod.STORE

        parameters.entrySize =
            entrySize
    } else {
        parameters.compressionMethod =
            CompressionMethod.DEFLATE

        parameters.compressionLevel =
            CompressionLevel.NORMAL
    }

    // STORE 只是不做 DEFLATE；密码保护仍然照常使用 AES-256。
    if (password.isNotEmpty()) {
        parameters.isEncryptFiles =
            true

        parameters.encryptionMethod =
            EncryptionMethod.AES

        parameters.aesKeyStrength =
            AesKeyStrength.KEY_STRENGTH_256
    }

    return parameters
}

private fun sha256InputStream(
    input: InputStream
): String {
    val digest =
        MessageDigest
            .getInstance(
                "SHA-256"
            )

    val buffer =
        ByteArray(
            1024 * 1024
        )

    while (true) {
        val count =
            input.read(
                buffer
            )

        if (count == -1) {
            break
        }

        if (count > 0) {
            digest.update(
                buffer,
                0,
                count
            )
        }
    }

    return digestToHex(
        digest.digest()
    )
}

private fun digestToHex(
    digest: ByteArray
): String {
    return digest.joinToString(
        ""
    ) { byte ->
        "%02x".format(
            byte.toInt() and 0xff
        )
    }
}


private fun getDocumentSize(
    uri: Uri
): Long {
    return contentResolver
        .query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_SIZE
            ),
            null,
            null,
            null
        )
        ?.use { cursor ->

            val sizeIndex =
                cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_SIZE
                )

            if (
                sizeIndex >= 0 &&
                cursor.moveToFirst() &&
                !cursor.isNull(sizeIndex)
            ) {
                cursor.getLong(sizeIndex)
            } else {
                0L
            }
        }
        ?: 0L
}


private fun cleanupOldBackups(
    destinationUriString: String,
    maxBackupVersions: Int
) {
    if (maxBackupVersions <= 0) return

    val treeUri = Uri.parse(destinationUriString)

    val rootDocumentId =
        DocumentsContract.getTreeDocumentId(treeUri)

    val childrenUri =
        DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            rootDocumentId
        )

    val backups =
        mutableListOf<Triple<String, Uri, Long>>()

    contentResolver.query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        ),
        null,
        null,
        null
    )?.use { cursor ->

        val idIndex =
            cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID
            )

        val nameIndex =
            cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )

        val modifiedIndex =
            cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )

        while (cursor.moveToNext()) {

            val documentId =
                cursor.getString(idIndex)

            val name =
                cursor.getString(nameIndex) ?: continue

            val modified =
                if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                    cursor.getLong(modifiedIndex)
                } else {
                    0L
                }

            // 只处理 Backup Sender 自己生成的备份
            if (!name.startsWith("backup_")) {
                continue
            }

            val documentUri =
                DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    documentId
                )

            backups.add(
                Triple(
                    name,
                    documentUri,
                    modified
                )
            )
        }
    }

    if (backups.size <= maxBackupVersions) {
        return
    }

    // 最新的排前面
    backups.sortByDescending { it.first }

    // 超过保留数量的全部删除
    val oldBackups =
        backups.drop(maxBackupVersions)

    for (backup in oldBackups) {
        try {
            DocumentsContract.deleteDocument(
                contentResolver,
                backup.second
            )

            println(
                "删除旧备份：${backup.first}"
            )
        } catch (e: Exception) {
            println(
                "删除旧备份失败：${backup.first}，${e.message}"
            )
        }
    }
}
    // =============================================================
    // SAF 文件夹选择结果
    // =============================================================
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (
            requestCode !=
            pickFolderRequest
        ) {
            return
        }

        if (
            resultCode ==
            Activity.RESULT_OK
        ) {

            val uri =
                data?.data

            if (uri != null) {

                try {

                    val flags =
                        data.flags and
                        (
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )

                    contentResolver
                        .takePersistableUriPermission(
                            uri,
                            flags
                        )

                    pendingResult
                        ?.success(
                            uri.toString()
                        )

                } catch (e: Exception) {

                    pendingResult
                        ?.error(
                            "PERMISSION_FAILED",
                            e.message,
                            null
                        )
                }

            } else {

                pendingResult
                    ?.success(null)
            }

        } else {

            pendingResult
                ?.success(null)
        }

        pendingResult = null
    }


    // =============================================================
    // 获取 SAF 文件夹名称
    // =============================================================
    private fun getFolderName(
        treeUri: Uri
    ): String {

        val documentId =
            DocumentsContract
                .getTreeDocumentId(
                    treeUri
                )

        val documentUri =
            DocumentsContract
                .buildDocumentUriUsingTree(
                    treeUri,
                    documentId
                )

        var folderName =
            documentId
                .substringAfterLast(':')

        contentResolver.query(
            documentUri,
            arrayOf(
                DocumentsContract.Document
                    .COLUMN_DISPLAY_NAME
            ),
            null,
            null,
            null
        )?.use { cursor ->

            val nameIndex =
                cursor.getColumnIndex(
                    DocumentsContract.Document
                        .COLUMN_DISPLAY_NAME
                )

            if (
                cursor.moveToFirst() &&
                nameIndex >= 0
            ) {

                folderName =
                    cursor.getString(
                        nameIndex
                    )
            }
        }

        return folderName
    }


    // =============================================================
    // 递归读取 SAF 文件列表
    // =============================================================
    private fun scanDirectory(
        treeUri: Uri,
        parentDocumentId: String,
        relativePath: String,
        files: MutableList<Map<String, Any?>>
    ) {

        val childrenUri =
            DocumentsContract
                .buildChildDocumentsUriUsingTree(
                    treeUri,
                    parentDocumentId
                )

        val projection =
            arrayOf(
                DocumentsContract.Document
                    .COLUMN_DOCUMENT_ID,

                DocumentsContract.Document
                    .COLUMN_DISPLAY_NAME,

                DocumentsContract.Document
                    .COLUMN_MIME_TYPE,

                DocumentsContract.Document
                    .COLUMN_SIZE
            )

        contentResolver.query(
            childrenUri,
            projection,
            null,
            null,
            null
        )?.use { cursor ->

            val idIndex =
                cursor.getColumnIndex(
                    DocumentsContract.Document
                        .COLUMN_DOCUMENT_ID
                )

            val nameIndex =
                cursor.getColumnIndex(
                    DocumentsContract.Document
                        .COLUMN_DISPLAY_NAME
                )

            val mimeIndex =
                cursor.getColumnIndex(
                    DocumentsContract.Document
                        .COLUMN_MIME_TYPE
                )

            val sizeIndex =
                cursor.getColumnIndex(
                    DocumentsContract.Document
                        .COLUMN_SIZE
                )

            while (
                cursor.moveToNext()
            ) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("backup cancelled")
                }

                if (
                    idIndex < 0 ||
                    nameIndex < 0 ||
                    mimeIndex < 0
                ) {
                    continue
                }

                val documentId =
                    cursor.getString(
                        idIndex
                    )

                val name =
                    cursor.getString(
                        nameIndex
                    )

                val mimeType =
                    cursor.getString(
                        mimeIndex
                    )

                val currentPath =
                    if (
                        relativePath.isEmpty()
                    ) {
                        name
                    } else {
                        "$relativePath/$name"
                    }

                if (
                    mimeType ==
                    DocumentsContract.Document
                        .MIME_TYPE_DIR
                ) {

                    scanDirectory(
                        treeUri,
                        documentId,
                        currentPath,
                        files
                    )

                    continue
                }

                val documentUri =
                    DocumentsContract
                        .buildDocumentUriUsingTree(
                            treeUri,
                            documentId
                        )

                val size =
                    if (
                        sizeIndex >= 0 &&
                        !cursor.isNull(
                            sizeIndex
                        )
                    ) {

                        cursor.getLong(
                            sizeIndex
                        )

                    } else {

                        0L
                    }

                files.add(
                    mapOf(
                        "name" to name,
                        "path" to currentPath,
                        "uri" to
                                documentUri.toString(),
                        "size" to size,
                        "mimeType" to mimeType
                    )
                )
            }
        }
    }


    // =============================================================
    // SAF → Android App 临时目录
    // =============================================================
    private fun copyDirectoryToFileSystem(
        treeUri: Uri,
        parentDocumentId: String,
        targetDirectory: File
    ): Int {

        var copiedFiles = 0

        val childrenUri =
            DocumentsContract
                .buildChildDocumentsUriUsingTree(
                    treeUri,
                    parentDocumentId
                )

        val projection =
            arrayOf(
                DocumentsContract.Document
                    .COLUMN_DOCUMENT_ID,

                DocumentsContract.Document
                    .COLUMN_DISPLAY_NAME,

                DocumentsContract.Document
                    .COLUMN_MIME_TYPE
            )

        contentResolver.query(
            childrenUri,
            projection,
            null,
            null,
            null
        )?.use { cursor ->

            val idIndex =
                cursor.getColumnIndex(
                    DocumentsContract.Document
                        .COLUMN_DOCUMENT_ID
                )

            val nameIndex =
                cursor.getColumnIndex(
                    DocumentsContract.Document
                        .COLUMN_DISPLAY_NAME
                )

            val mimeIndex =
                cursor.getColumnIndex(
                    DocumentsContract.Document
                        .COLUMN_MIME_TYPE
                )

            while (
                cursor.moveToNext()
            ) {

                if (
                    idIndex < 0 ||
                    nameIndex < 0 ||
                    mimeIndex < 0
                ) {
                    continue
                }

                val documentId =
                    cursor.getString(
                        idIndex
                    )

                val name =
                    cursor.getString(
                        nameIndex
                    )

                val mimeType =
                    cursor.getString(
                        mimeIndex
                    )

                if (
                    mimeType ==
                    DocumentsContract.Document
                        .MIME_TYPE_DIR
                ) {

                    val childDirectory =
                        File(
                            targetDirectory,
                            name
                        )

                    childDirectory.mkdirs()

                    copiedFiles +=
                        copyDirectoryToFileSystem(
                            treeUri,
                            documentId,
                            childDirectory
                        )

                    continue
                }

                val documentUri =
                    DocumentsContract
                        .buildDocumentUriUsingTree(
                            treeUri,
                            documentId
                        )

                val outputFile =
                    File(
                        targetDirectory,
                        name
                    )

                val inputStream =
                    contentResolver
                        .openInputStream(
                            documentUri
                        )
                        ?: throw Exception(
                            "无法读取文件：$name"
                        )

                inputStream.use { input ->

                    FileOutputStream(
                        outputFile
                    ).use { output ->

                        input.copyTo(
                            output,
                            1024 * 1024
                        )
                    }
                }

                copiedFiles++
            }
        }

        return copiedFiles
    }


    // =============================================================
    // Android App 临时目录 → SAF 最终备份目录
    // =============================================================
    private fun copyFileSystemToSaf(
        sourceDirectory: File,
        targetDirectoryUri: Uri
    ): Int {

        var copiedFiles = 0

        val children =
            sourceDirectory.listFiles()
                ?: return 0

        for (child in children) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("backup cancelled")
            }

            // Media folders contain a marker that some SAF providers rewrite
            // during export. It is metadata rather than user content, so do
            // not let it make the whole uncompressed backup fail validation.
            if (child.name.equals(".nomedia", ignoreCase = true)) {
                continue
            }

            if (child.isDirectory) {

                val newDirectoryUri =
                    DocumentsContract
                        .createDocument(
                            contentResolver,
                            targetDirectoryUri,
                            DocumentsContract
                                .Document
                                .MIME_TYPE_DIR,
                            child.name
                        )
                        ?: throw Exception(
                            "无法创建目录：${child.name}"
                        )

                copiedFiles +=
                    copyFileSystemToSaf(
                        child,
                        newDirectoryUri
                    )

                continue
            }

            val mimeType =
                URLConnection
                    .guessContentTypeFromName(
                        child.name
                    )
                    ?: "application/octet-stream"

            val newFileUri =
                DocumentsContract
                    .createDocument(
                        contentResolver,
                        targetDirectoryUri,
                        mimeType,
                        child.name
                    )
                    ?: throw Exception(
                        "无法创建文件：${child.name}"
                    )

            val outputStream =
                contentResolver
                    .openOutputStream(
                        newFileUri,
                        "w"
                    )
                    ?: throw Exception(
                        "无法写入文件：${child.name}"
                    )

            FileInputStream(
    child
).use { input ->

    outputStream.use { output ->

        input.copyTo(
            output,
            1024 * 1024
        )
    }
}


// =========================
// SHA-256 校验
// =========================

val sourceHash =
    sha256File(child)

val targetHash =
    sha256Uri(newFileUri)

if (sourceHash != targetHash) {

    try {
        DocumentsContract.deleteDocument(
            contentResolver,
            newFileUri
        )
    } catch (_: Exception) {
    }

    throw Exception(
        "文件SHA-256校验失败：${child.name}"
    )
}


copiedFiles++
        }

        return copiedFiles
    }

    private fun copyPhysicalDirectoryToTemp(
    sourceDirectory: File,
    targetDirectory: File
): Int {

    var copiedFiles = 0

    if (!targetDirectory.exists()) {
        targetDirectory.mkdirs()
    }

    val children =
        sourceDirectory.listFiles()
            ?: return 0

    for (child in children) {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("backup cancelled")
        }

        val target =
            File(
                targetDirectory,
                child.name
            )

        if (child.isDirectory) {

            target.mkdirs()

            copiedFiles +=
                copyPhysicalDirectoryToTemp(
                    child,
                    target
                )

            continue
        }

        if (!child.isFile) {
            continue
        }

        val sourceDigest = MessageDigest.getInstance("SHA-256")
        val targetDigest = MessageDigest.getInstance("SHA-256")
        FileInputStream(child).use { rawInput ->
            DigestInputStream(rawInput, sourceDigest).use { input ->
                FileOutputStream(target).use { rawOutput ->
                    DigestOutputStream(rawOutput, targetDigest).use { output ->
                        input.copyTo(output, 1024 * 1024)
                    }
                }
            }
        }


        // ==========================================
        // 源文件 → 临时文件 SHA-256 校验
        // ==========================================

        val sourceHash = sourceDigest.digest().joinToString("") { "%02x".format(it) }
        val targetHash = targetDigest.digest().joinToString("") { "%02x".format(it) }

        if (
            sourceHash != targetHash
        ) {

            try {
                target.delete()
            } catch (_: Exception) {
            }

            throw Exception(
                "文件SHA-256校验失败：${child.absolutePath}"
            )
        }

        copiedFiles++
    }

    return copiedFiles
}
private fun sendBackupProgress(
    progress: Int,
    status: String,
    currentFile: String = "",
    processedFiles: Int = 0,
    totalFiles: Int = 0
) {

    runOnUiThread {

        methodChannel.invokeMethod(
            "backupProgress",
            mapOf(
                "progress" to progress,
                "status" to status,
                "currentFile" to currentFile,
                "processedFiles" to processedFiles,
                "totalFiles" to totalFiles
            )
        )
    }
}
}
