package com.example.backup_sender

import java.io.File
import java.io.RandomAccessFile
import net.sf.sevenzipjbinding.ArchiveFormat
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream

/** Check the produced header, not just the writer's requested encryption flag. */
internal object SevenZipPrivacy {
    fun verify(file: File, password: String, expectedEntries: Int) {
        require(password.isNotEmpty()) { "请先设置7z压缩密码" }
        check(!Thread.currentThread().isInterrupted) { "backup cancelled" }
        RandomAccessFile(file, "r").use { input ->
            val archive = SevenZip.openInArchive(
                ArchiveFormat.SEVEN_ZIP, RandomAccessFileInStream(input), password
            )
            try {
                check(archive.numberOfItems == expectedEntries) { "7z文件数量校验失败" }
            } finally {
                archive.close()
            }
        }
        var readableWithoutPassword = false
        RandomAccessFile(file, "r").use { input ->
            try {
                val archive = SevenZip.openInArchive(
                    ArchiveFormat.SEVEN_ZIP, RandomAccessFileInStream(input)
                )
                try {
                    archive.numberOfItems
                    readableWithoutPassword = true
                } finally {
                    archive.close()
                }
            } catch (_: SevenZipException) {
                // The header above was readable with the password. Failure
                // without a password is the required privacy boundary.
            }
        }
        check(!readableWithoutPassword) { "7z文件名加密校验失败，已阻止保存未加密的文件列表" }
    }
}
