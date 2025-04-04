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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection

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
        private const val BOUNDARY = "++++IONFLTRBoundary"
        private const val LINE_START = "--"
        private const val LINE_END = "\r\n"
    }

    /**
     * Downloads a file from a remote URL to a local file path.
     *
     * @param options The download options including URL and file path
     * @return A Flow of [IONFLTRTransferResult] to track progress and completion
     */
    fun downloadFile(options: IONFLTRDownloadOptions): Flow<IONFLTRTransferResult> = flow {
        runCatchingIONFLTRExceptions {
            // Prepare for download
            val (targetFile, connection) = prepareForDownload(options)

            try {
                // Execute the download and handle response
                val contentLength = beginDownload(connection)

                // Perform the actual file download with progress reporting
                val totalBytesRead = downloadFileWithProgress(
                    connection = connection,
                    targetFile = targetFile,
                    contentLength = contentLength,
                    emit = { emit(it) }
                )

                // Emit completion
                emit(
                    IONFLTRTransferResult.Complete(
                        IONFLTRTransferComplete(
                            totalBytes = totalBytesRead,
                            responseCode = connection.responseCode.toString(),
                            responseBody = null,
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
     * Uploads a file from a local path to a remote URL.
     *
     * @param options The upload options including URL, file path, and other configuration
     * @return A Flow of [IONFLTRTransferResult] to track progress and completion
     */
    fun uploadFile(options: IONFLTRUploadOptions): Flow<IONFLTRTransferResult> = flow {
        runCatchingIONFLTRExceptions {
            // Prepare for upload
            val (file, connection) = prepareForUpload(options)

            try {
                val useChunkedMode = options.chunkedMode || file.length() == -1L

                // Configure connection based on upload mode
                val multiPartFormData =
                    configureConnectionForUpload(connection, options, file, useChunkedMode)

                connection.doOutput = true
                connection.connect()

                // Perform the upload
                val totalBytesWritten: Long = if (multiPartFormData != null) {
                    handleMultipartUpload(connection, multiPartFormData, file, emit = { emit(it) })
                } else {
                    handleDirectUpload(connection, file, emit = { emit(it) })
                }

                // Process the response
                processUploadResponse(connection, totalBytesWritten, emit = { emit(it) })
            } finally {
                connection.disconnect()
            }
        }.getOrThrow()
    }.flowOn(Dispatchers.IO)

    /**
     * Prepares for download by validating inputs, creating directories and setting up connection.
     */
    private fun prepareForDownload(options: IONFLTRDownloadOptions): Pair<File, HttpURLConnection> {
        // Validate inputs
        inputsValidator.validateTransferInputs(options.url, options.filePath)

        // Create parent directories if needed
        val targetFile = File(options.filePath)
        fileHelper.createParentDirectories(targetFile)

        // Setup connection
        val connection = connectionHelper.setupConnection(options.url, options.httpOptions)

        return Pair(targetFile, connection)
    }

    /**
     * Begins the download process and checks the response code.
     *
     * @return the content length associated with the download request
     */
    private fun beginDownload(connection: HttpURLConnection): Long {
        connection.connect()

        // Check response code
        connection.assertSuccessHttpResponse()

        // Get content length if available
        val contentLength = connection.contentLength.toLong()

        return contentLength
    }

    /**
     * Downloads the file content with progress reporting.
     */
    private suspend fun downloadFileWithProgress(
        connection: HttpURLConnection,
        targetFile: File,
        contentLength: Long,
        emit: suspend (IONFLTRTransferResult) -> Unit
    ): Long = BufferedInputStream(connection.inputStream).use { inputStream ->
        FileOutputStream(targetFile).use { fileOut ->
            BufferedOutputStream(fileOut).use { outputStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                val lengthComputable = connection.contentEncoding.let {
                    it == null || it.equals("gzip", ignoreCase = true)
                } && contentLength > 0
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

                totalBytesRead
            }
        }
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
    @Suppress("BlockingMethodInNonBlockingContext") // method will always be called from IO scope
    private suspend fun uploadFileWithProgress(
        file: File,
        outputStream: BufferedOutputStream,
        totalBytesWritten: Long,
        totalSize: Long,
        emit: suspend (IONFLTRTransferResult) -> Unit
    ): Long {
        var currentTotalBytes = totalBytesWritten
        FileInputStream(file).use { fileInputStream ->
            BufferedInputStream(fileInputStream).use { inputStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    currentTotalBytes += bytesRead
                    emit(createUploadFileProgress(bytes = currentTotalBytes, total = totalSize))
                }
            }
        }
        return currentTotalBytes
    }

    /**
     * Prepares for upload by validating inputs and setting up connection.
     */
    private fun prepareForUpload(options: IONFLTRUploadOptions): Pair<File, HttpURLConnection> {
        // Validate inputs
        inputsValidator.validateTransferInputs(options.url, options.filePath)

        // Check if file exists
        val file = File(options.filePath)
        if (!file.exists()) {
            throw IONFLTRException.FileDoesNotExist()
        }

        // Setup connection
        val connection = connectionHelper.setupConnection(options.url, options.httpOptions)

        return Pair(file, connection)
    }

    /**
     * Configures the connection for upload based on the upload mode.
     *
     * @return multi-part form data to append to beggining and end
     */
    private fun configureConnectionForUpload(
        connection: HttpURLConnection,
        options: IONFLTRUploadOptions,
        file: File,
        useChunkedMode: Boolean
    ): Pair<String, String>? {
        var multiPartUpload = false
        // Set content type if not already set
        if (!options.httpOptions.headers.containsKey("Content-Type")) {
            val mimeType = options.mimeType ?: fileHelper.getMimeType(options.filePath)
            ?: "application/octet-stream"
            if (isPostOrPutMethod(options.httpOptions.method)) {
                multiPartUpload = true
                connection.setRequestProperty(
                    "Content-Type",
                    "multipart/form-data; boundary=$BOUNDARY"
                )
            } else {
                connection.setRequestProperty("Content-Type", mimeType)
            }
        }

        // gzip to allow for better progress tracking
        connection.setRequestProperty("Accept-Encoding", "gzip")

        if (useChunkedMode) {
            connection.setChunkedStreamingMode(BUFFER_SIZE)
            connection.setRequestProperty("Transfer-Encoding", "chunked")
        } else if (!multiPartUpload) {
            connection.setFixedLengthStreamingMode(file.length())
        } else {
            val multipartData = createMultipartData(options, file.name)
            // Calculate total size including multipart overhead
            val multipartByteArray = (multipartData.first + multipartData.second).toByteArray()
            connection.setFixedLengthStreamingMode(file.length() + multipartByteArray.size)
            return multipartData
        }

        return null
    }

    /**
     * Create the multipart-form data that will be added to the upload request body
     *
     * There are two parts: content to be added before the file data, and after the file
     *
     * @param options the options to configure the upload
     * @param fileName name of the file to upload
     * @return pair of multipart content to upload before and after file data
     */
    private fun createMultipartData(
        options: IONFLTRUploadOptions,
        fileName: String
    ): Pair<String, String> {
        val boundary = "$LINE_START$BOUNDARY$LINE_END"

        val beforeData = buildString {
            // Write additional form parameters if any
            options.formParams?.forEach { (key, value) ->
                append(boundary)
                val paramHeader = "Content-Disposition: form-data; name=\"$key\"$LINE_END$LINE_END"
                val paramValue = "$value$LINE_END"
                val param = (paramHeader + paramValue)
                append(param)
            }
            append(boundary)
            val fileHeader =
                "Content-Disposition: form-data; name=\"${options.fileKey}\"; filename=\"${fileName}\"$LINE_END"
            append(fileHeader)
            val mimeType = options.mimeType ?: fileHelper.getMimeType(options.filePath)
            ?: "application/octet-stream"
            val contentType = "Content-Type: $mimeType$LINE_END$LINE_END"
            append(contentType)
        }

        val afterData = "$LINE_END$LINE_START$BOUNDARY$LINE_START$LINE_END"

        return Pair(beforeData, afterData)
    }

    /**
     * Handles multipart form data uploads.
     */
    private suspend fun handleMultipartUpload(
        connection: HttpURLConnection,
        multipartExtraData: Pair<String, String>,
        file: File,
        emit: suspend (IONFLTRTransferResult) -> Unit
    ): Long {
        var totalBytesWritten: Long = 0

        connection.outputStream.use { connOutputStream ->
            BufferedOutputStream(connOutputStream).use { outputStream ->
                val beforeDataByteArray = multipartExtraData.first.toByteArray()
                val afterDataByteArray = multipartExtraData.second.toByteArray()

                // Actual total size includes file size plus multipart overhead
                val totalSize = file.length() + beforeDataByteArray.size + afterDataByteArray.size

                // write multipart form content before file
                totalBytesWritten += beforeDataByteArray.size
                outputStream.write(beforeDataByteArray)
                emit(createUploadFileProgress(bytes = totalBytesWritten, total = totalSize))

                // Write file content
                totalBytesWritten = uploadFileWithProgress(
                    file = file,
                    outputStream = outputStream,
                    totalBytesWritten = totalBytesWritten,
                    totalSize = totalSize,
                    emit = { emit(it) }
                )

                // write multipart form content after file
                outputStream.write(afterDataByteArray)
                totalBytesWritten += afterDataByteArray.size
                emit(createUploadFileProgress(bytes = totalBytesWritten, total = totalSize))
            }
        }

        return totalBytesWritten
    }

    /**
     * Handles direct (non-multipart) file uploads.
     */
    private suspend fun handleDirectUpload(
        connection: HttpURLConnection,
        file: File,
        emit: suspend (IONFLTRTransferResult) -> Unit
    ): Long {
        var totalBytesWritten: Long

        connection.outputStream.use { connOutputStream ->
            BufferedOutputStream(connOutputStream).use { outputStream ->
                // Direct upload (not multipart)
                totalBytesWritten = uploadFileWithProgress(
                    file = file,
                    outputStream = outputStream,
                    totalBytesWritten = 0,
                    totalSize = file.length(),
                    emit = { emit(it) }
                )
            }
        }

        return totalBytesWritten
    }

    private fun createUploadFileProgress(bytes: Long, total: Long) =
        IONFLTRTransferResult.Ongoing(
            status = IONFLTRProgressStatus(
                bytes = bytes, contentLength = total, lengthComputable = true
            )
        )

    /**
     * Processes the upload response and emits completion.
     */
    private suspend fun processUploadResponse(
        connection: HttpURLConnection,
        totalBytesWritten: Long,
        emit: suspend (IONFLTRTransferResult) -> Unit
    ) {
        // Check response
        connection.assertSuccessHttpResponse()
        val responseCode = connection.responseCode
        val responseBody = connection.inputStream.bufferedReader().readText()

        // Return success
        emit(
            IONFLTRTransferResult.Complete(
                IONFLTRTransferComplete(
                    totalBytes = totalBytesWritten,
                    responseCode = responseCode.toString(),
                    responseBody = responseBody,
                    headers = connection.headerFields
                )
            )
        )
    }

    private fun HttpURLConnection.assertSuccessHttpResponse() {
        if (responseCode in 200..299) {
            return // successful response
        }
        errorStream?.bufferedReader()?.readText()?.also {
            throw IONFLTRException.HttpError(
                responseCode.toString(),
                it,
                headerFields
            )
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