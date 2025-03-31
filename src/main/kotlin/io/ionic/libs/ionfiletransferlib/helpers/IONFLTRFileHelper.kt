package io.ionic.libs.ionfiletransferlib.helpers

import android.webkit.MimeTypeMap
import io.ionic.libs.ionfiletransferlib.model.IONFLTRException
import java.io.File

class IONFLTRFileHelper {
    /**
     * Gets a MIME type based on the provided file path
     *
     * @param filePath The full path to file
     * @return The MIME type or null if it was unable to determine
     */
    fun getMimeType(filePath: String?): String? =
        MimeTypeMap.getFileExtensionFromUrl(filePath)?.let { extension ->
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
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
} 