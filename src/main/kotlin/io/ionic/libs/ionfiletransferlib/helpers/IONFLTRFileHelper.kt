package io.ionic.libs.ionfiletransferlib.helpers

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import io.ionic.libs.ionfiletransferlib.model.IONFLTRException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import androidx.core.net.toUri

internal class IONFLTRFileHelper(val contentResolver: ContentResolver) {
    /**
     * Gets relevant data for file transfer (namely, upload) based on the provided file path
     *
     * @param filePath the path or uri to the file
     * @return a [FileToUploadInfo] object
     */
    fun getFileToUploadInfo(filePath: String): FileToUploadInfo {
        return if (filePath.startsWith("content://")) {
            val uri = filePath.toUri()
            val cursor = contentResolver.query(uri, null, null, null, null)
                ?: throw IONFLTRException.FileDoesNotExist()
            cursor.use {
                val fileName = getNameForContentUri(cursor)
                    ?: throw IONFLTRException.FileDoesNotExist()
                val fileSize = getSizeForContentUri(cursor, uri)
                val inputStream = contentResolver.openInputStream(uri)
                    ?: throw IONFLTRException.FileDoesNotExist()
                FileToUploadInfo(fileName, fileSize, inputStream)
            }
        } else {
            val cleanFilePath = normalizeFilePath(filePath)
            val fileObject = File(cleanFilePath)
            if (!fileObject.exists()) {
                throw IONFLTRException.FileDoesNotExist()
            }
            FileToUploadInfo(fileObject.name, fileObject.length(), FileInputStream(fileObject))
        }
    }

    /**
     * Normalizes a file path by removing URI prefixes like "file://", "file:/", etc.
     *
     *
     * @param filePath The file path that might contain URI prefixes
     * @return Cleaned file path without URI prefixes
     */
    fun normalizeFilePath(filePath: String): String {
        return when {
            filePath.startsWith("file://") -> filePath.removePrefix("file://")
            filePath.startsWith("file:/") -> filePath.removePrefix("file:/")
            filePath.startsWith("file:") -> filePath.removePrefix("file:")
            else -> filePath
        }
    }

    /**
     * Gets a MIME type based on the provided file path
     *
     * @param filePath The full path to file
     * @return The MIME type or null if it was unable to determine
     */
    fun getMimeType(filePath: String?): String? =
        filePath?.let { normalizeFilePath(it) }?.let { normalizedPath ->
            MimeTypeMap.getFileExtensionFromUrl(normalizedPath)?.let { extension ->
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            }
        }

    /**
     * Creates parent directories for a file if they don't exist
     *
     * @param file The file to create parent directories for
     * @throws IONFLTRException.CannotCreateDirectory If the directories cannot be created
     */
    @Throws(IONFLTRException.CannotCreateDirectory::class)
    fun createParentDirectories(file: File) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            val created = parent.mkdirs()
            if (!created) {
                throw IONFLTRException.CannotCreateDirectory(parent.path)
            }
        }
    }

    /**
     * Gets the size of the that the content uri is pointing to.
     *
     * Will try to open the file and get its size if the android [Cursor] does not have the necessary column.
     *
     * @param cursor the android [Cursor] containing information about the uri
     * @param uri the content uri of the file, to try to open the file as a fallback if the cursor has no information
     * @return the size of the file, or 0 if it cannot be retrieved; throws exceptions in case file cannot be opened
     */
    private fun getSizeForContentUri(cursor: Cursor, uri: Uri): Long =
        cursor.getColumnIndex(OpenableColumns.SIZE).let { index ->
            if (index >= 0) {
                cursor.getString(index).toLongOrNull()
            } else {
                null
            }
        } ?: contentResolver.openAssetFileDescriptor(uri, "r")?.use {
            it.length
        } ?: 0L

    /**
     * Gets the name of a file in content uri
     *
     * @param cursor the android [Cursor] containing information about the uri
     * @return the name of the file, or null if no display name column was found
     */
    private fun getNameForContentUri(cursor: Cursor): String? {
        val columnIndex = cursor.getColumnIndexForNames(
            columnNames = listOf(
                OpenableColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
        )
        return columnIndex?.let { cursor.getString(columnIndex) }
    }

    private fun Cursor.getColumnIndexForNames(
        columnNames: List<String>
    ): Int? = columnNames.firstNotNullOfOrNull { getColumnIndex(it).takeIf { index -> index >= 0 } }
}

internal data class FileToUploadInfo(val name: String, val size: Long, val inputStream: InputStream)