package io.ionic.libs.ionfiletransferlib

import io.ionic.libs.ionfiletransferlib.helpers.IONFLTRConnectionHelper
import io.ionic.libs.ionfiletransferlib.helpers.IONFLTRFileHelper
import io.ionic.libs.ionfiletransferlib.helpers.IONFLTRInputsValidator
import io.ionic.libs.ionfiletransferlib.helpers.runCatchingIONFLTRExceptions
import io.ionic.libs.ionfiletransferlib.model.IONFLTRDownloadOptions
import io.ionic.libs.ionfiletransferlib.model.IONFLTRException
import io.ionic.libs.ionfiletransferlib.model.IONFLTRProgressStatus
import io.ionic.libs.ionfiletransferlib.model.IONFLTRTransferComplete
import io.ionic.libs.ionfiletransferlib.model.IONFLTRTransferResult
import io.ionic.libs.ionfiletransferlib.model.IONFLTRUploadOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Entry point in IONFileTransferLib-Android
 *
 * Contains relevant methods for downloading and uploading files in Android.
 */
class IONFLTRController internal constructor(
    private val inputsValidator: IONFLTRInputsValidator,
    private val fileHelper: IONFLTRFileHelper,
    private val connectionHelper: IONFLTRConnectionHelper
) {
    constructor() : this(
        inputsValidator = IONFLTRInputsValidator(),
        fileHelper = IONFLTRFileHelper(),
        connectionHelper = IONFLTRConnectionHelper()
    )

    companion object {
        private const val BUFFER_SIZE = 8192 // 8KB buffer size
        private const val BOUNDARY = "----IONFLTRBoundary"
    }

    /**
     * Writes a file to an output stream and emits progress updates.
     *
     * @param file The file to write
     * @param outputStream The output stream to write to
     * @param totalBytesWritten The current total bytes written
     * @param totalSize The total size to report in progress updates
     * @param emit Function to emit progress updates
     */
    private suspend fun writeFileWithProgress(
        file: File,
        outputStream: BufferedOutputStream,
        totalBytesWritten: Long,
        totalSize: Long,
        emit: suspend (IONFLTRTransferResult) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        var currentTotalBytes = totalBytesWritten
        FileInputStream(file).use { fileInputStream ->
            BufferedInputStream(fileInputStream).use { inputStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    currentTotalBytes += bytesRead
                    
                    // Emit progress
                    emit(
                        IONFLTRTransferResult.Ongoing(
                            IONFLTRProgressStatus(
                                bytes = currentTotalBytes,
                                contentLength = totalSize,
                                lengthComputable = true
                            )
                        )
                    )
                }
            }
        }
        currentTotalBytes
    }

    /**
     * Downloads a file from a remote URL to a local file path.
     *
     * @param options The download options including URL and file path
     * @return A Flow of [IONFLTRTransferResult] to track progress and completion
     */
    fun downloadFile(options: IONFLTRDownloadOptions): Flow<IONFLTRTransferResult> = flow {
        runCatchingIONFLTRExceptions {
            // Validate inputs
            validateTransferInputs(options.url, options.filePath)

            // Create parent directories if needed
            val targetFile = File(options.filePath)
            fileHelper.createParentDirectories(targetFile)

            // Setup connection
            val connection = connectionHelper.setupConnection(options.url, options.httpOptions)
            
            try {
                connection.connect()
                
                // Check response code
                val responseCode = connection.responseCode
                if (responseCode < 200 || responseCode > 299) {
                    val errorBody = connection.errorStream?.bufferedReader()?.readText()
                    throw IONFLTRException.HttpError(
                        responseCode.toString(),
                        errorBody,
                        connection.headerFields
                    )
                }
                
                // Get content length if available
                val contentLength = connection.contentLength.toLong()
                val lengthComputable = contentLength > 0
                
                // Download the file
                BufferedInputStream(connection.inputStream).use { inputStream ->
                    FileOutputStream(targetFile).use { fileOut ->
                        BufferedOutputStream(fileOut).use { outputStream ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int
                            var totalBytesRead: Long = 0
                            
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead
                                
                                // Emit progress
                                emit(
                                    IONFLTRTransferResult.Ongoing(
                                        IONFLTRProgressStatus(
                                            bytes = totalBytesRead,
                                            contentLength = contentLength,
                                            lengthComputable = lengthComputable
                                        )
                                    )
                                )
                            }
                            
                            // Emit completion
                            emit(
                                IONFLTRTransferResult.Complete(
                                    IONFLTRTransferComplete(
                                        totalBytes = totalBytesRead,
                                        responseCode = responseCode.toString(),
                                        responseBody = null,
                                        headers = connection.headerFields
                                    )
                                )
                            )
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }.getOrThrow()
    }.flowOn(Dispatchers.IO)

    /**
     * Uploads a file from a local path to a remote URL.
     *
     * @param options The upload options including URL, file path, and other configuration
     * @return A Flow of [IONFLTRTransferResult] to track progress and completion
     */
    fun uploadFile(options: IONFLTRUploadOptions): Flow<IONFLTRTransferResult> = flow {
        runCatchingIONFLTRExceptions {
            // Validate inputs
            validateTransferInputs(options.url, options.filePath)
            
            // Check if file exists
            val file = File(options.filePath)
            if (!file.exists()) {
                throw IONFLTRException.FileDoesNotExist()
            }
            
            // Setup connection
            val connection = connectionHelper.setupConnection(options.url, options.httpOptions)
            
            try {
                val fileSize = file.length()
                val useChunkedMode = options.chunkedMode || fileSize == -1L
                var totalBytesWritten: Long
                
                if (useChunkedMode) {
                    connection.setChunkedStreamingMode(BUFFER_SIZE)
                    connection.setRequestProperty("Transfer-Encoding", "chunked")
                } else {
                    if (!isPostOrPutMethod(options.httpOptions.method)) {
                        connection.setFixedLengthStreamingMode(fileSize)
                    } else {
                        // Calculate total size including multipart overhead
                        val multipartOverheadBytes = if (isPostOrPutMethod(options.httpOptions.method)) {
                            val boundary = "--$BOUNDARY\r\n"
                            val fileHeader = "Content-Disposition: form-data; name=\"${options.fileKey}\"; filename=\"${file.name}\"\r\n"
                            val mimeType = options.mimeType ?: fileHelper.getMimeType(options.filePath) ?: "application/octet-stream"
                            val contentType = "Content-Type: $mimeType\r\n\r\n"
                            val closingBoundary = "\r\n--$BOUNDARY--\r\n"
                            
                            // Add form parameters overhead if any
                            val formParamsOverhead = options.formParams?.entries?.sumOf { (key, value) ->
                                val paramHeader = "Content-Disposition: form-data; name=\"$key\"\r\n\r\n"
                                val paramValue = "$value\r\n"
                                (paramHeader + paramValue + boundary).toByteArray(StandardCharsets.UTF_8).size
                            } ?: 0
                            
                            boundary.toByteArray(StandardCharsets.UTF_8).size +
                            fileHeader.toByteArray(StandardCharsets.UTF_8).size +
                            contentType.toByteArray(StandardCharsets.UTF_8).size +
                            closingBoundary.toByteArray(StandardCharsets.UTF_8).size +
                            formParamsOverhead
                        } else 0
                        
                        connection.setFixedLengthStreamingMode(fileSize + multipartOverheadBytes)
                    }
                }
                
                // Set content type if not already set
                if (!options.httpOptions.headers.containsKey("Content-Type")) {
                    val mimeType = options.mimeType ?: fileHelper.getMimeType(options.filePath) ?: "application/octet-stream"
                    if (isPostOrPutMethod(options.httpOptions.method)) {
                        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
                    } else {
                        connection.setRequestProperty("Content-Type", mimeType)
                    }
                }
                
                connection.doOutput = true
                connection.connect()
                
                // Start uploading
                connection.outputStream.use { connOutputStream ->
                    BufferedOutputStream(connOutputStream).use { outputStream ->
                        if (isPostOrPutMethod(options.httpOptions.method)) {
                            // Handle multipart form data
                            val boundary = "--$BOUNDARY\r\n"
                            val boundaryBytes = boundary.toByteArray(StandardCharsets.UTF_8)
                            outputStream.write(boundaryBytes)
                            
                            // Calculate multipart overhead for form parameters and keep track of written bytes
                            totalBytesWritten = boundaryBytes.size.toLong()
                            
                            // Write additional form parameters if any
                            options.formParams?.forEach { (key, value) ->
                                val paramHeader = "Content-Disposition: form-data; name=\"$key\"\r\n\r\n"
                                val paramValue = "$value\r\n"
                                val paramBytes = (paramHeader + paramValue).toByteArray(StandardCharsets.UTF_8)
                                outputStream.write(paramBytes)
                                totalBytesWritten += paramBytes.size
                                
                                // Write boundary for next part
                                outputStream.write(boundaryBytes)
                                totalBytesWritten += boundaryBytes.size
                            }
                            
                            val fileHeader = "Content-Disposition: form-data; name=\"${options.fileKey}\"; filename=\"${file.name}\"\r\n"
                            val fileHeaderBytes = fileHeader.toByteArray(StandardCharsets.UTF_8)
                            outputStream.write(fileHeaderBytes)
                            
                            val mimeType = options.mimeType ?: fileHelper.getMimeType(options.filePath) ?: "application/octet-stream"
                            val contentType = "Content-Type: $mimeType\r\n\r\n"
                            val contentTypeBytes = contentType.toByteArray(StandardCharsets.UTF_8)
                            outputStream.write(contentTypeBytes)
                            
                            // Calculate closing boundary
                            val closingBoundary = "\r\n--$BOUNDARY--\r\n"
                            val closingBoundaryBytes = closingBoundary.toByteArray(StandardCharsets.UTF_8)
                            
                            // Calculate total multipart overhead bytes
                            val multipartOverheadBytes = boundaryBytes.size + fileHeaderBytes.size + 
                                                        contentTypeBytes.size + closingBoundaryBytes.size
                            
                            // Actual total size includes file size plus multipart overhead
                            val totalSize = fileSize + multipartOverheadBytes
                            
                            // Update totalBytesWritten to include file header and content type
                            totalBytesWritten += fileHeaderBytes.size + contentTypeBytes.size
                            
                            // Write file content
                            totalBytesWritten = writeFileWithProgress(
                                file = file,
                                outputStream = outputStream,
                                totalBytesWritten = totalBytesWritten,
                                totalSize = totalSize,
                                emit = { emit(it) }
                            )
                            
                            outputStream.write(closingBoundaryBytes)
                            totalBytesWritten += closingBoundaryBytes.size
                            
                            // Final update to ensure we account for the closing boundary
                            emit(
                                IONFLTRTransferResult.Ongoing(
                                    IONFLTRProgressStatus(
                                        bytes = totalBytesWritten,
                                        contentLength = totalSize,
                                        lengthComputable = true
                                    )
                                )
                            )
                        } else {
                            // Direct upload (not multipart)
                            totalBytesWritten = writeFileWithProgress(
                                file = file,
                                outputStream = outputStream,
                                totalBytesWritten = 0,
                                totalSize = fileSize,
                                emit = { emit(it) }
                            )
                        }
                    }
                }
                
                // Check response
                val responseCode = connection.responseCode
                val responseBody = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().readText()
                } else {
                    connection.errorStream?.bufferedReader()?.readText()?.also {
                        throw IONFLTRException.HttpError(
                            responseCode.toString(),
                            it,
                            connection.headerFields
                        )
                    }
                }
                
                // Return success
                emit(
                    IONFLTRTransferResult.Complete(
                        IONFLTRTransferComplete(
                            totalBytes = if (isPostOrPutMethod(options.httpOptions.method)) totalBytesWritten else file.length(),
                            responseCode = responseCode.toString(),
                            responseBody = responseBody,
                            headers = connection.headerFields
                        )
                    )
                )
            } finally {
                connection.disconnect()
            }
        }.getOrThrow()
    }.flowOn(Dispatchers.IO)

    /**
     * Validates the URL and file path for transfer operations.
     * 
     * @param url The URL to validate
     * @param filePath The file path to validate
     * @throws IONFLTRException if validation fails
     */
    private fun validateTransferInputs(url: String, filePath: String) {
        when {
            url.isBlank() -> throw IONFLTRException.EmptyURL(url)
            !inputsValidator.isURLValid(url) -> throw IONFLTRException.InvalidURL(url)
            !inputsValidator.isPathValid(filePath) -> throw IONFLTRException.InvalidPath(filePath)
        }
    }
    
    /**
     * Checks if the HTTP method is either POST or PUT.
     * 
     * @param method The HTTP method to check
     * @return True if the method is POST or PUT, false otherwise
     */
    private fun isPostOrPutMethod(method: String): Boolean {
        return method.equals("POST", ignoreCase = true) || method.equals("PUT", ignoreCase = true)
    }
} 