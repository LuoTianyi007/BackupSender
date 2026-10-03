package com.example.backup_sender

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.security.MessageDigest
import java.util.Date
import java.util.Locale
import java.net.URLConnection
import net.sf.sevenzipjbinding.IOutCreateCallback
import net.sf.sevenzipjbinding.IOutItem7z
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.ISequentialInStream
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.InputStreamSequentialInStream
import net.sf.sevenzipjbinding.impl.OutItemFactory
import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream

@Volatile
private var workerSevenZipInitialized = false

class BackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        Log.i("BackupWorker", "WorkManager正式备份任务开始")

        val started = System.currentTimeMillis()
        val raw = applicationContext.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE).getString("flutter.backup_config", null)
        val config = try { raw?.let { org.json.JSONObject(it) } } catch (_: Exception) { null }
        if (config != null && (!config.optBoolean("autoBackup") || config.optString("schedule") != "daily")) return Result.success()
        val destinationUriString = config?.optString("backupPath") ?: inputData.getString("destinationUri") ?: ""
        val allFolders = config?.optJSONArray("folders")?.let { array -> (0 until array.length()).map { array.getString(it) } }
            ?: inputData.getStringArray("folders")?.toList() ?: emptyList()
        val disabled = config?.optJSONArray("disabledFolders")?.let { array -> (0 until array.length()).map { array.getString(it) }.toSet() } ?: emptySet()
        val folders = allFolders.filter { it !in disabled }
        if (folders.isEmpty()) return Result.success()
        val enableCompression = config?.optBoolean("enableCompression", true) ?: inputData.getBoolean("enableCompression", false)
        val maxBackupVersions = config?.optInt("maxBackupVersions", 5) ?: inputData.getInt("maxBackupVersions", 5)
        val password = config?.optString("zipPassword") ?: inputData.getString("password") ?: ""
        val customName = (config?.optString("archiveName") ?: "").trim()
            .removeSuffix(".zip")
            .removeSuffix(".7z")
            .map { if (it.code < 32 || it in "<>:\"/\\|?*") '_' else it }.joinToString("").trimEnd('.', ' ')
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(started))
        val backupName = "backup_$stamp" + if (customName.isEmpty()) "" else "_$customName"
        fun record(success: Boolean, error: String = "", size: Long = 0L) {
            try {
                BackupHistoryStore.add(applicationContext, org.json.JSONObject()
                    .put("time", started).put("finishedAt", System.currentTimeMillis())
                    .put(
                        "success",
                        success
                    ).put(
                        "name",
                        backupName + when {
                            !enableCompression -> ""
                            password.isNotEmpty() -> ".7z"
                            else -> ".zip"
                        }
                    )
                    .put("size", size)
                    .put("folders", org.json.JSONArray(folders)).put("error", error)
                    .put("source", "定时备份（第 ${runAttemptCount + 1} 次尝试）"))
            } catch (e: Exception) { Log.e("BackupWorker", "保存备份记录失败", e) }
        }
        if (destinationUriString.isBlank()) { record(false, "请先选择备份保存位置"); return Result.failure() }
        if (enableCompression && password.isEmpty()) {
            record(false, "启用7z压缩前请先设置密码，否则无法隐藏文件名")
            return Result.failure()
        }
        Log.i("BackupWorker", "Worker收到源文件夹数量：${folders.size}")
        Log.i("BackupWorker", "后台压缩设置：$enableCompression")
        Log.i("BackupWorker", "后台保留版本数量：$maxBackupVersions")
        Log.i("BackupWorker", "后台是否设置ZIP密码：${password.isNotEmpty()}")

        if (folders.isEmpty()) {
            Log.e("BackupWorker", "没有收到需要备份的源文件夹")
            return Result.failure()
        }

        // 这是配置错误，重试不会自行恢复，直接失败。
        if (folders.any { it == destinationUriString }) { record(false, "源文件夹不能和备份目录相同")
            Log.e(
                "BackupWorker",
                "源文件夹不能和备份保存目录相同"
            )
            return Result.failure()
        }

        Log.i(
            "BackupWorker",
            "当前执行次数：${runAttemptCount + 1}/$MAX_ATTEMPTS"
        )

        return try {
            Log.i("BackupWorker", "准备创建备份：$backupName")

            val backupSize: Long
            if (enableCompression) {
                Log.i("BackupWorker", "压缩模式已开启，开始7z备份")

                backupSize = createCompressedBackup(
                    folders = folders,
                    destinationUriString = destinationUriString,
                    backupName = backupName,
                    password = password
                )

                Log.i(
                    "BackupWorker",
                    "7z后台备份完成：$backupName.7z"
                )
            } else {
                backupSize = createNormalBackup(
                    folders = folders,
                    destinationUriString = destinationUriString,
                    backupName = backupName
                )
            }

            cleanupOldBackups(
                destinationUriString = destinationUriString,
                maxBackupVersions = maxBackupVersions
            )

            record(true, size = backupSize)
            Result.success()
        } catch (e: Throwable) {
            val failure = if (e is Exception) e else Exception(
                e.message ?: e.javaClass.simpleName,
                e
            )
            record(false, failure.message ?: failure.toString())
            retryOrFail(failure)
        }
    }

    // =============================================================
    // 后台失败处理：最多执行3次，前两次失败交给WorkManager退避重试
    // =============================================================
    private fun retryOrFail(e: Exception): Result {
        val currentAttempt = runAttemptCount + 1

        Log.e(
            "BackupWorker",
            "后台备份第${currentAttempt}次执行失败",
            e
        )

        return if (currentAttempt < MAX_ATTEMPTS) {
            Log.w(
                "BackupWorker",
                "将在WorkManager退避时间后自动重试；下一次为第${currentAttempt + 1}次"
            )
            Result.retry()
        } else {
            Log.e(
                "BackupWorker",
                "已达到最大重试次数：$MAX_ATTEMPTS，本次备份最终失败"
            )
            Result.failure()
        }
    }

    // =============================================================
    // 普通备份：SAF源目录 -> SAF目标目录 -> SHA-256逐文件校验
    // =============================================================
private fun createNormalBackup(
    folders: List<String>,
    destinationUriString: String,
    backupName: String
): Long {
        val resolver = applicationContext.contentResolver

        val destinationTreeUri = Uri.parse(destinationUriString)
        val destinationRootId =
            DocumentsContract.getTreeDocumentId(destinationTreeUri)
        val destinationRootUri =
            DocumentsContract.buildDocumentUriUsingTree(
                destinationTreeUri,
                destinationRootId
            )

        val backupDirectoryUri =
            DocumentsContract.createDocument(
                resolver,
                destinationRootUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                backupName
            ) ?: throw Exception("无法创建备份目录")

        try {
            var totalFiles = 0

            folders.forEachIndexed { index, sourceUriString ->
                Log.i(
                    "BackupWorker",
                    "正在备份源目录 ${index + 1}"
                )

               var copied = 0
var numberedFolderName = ""

// ==========================================
// SAF目录：content://...
// ==========================================
if (
    sourceUriString.startsWith(
        "content://"
    )
) {

    val sourceTreeUri =
        Uri.parse(sourceUriString)

    val sourceRootId =
        DocumentsContract
            .getTreeDocumentId(
                sourceTreeUri
            )

    val sourceFolderName =
        getFolderName(
            sourceTreeUri,
            sourceRootId
        )

    numberedFolderName =
        "${index + 1}_$sourceFolderName"

    val targetFolderUri =
        DocumentsContract
            .createDocument(
                resolver,
                backupDirectoryUri,
                DocumentsContract.Document
                    .MIME_TYPE_DIR,
                numberedFolderName
            )
            ?: throw Exception(
                "无法创建目录：$numberedFolderName"
            )

    copied =
        copyDirectory(
            sourceTreeUri,
            sourceRootId,
            targetFolderUri
        )

    // SAF源目录严格校验
    val targetFolderDocumentId =
        DocumentsContract
            .getDocumentId(
                targetFolderUri
            )

    Log.i(
        "BackupWorker",
        "开始校验普通备份：$numberedFolderName"
    )

    verifySafDirectoriesBySha256(
        sourceTreeUri = sourceTreeUri,
        sourceParentDocumentId =
            sourceRootId,
        targetTreeUri =
            destinationTreeUri,
        targetParentDocumentId =
            targetFolderDocumentId
    )

    Log.i(
        "BackupWorker",
        "普通备份SHA-256校验通过：$numberedFolderName"
    )

} else {

    // ==========================================
    // 普通路径：
    // /storage/emulated/0/Download
    // ==========================================

    val sourceDirectory =
        File(sourceUriString)

    if (!sourceDirectory.exists()) {
        throw Exception(
            "源目录不存在：$sourceUriString"
        )
    }

    if (!sourceDirectory.isDirectory) {
        throw Exception(
            "源路径不是文件夹：$sourceUriString"
        )
    }

    if (!sourceDirectory.canRead()) {
        throw Exception(
            "无法读取源目录，请检查“所有文件访问权限”：$sourceUriString"
        )
    }

    val sourceFolderName =
        sourceDirectory.name.ifBlank {
            "Download"
        }

    numberedFolderName =
        "${index + 1}_$sourceFolderName"

    val targetFolderUri =
        DocumentsContract
            .createDocument(
                resolver,
                backupDirectoryUri,
                DocumentsContract.Document
                    .MIME_TYPE_DIR,
                numberedFolderName
            )
            ?: throw Exception(
                "无法创建目录：$numberedFolderName"
            )

    Log.i(
        "BackupWorker",
        "开始备份普通路径：$sourceUriString"
    )

    copied =
        copyPhysicalDirectoryToSaf(
            sourceDirectory,
            targetFolderUri
        )

    Log.i(
        "BackupWorker",
        "普通路径SHA-256校验通过：$numberedFolderName"
    )
}

totalFiles += copied

Log.i(
    "BackupWorker",
    "$numberedFolderName 复制完成，共 $copied 个文件"
)
            }

            Log.i(
                "BackupWorker",
                "普通后台备份完成并校验通过：$backupName，共 $totalFiles 个文件"
            )
            val backupTreeUri = Uri.parse(destinationUriString)
            val backupDocumentId = DocumentsContract.getDocumentId(backupDirectoryUri)
            return collectSafEntries(
                treeUri = backupTreeUri,
                parentDocumentId = backupDocumentId
            ).values
                .filter { !it.isDirectory && it.size >= 0L }
                .sumOf { it.size }
        } catch (e: Exception) {
            // 任何一个文件校验失败，都删除本次不完整/不可信的备份目录。
            try {
                DocumentsContract.deleteDocument(
                    resolver,
                    backupDirectoryUri
                )
                Log.e(
                    "BackupWorker",
                    "普通备份失败，已删除本次备份：$backupName"
                )
            } catch (_: Exception) {
            }
            throw e
        }
    }

    private data class SafEntryInfo(
        val uri: Uri,
        val isDirectory: Boolean,
        val size: Long
    )

    // =============================================================
    // 普通备份严格校验：SAF源目录 vs SAF目标目录
    // 相对路径、目录结构、文件大小、SHA-256 必须全部一致
    // =============================================================
    private fun verifySafDirectoriesBySha256(
        sourceTreeUri: Uri,
        sourceParentDocumentId: String,
        targetTreeUri: Uri,
        targetParentDocumentId: String
    ) {
        val sourceEntries =
            collectSafEntries(
                treeUri = sourceTreeUri,
                parentDocumentId = sourceParentDocumentId
            )

        val targetEntries =
            collectSafEntries(
                treeUri = targetTreeUri,
                parentDocumentId = targetParentDocumentId
            )

        if (sourceEntries.keys != targetEntries.keys) {
            val missing = sourceEntries.keys - targetEntries.keys
            val extra = targetEntries.keys - sourceEntries.keys

            throw Exception(
                "普通备份校验失败：目录结构不一致，" +
                    "缺少=${missing.joinToString(limit = 5)}，" +
                    "多出=${extra.joinToString(limit = 5)}"
            )
        }

        for ((relativePath, sourceEntry) in sourceEntries) {
            val targetEntry = targetEntries[relativePath]
                ?: throw Exception(
                    "普通备份校验失败：缺少 $relativePath"
                )

            if (sourceEntry.isDirectory != targetEntry.isDirectory) {
                throw Exception(
                    "普通备份校验失败：文件类型不一致 $relativePath"
                )
            }

            if (sourceEntry.isDirectory) {
                continue
            }

            if (
                sourceEntry.size >= 0L &&
                targetEntry.size >= 0L &&
                sourceEntry.size != targetEntry.size
            ) {
                throw Exception(
                    "普通备份校验失败：文件大小不一致 $relativePath"
                )
            }

            val sourceHash = sha256Uri(sourceEntry.uri)
            val targetHash = sha256Uri(targetEntry.uri)

            if (sourceHash != targetHash) {
                throw Exception(
                    "普通备份校验失败：SHA-256不一致 $relativePath"
                )
            }
        }
    }

    private fun collectSafEntries(
        treeUri: Uri,
        parentDocumentId: String,
        prefix: String = ""
    ): Map<String, SafEntryInfo> {
        val resolver = applicationContext.contentResolver
        val result = linkedMapOf<String, SafEntryInfo>()

        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(
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

        resolver.query(
            childrenUri,
            projection,
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
            val mimeIndex =
                cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                )
            val sizeIndex =
                cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_SIZE
                )

            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex) ?: continue
                val mimeType =
                    cursor.getString(mimeIndex)
                        ?: "application/octet-stream"

                val relativePath =
                    if (prefix.isEmpty()) {
                        name
                    } else {
                        "$prefix/$name"
                    }

                val documentUri =
                    DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        documentId
                    )

                val isDirectory =
                    mimeType ==
                        DocumentsContract.Document.MIME_TYPE_DIR

                val size =
                    if (
                        !isDirectory &&
                        sizeIndex >= 0 &&
                        !cursor.isNull(sizeIndex)
                    ) {
                        cursor.getLong(sizeIndex)
                    } else {
                        -1L
                    }

                result[relativePath] =
                    SafEntryInfo(
                        uri = documentUri,
                        isDirectory = isDirectory,
                        size = size
                    )

                if (isDirectory) {
                    result.putAll(
                        collectSafEntries(
                            treeUri = treeUri,
                            parentDocumentId = documentId,
                            prefix = relativePath
                        )
                    )
                }
            }
        }

        return result
    }

private data class WorkerSevenZipEntry(
    val path: String,
    val file: File,
    val isDirectory: Boolean
)

private fun createSevenZipFromDirectory(
    root: File,
    output: File,
    password: String
) {
    val entries = root.walkTopDown()
        .filter { it != root }
        .map {
            WorkerSevenZipEntry(
                path = root.toPath().relativize(it.toPath()).toString()
                    .replace(File.separatorChar, '/'),
                file = it,
                isDirectory = it.isDirectory
            )
        }
        .toList()

    RandomAccessFile(output, "rw").use { randomAccessFile ->
        ensureWorkerSevenZipInitialized()
        val archive = SevenZip.openOutArchive7z()
        try {
            // MT/solid setters reset HE in this binding. Keep HE as the only
            // native property operation, matching foreground backups.
            archive.setHeaderEncryption(true)
            val callback = object : IOutCreateCallback<IOutItem7z>,
                ICryptoGetTextPassword {
                override fun cryptoGetTextPassword(): String = password
                override fun setTotal(total: Long) {}
                override fun setCompleted(complete: Long) {
                    if (Thread.currentThread().isInterrupted) {
                        throw SevenZipException("backup cancelled")
                    }
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
                    if (Thread.currentThread().isInterrupted) {
                        throw SevenZipException("backup cancelled")
                    }
                    val entry = entries[index]
                    val item = outItemFactory.createOutItem()
                    item.setPropertyPath(entry.path)
                    if (entry.isDirectory) {
                        item.setPropertyIsDir(true)
                    } else {
                        item.setDataSize(entry.file.length())
                    }
                    return item
                }
                override fun getStream(index: Int): ISequentialInStream? {
                    val entry = entries[index]
                    if (entry.isDirectory) return null
                    return InputStreamSequentialInStream(
                        InterruptAwareWorkerInputStream(FileInputStream(entry.file))
                    )
                }
            }
            synchronized(SevenZip::class.java) {
                archive.createArchive(
                    RandomAccessFileOutStream(randomAccessFile),
                    entries.size,
                    callback
                )
            }
        } finally {
            archive.close()
        }
    }
    SevenZipPrivacy.verify(output, password, entries.size)
}

private class InterruptAwareWorkerInputStream(
    private val delegate: FileInputStream
) : java.io.InputStream() {
    override fun read(): Int {
        checkCancelled()
        return delegate.read()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkCancelled()
        return delegate.read(buffer, offset, length)
    }

    override fun close() {
        delegate.close()
    }

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) {
            throw java.io.IOException("backup cancelled")
        }
    }
}

@Synchronized
private fun ensureWorkerSevenZipInitialized() {
    if (workerSevenZipInitialized) {
        return
    }
    try {
        val nativeLibrary = File(
            applicationContext.applicationInfo.nativeLibraryDir,
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
        workerSevenZipInitialized = true
    } catch (t: Throwable) {
        throw IllegalStateException("7z引擎初始化失败：${t.message}", t)
    }
}

private fun extractWorkerSevenZipLibraryFromApk(): File {
    val output = File(applicationContext.cacheDir, "lib7-Zip-JBinding.so")
    if (output.exists() && output.length() > 0L) return output
    val apk = java.util.zip.ZipFile(applicationContext.applicationInfo.sourceDir)
    apk.use { zip ->
        var entry = zip.getEntry(
            "lib/${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"}/lib7-Zip-JBinding.so"
        )
        if (entry == null) {
            for (abi in android.os.Build.SUPPORTED_ABIS) {
                entry = zip.getEntry("lib/$abi/lib7-Zip-JBinding.so")
                if (entry != null) break
            }
        }
        val selected = entry ?: throw IllegalStateException(
            "APK中没有找到7z native库，ABI=${android.os.Build.SUPPORTED_ABIS.joinToString()}"
        )
        zip.getInputStream(selected).use { input ->
            FileOutputStream(output).use { outputStream ->
                input.copyTo(outputStream, 1024 * 1024)
            }
        }
    }
    return output
}

    // =============================================================
    // 压缩备份：SAF源目录 -> App临时目录 -> 7z -> SAF目标目录
    // =============================================================
private fun createCompressedBackup(
    folders: List<String>,
    destinationUriString: String,
    backupName: String,
    password: String
): Long {

    val resolver =
        applicationContext.contentResolver


    // =====================================
    // 临时源文件目录
    // =====================================

    val tempRoot =
        File(
            applicationContext.cacheDir,
            "worker_temp_backup"
        )


    // =====================================
    // ZIP严格校验解压目录
    // =====================================

    val verifyRoot =
        File(
            applicationContext.cacheDir,
            "worker_verify_backup"
        )


    // =====================================
    // 临时ZIP
    // =====================================

    val zipOutput =
        File(
            applicationContext.cacheDir,
            "worker_$backupName.${if (password.isNotEmpty()) "7z" else "zip"}"
        )


    var finalZipUri: Uri? = null


    try {

        // =====================================
        // 清理上一次异常留下的缓存
        // =====================================

        if (tempRoot.exists()) {
            tempRoot.deleteRecursively()
        }

        if (verifyRoot.exists()) {
            verifyRoot.deleteRecursively()
        }

        if (zipOutput.exists()) {
            zipOutput.delete()
        }


        tempRoot.mkdirs()
        verifyRoot.mkdirs()


        // =====================================
        // 所有源目录复制到临时目录
        // =====================================

        folders.forEachIndexed {
                index,
                sourceUriString ->

            var copiedFiles = 0
            var folderName = "folder"


            // =================================
            // SAF目录
            // content://...
            // =================================

            if (
                sourceUriString.startsWith(
                    "content://"
                )
            ) {

                val treeUri =
                    Uri.parse(
                        sourceUriString
                    )

                val rootDocumentId =
                    DocumentsContract
                        .getTreeDocumentId(
                            treeUri
                        )

                folderName =
                    getFolderName(
                        treeUri,
                        rootDocumentId
                    )


                val targetDirectory =
                    File(
                        tempRoot,
                        "${index + 1}_$folderName"
                    )

                targetDirectory.mkdirs()


                copiedFiles =
                    copySafToFileSystem(
                        treeUri,
                        rootDocumentId,
                        targetDirectory
                    )


                Log.i(
                    "BackupWorker",
                    "SAF压缩临时目录准备完成：" +
                        "${targetDirectory.name}，" +
                        "共 $copiedFiles 个文件"
                )

            } else {

                // =================================
                // 普通物理路径
                // 例如 Download
                // =================================

                val sourceDirectory =
                    File(
                        sourceUriString
                    )


                if (!sourceDirectory.exists()) {

                    throw Exception(
                        "源目录不存在：$sourceUriString"
                    )
                }


                if (!sourceDirectory.isDirectory) {

                    throw Exception(
                        "源路径不是文件夹：$sourceUriString"
                    )
                }


                if (!sourceDirectory.canRead()) {

                    throw Exception(
                        "无法读取源目录，请检查所有文件访问权限：" +
                            sourceUriString
                    )
                }


                folderName =
                    sourceDirectory
                        .name
                        .ifBlank {
                            "Download"
                        }


                val targetDirectory =
                    File(
                        tempRoot,
                        "${index + 1}_$folderName"
                    )

                targetDirectory.mkdirs()


                copiedFiles =
                    copyPhysicalDirectoryToFileSystem(
                        sourceDirectory,
                        targetDirectory
                    )


                Log.i(
                    "BackupWorker",
                    "普通路径压缩临时目录准备完成：" +
                        "${targetDirectory.name}，" +
                        "共 $copiedFiles 个文件"
                )
            }
        }


        if (password.isNotEmpty()) {
            // 7z header encryption hides entry names; ZIP encryption does not.
            createSevenZipFromDirectory(tempRoot, zipOutput, password)
        } else {
            val zipFile = net.lingala.zip4j.ZipFile(zipOutput)
            val parameters = net.lingala.zip4j.model.ZipParameters().apply {
                compressionMethod = net.lingala.zip4j.model.enums.CompressionMethod.DEFLATE
                compressionLevel = net.lingala.zip4j.model.enums.CompressionLevel.NORMAL
            }
            tempRoot.listFiles()?.forEach { child ->
                if (child.isDirectory) zipFile.addFolder(child, parameters)
                else zipFile.addFile(child, parameters)
            }
        }
        if (!zipOutput.exists() || zipOutput.length() <= 0L) {
            throw Exception("压缩文件创建失败")
        }
        Log.i("BackupWorker", "压缩文件创建成功：${zipOutput.length()} 字节")


        // =====================================
        // 7z写入用户选择的SAF目录
        // =====================================

        val localZipHash =
            sha256File(
                zipOutput
            )


        val destinationTreeUri =
            Uri.parse(
                destinationUriString
            )


        val destinationRootId =
            DocumentsContract
                .getTreeDocumentId(
                    destinationTreeUri
                )


        val destinationRootUri =
            DocumentsContract
                .buildDocumentUriUsingTree(
                    destinationTreeUri,
                    destinationRootId
                )


        finalZipUri =
            DocumentsContract
                .createDocument(
                    resolver,
                    destinationRootUri,
                    if (password.isNotEmpty()) "application/x-7z-compressed" else "application/zip",
                    "$backupName.${if (password.isNotEmpty()) "7z" else "zip"}"
                )
                ?: throw Exception(
                    "无法创建最终压缩文件"
                )


        val outputStream =
            resolver.openOutputStream(
                finalZipUri,
                "w"
            )
                ?: throw Exception(
                "无法写入最终压缩文件"
                )


        FileInputStream(
            zipOutput
        ).use { input ->

            outputStream.use { output ->

                input.copyTo(
                    output,
                    1024 * 1024
                )
            }
        }


        // =====================================
        // 最终ZIP再次SHA-256校验
        // =====================================

        val finalZipHash =
            sha256Uri(
                finalZipUri
            )


        if (
            localZipHash !=
            finalZipHash
        ) {

            throw Exception(
                "最终压缩文件校验失败：" +
                    "写入备份目录后的SHA-256不一致"
            )
        }


        Log.i(
            "BackupWorker",
            "最终压缩文件 SHA-256校验通过"
        )


        Log.i(
            "BackupWorker",
            "压缩文件已写入最终备份目录：$backupName.${if (password.isNotEmpty()) "7z" else "zip"}"
        )
        return finalZipUri?.let { getDocumentSize(it) } ?: 0L


    } catch (e: Exception) {

        // =====================================
        // 如果最终ZIP已经创建，
        // 但中途发生错误，
        // 删除可能损坏的不完整ZIP
        // =====================================

        finalZipUri?.let { uri ->

            try {

                DocumentsContract
                    .deleteDocument(
                        resolver,
                        uri
                    )

            } catch (_: Exception) {
            }
        }


        throw e


    } finally {

        // =====================================
        // 无论成功、失败、校验失败
        // 都清理App缓存
        // =====================================

        try {

            if (zipOutput.exists()) {

                val deleted =
                    zipOutput.delete()

                Log.i(
                    "BackupWorker",
                    "临时7z清理：$deleted"
                )
            }

        } catch (e: Exception) {

            Log.e(
                "BackupWorker",
                "临时7z清理失败",
                e
            )
        }


        try {

            if (tempRoot.exists()) {

                val deleted =
                    tempRoot
                        .deleteRecursively()

                Log.i(
                    "BackupWorker",
                    "worker_temp_backup清理：$deleted"
                )
            }

        } catch (e: Exception) {

            Log.e(
                "BackupWorker",
                "worker_temp_backup清理失败",
                e
            )
        }


        try {

            if (verifyRoot.exists()) {

                val deleted =
                    verifyRoot
                        .deleteRecursively()

                Log.i(
                    "BackupWorker",
                    "worker_verify_backup清理：$deleted"
                )
            }

        } catch (e: Exception) {

            Log.e(
                "BackupWorker",
                "worker_verify_backup清理失败",
                e
            )
        }
    }
}

private fun getDocumentSize(uri: Uri): Long {
    return applicationContext.contentResolver.query(
        uri,
        arrayOf(DocumentsContract.Document.COLUMN_SIZE),
        null,
        null,
        null
    )?.use { cursor ->
        val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
            cursor.getLong(index)
        } else {
            0L
        }
    } ?: 0L
}




    // =============================================================
    // ZIP严格校验：解压结果 vs 压缩前目录
    // 文件数量、相对路径、大小、SHA-256 必须全部一致
    // =============================================================
    private fun verifyDirectoriesBySha256(
        sourceRoot: File,
        verifyRoot: File
    ) {
        val sourceFiles = collectFilesByRelativePath(sourceRoot)
        val verifyFiles = collectFilesByRelativePath(verifyRoot)

        if (sourceFiles.size != verifyFiles.size) {
            throw Exception(
                "ZIP校验失败：文件数量不一致，" +
                    "源=${sourceFiles.size}，ZIP=${verifyFiles.size}"
            )
        }

        for ((relativePath, sourceFile) in sourceFiles) {
            val verifyFile = verifyFiles[relativePath]
                ?: throw Exception(
                    "ZIP校验失败：缺少文件 $relativePath"
                )

            if (sourceFile.length() != verifyFile.length()) {
                throw Exception(
                    "ZIP校验失败：文件大小不一致 $relativePath"
                )
            }

            val sourceHash = sha256File(sourceFile)
            val verifyHash = sha256File(verifyFile)

            if (sourceHash != verifyHash) {
                throw Exception(
                    "ZIP校验失败：SHA-256不一致 $relativePath"
                )
            }
        }
    }

    private fun collectFilesByRelativePath(
        root: File
    ): Map<String, File> {
        val result = linkedMapOf<String, File>()

        if (!root.exists()) {
            return result
        }

        root.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relativePath =
                    file.relativeTo(root)
                        .path
                        .replace(File.separatorChar, '/')

                result[relativePath] = file
            }

        return result
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)

        FileInputStream(file).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }

        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun sha256Uri(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)

        val input =
            applicationContext.contentResolver.openInputStream(uri)
                ?: throw Exception("无法读取文件进行SHA-256校验")

        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }

        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    // =============================================================
    // SAF源目录 -> Android App临时文件系统
    // =============================================================
    private fun copySafToFileSystem(
        treeUri: Uri,
        parentDocumentId: String,
        targetDirectory: File
    ): Int {
        val resolver = applicationContext.contentResolver
        var copiedFiles = 0

        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                parentDocumentId
            )

        val projection =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )

        resolver.query(
            childrenUri,
            projection,
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
            val mimeIndex =
                cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                )

            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex) ?: continue
                val mimeType =
                    cursor.getString(mimeIndex)
                        ?: "application/octet-stream"

                if (
                    mimeType ==
                    DocumentsContract.Document.MIME_TYPE_DIR
                ) {
                    val childDirectory =
                        File(targetDirectory, name)
                    childDirectory.mkdirs()

                    copiedFiles +=
                        copySafToFileSystem(
                            treeUri,
                            documentId,
                            childDirectory
                        )
                } else {
                    val sourceUri =
                        DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            documentId
                        )

                    val outputFile =
                        File(targetDirectory, name)

                    val input =
                        resolver.openInputStream(sourceUri)
                            ?: throw Exception(
                                "无法读取文件：$name"
                            )

                    input.use { inputStream ->
                        FileOutputStream(outputFile).use { output ->
                            inputStream.copyTo(
                                output,
                                1024 * 1024
                            )
                        }
                    }

                    copiedFiles++
                }
            }
        }

        return copiedFiles
    }

    // =============================================================
    // 获取 SAF 文件夹名称
    // =============================================================
    private fun getFolderName(
        treeUri: Uri,
        documentId: String
    ): String {
        val resolver = applicationContext.contentResolver

        val documentUri =
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                documentId
            )

        var folderName =
            documentId.substringAfterLast(':')

        if (folderName.isBlank()) {
            folderName = "folder"
        }

        resolver.query(
            documentUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val nameIndex =
                cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                )

            if (cursor.moveToFirst() && nameIndex >= 0) {
                folderName =
                    cursor.getString(nameIndex) ?: folderName
            }
        }

        return folderName
    }

    // =============================================================
    // SAF源目录 -> SAF目标目录
    // =============================================================
    private fun copyDirectory(
        sourceTreeUri: Uri,
        sourceParentDocumentId: String,
        targetParentUri: Uri
    ): Int {
        val resolver = applicationContext.contentResolver

        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                sourceTreeUri,
                sourceParentDocumentId
            )

        val projection =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )

        var copiedFiles = 0

        resolver.query(
            childrenUri,
            projection,
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
            val mimeIndex =
                cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                )

            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex) ?: continue
                val mimeType =
                    cursor.getString(mimeIndex)
                        ?: "application/octet-stream"

                if (
                    mimeType ==
                    DocumentsContract.Document.MIME_TYPE_DIR
                ) {
                    val targetDirectoryUri =
                        DocumentsContract.createDocument(
                            resolver,
                            targetParentUri,
                            DocumentsContract.Document.MIME_TYPE_DIR,
                            name
                        ) ?: throw Exception(
                            "无法创建目录：$name"
                        )

                    copiedFiles +=
                        copyDirectory(
                            sourceTreeUri,
                            documentId,
                            targetDirectoryUri
                        )
                } else {
                    val sourceFileUri =
                        DocumentsContract.buildDocumentUriUsingTree(
                            sourceTreeUri,
                            documentId
                        )

                    val targetFileUri =
                        DocumentsContract.createDocument(
                            resolver,
                            targetParentUri,
                            mimeType,
                            name
                        ) ?: throw Exception(
                            "无法创建文件：$name"
                        )

                    val inputStream =
                        resolver.openInputStream(sourceFileUri)
                            ?: throw Exception(
                                "无法读取文件：$name"
                            )

                    val outputStream =
                        resolver.openOutputStream(
                            targetFileUri,
                            "w"
                        ) ?: throw Exception(
                            "无法写入文件：$name"
                        )

                    inputStream.use { input ->
                        outputStream.use { output ->
                            input.copyTo(
                                output,
                                1024 * 1024
                            )
                        }
                    }

                    copiedFiles++
                }
            }
        }

        return copiedFiles
    }

    // =============================================================
    // 清理旧备份：同时支持 backup_xxx 文件夹和 backup_xxx.zip
    // =============================================================
    private fun cleanupOldBackups(
        destinationUriString: String,
        maxBackupVersions: Int
    ) {
        if (maxBackupVersions <= 0) return

        val resolver = applicationContext.contentResolver
        val treeUri = Uri.parse(destinationUriString)
        val rootDocumentId =
            DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                rootDocumentId
            )

        val backups =
            mutableListOf<Pair<String, Uri>>()

        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
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

            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex) ?: continue

                if (!name.startsWith("backup_")) {
                    continue
                }

                val documentUri =
                    DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        documentId
                    )

                backups.add(name to documentUri)
            }
        }

        if (backups.size <= maxBackupVersions) {
            return
        }

        // 文件名包含 yyyyMMdd_HHmmss，因此按名称降序就是按时间从新到旧。
        backups.sortByDescending { it.first }

        for (backup in backups.drop(maxBackupVersions)) {
            try {
                DocumentsContract.deleteDocument(
                    resolver,
                    backup.second
                )
                Log.i(
                    "BackupWorker",
                    "删除旧备份：${backup.first}"
                )
            } catch (e: Exception) {
                Log.e(
                    "BackupWorker",
                    "删除旧备份失败：${backup.first}",
                    e
                )
            }
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
    }
    private fun copyPhysicalDirectoryToSaf(
    sourceDirectory: File,
    targetDirectoryUri: Uri
): Int {

    val resolver =
        applicationContext.contentResolver

    var copiedFiles = 0

    val children =
        sourceDirectory.listFiles()
            ?: throw Exception(
                "无法读取目录：${sourceDirectory.absolutePath}"
            )

    for (child in children) {

        // ==========================================
        // 子目录
        // ==========================================

        if (child.isDirectory) {

            val childDirectoryUri =
                DocumentsContract
                    .createDocument(
                        resolver,
                        targetDirectoryUri,
                        DocumentsContract.Document
                            .MIME_TYPE_DIR,
                        child.name
                    )
                    ?: throw Exception(
                        "无法创建目录：${child.name}"
                    )

            copiedFiles +=
                copyPhysicalDirectoryToSaf(
                    child,
                    childDirectoryUri
                )

            continue
        }

        if (!child.isFile) {
            continue
        }


        // ==========================================
        // 普通文件
        // ==========================================

        val mimeType =
            URLConnection
                .guessContentTypeFromName(
                    child.name
                )
                ?: "application/octet-stream"

        val targetFileUri =
            DocumentsContract
                .createDocument(
                    resolver,
                    targetDirectoryUri,
                    mimeType,
                    child.name
                )
                ?: throw Exception(
                    "无法创建文件：${child.name}"
                )

        val outputStream =
            resolver.openOutputStream(
                targetFileUri,
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


        // ==========================================
        // SHA-256校验
        // ==========================================

        val sourceHash =
            sha256File(child)

        val targetHash =
            sha256Uri(
                targetFileUri
            )

        if (sourceHash != targetHash) {

            try {
                DocumentsContract
                    .deleteDocument(
                        resolver,
                        targetFileUri
                    )
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


private fun copyPhysicalDirectoryToFileSystem(
    sourceDirectory: File,
    targetDirectory: File
): Int {

    var copiedFiles = 0

    if (!targetDirectory.exists()) {
        targetDirectory.mkdirs()
    }

    val children =
        sourceDirectory.listFiles()
            ?: throw Exception(
                "无法读取目录：${sourceDirectory.absolutePath}"
            )

    for (child in children) {

        val target =
            File(
                targetDirectory,
                child.name
            )

        if (child.isDirectory) {

            target.mkdirs()

            copiedFiles +=
                copyPhysicalDirectoryToFileSystem(
                    child,
                    target
                )

            continue
        }

        if (!child.isFile) {
            continue
        }

        FileInputStream(
            child
        ).use { input ->

            FileOutputStream(
                target
            ).use { output ->

                input.copyTo(
                    output,
                    1024 * 1024
                )
            }
        }

        // 源文件 → 临时文件 SHA-256
        val sourceHash =
            sha256File(
                child
            )

        val targetHash =
            sha256File(
                target
            )

        if (sourceHash != targetHash) {

            try {
                target.delete()
            } catch (_: Exception) {
            }

            throw Exception(
                "临时文件SHA-256校验失败：${child.absolutePath}"
            )
        }

        copiedFiles++
    }

    return copiedFiles
}
}
