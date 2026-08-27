package io.ionic.libs.ionfiletransferlib

import android.content.Context
import io.ionic.libs.ionfiletransferlib.helpers.FileToUploadInfo
import io.ionic.libs.ionfiletransferlib.helpers.IONFLTRConnectionHelper
import io.ionic.libs.ionfiletransferlib.helpers.IONFLTRFileHelper
import io.ionic.libs.ionfiletransferlib.helpers.IONFLTRInputsValidator
import io.ionic.libs.ionfiletransferlib.helpers.IONFLTRTransferHandle
import io.ionic.libs.ionfiletransferlib.helpers.assertSuccessHttpResponse
import io.ionic.libs.ionfiletransferlib.helpers.runCatchingIONFLTRExceptions
import io.ionic.libs.ionfiletransferlib.helpers.use
import io.ionic.libs.ionfiletransferlib.model.IONFLTRDownloadOptions
import io.ionic.libs.ionfiletransferlib.model.IONFLTRException
import io.ionic.libs.ionfiletransferlib.model.IONFLTRProgressStatus
import io.ionic.libs.ionfiletransferlib.model.IONFLTRTransferComplete
import io.ionic.libs.ionfiletransferlib.model.IONFLTRTransferResult
import io.ionic.libs.ionfiletransferlib.model.IONFLTRUploadOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

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
    constructor(context: Context) : this(
        inputsValidator = IONFLTRInputsValidator(),
        fileHelper = IONFLTRFileHelper(contentResolver = context.contentResolver),
        connectionHelper = IONFLTRConnectionHelper()
    )

    /**
     * The transfers that are currently in progress and were given an id, by id.
     */
    private val activeTransfers = ConcurrentHashMap<String, IONFLTRTransferHandle>()

    companion object {
        private const val BUFFER_SIZE = 8192 // 8KB buffer size
        private const val BOUNDARY = "++++IONFLTRBoundary"
        private const val LINE_START = "--"
        private const val LINE_END = "\r\n"
    }

    /**
     * Downloads a file from a remote URL to a local file path.
     *
     * The download can be aborted by calling [abortTransfer] with the id in
     * [IONFLTRDownloadOptions.id], which interrupts the download immediately.
     * Cancelling the coroutine that is collecting the flow also stops the download,
     * although only once the chunk being read at that moment is done.
     *
     * @param options The download options including URL and file path
     * @return A Flow of [IONFLTRTransferResult] to track progress and completion;
     *  it fails with [IONFLTRException.TransferAborted] if the download is aborted via [abortTransfer]
     */
    fun downloadFile(options: IONFLTRDownloadOptions): Flow<IONFLTRTransferResult> = flow {
        val handle = registerTransfer(options.id)
        var targetFile: File? = null
        try {
            runCatchingIONFLTRExceptions {
                handle.throwIfAborted()

                // Prepare for download
                val (file, connection) = prepareForDownload(options)
                targetFile = file
                handle.setConnection(connection)

                connection.use { conn ->
                    // Execute the download and handle response
                    val contentLength = beginDownload(conn)

                    // Perform the actual file download with progress reporting
                    val totalBytesRead = downloadFileWithProgress(
                        connection = conn,
                        targetFile = file,
                        contentLength = contentLength,
                        handle = handle,
                        emit = { emit(it) }
                    )

                    // Emit completion
                    emit(
                        IONFLTRTransferResult.Complete(
                            IONFLTRTransferComplete(
                                totalBytes = totalBytesRead,
                                responseCode = conn.responseCode.toString(),
                                responseBody = null,
                                headers = conn.headerFields
                            )
                        )
                    )
                }
            }.getOrElse { error ->
                // an abort closes the connection, so the resulting error is replaced by TransferAborted
                val mappedError = handle.mapErrorIfAborted(error)
                if (mappedError is IONFLTRException.TransferAborted) {
                    // the partially downloaded file is of no use to anyone
                    targetFile?.let { fileHelper.deleteFile(it) }
                }
                throw mappedError
            }
        } finally {
            options.id?.let { activeTransfers.remove(it, handle) }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Aborts an ongoing transfer that was started with the provided id.
     *
     * The flow of the aborted transfer fails with [IONFLTRException.TransferAborted],
     * and any partially downloaded file is deleted.
     *
     * @param id The id that was provided in [IONFLTRDownloadOptions.id]
     * @return true if there was an ongoing transfer with the provided id, false otherwise
     */
    fun abortTransfer(id: String): Boolean =
        activeTransfers[id]?.also { it.abort() } != null

    /**
     * @param id The id of the transfer, if any
     * @return the handle to use for tracking (and aborting) the transfer
     */
    private fun registerTransfer(id: String?): IONFLTRTransferHandle =
        IONFLTRTransferHandle(id).also { handle ->
            id?.let { activeTransfers[it] = handle }
        }

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

            connection.use { conn ->
                // Execute the upload and handle response
                val multiPartFormData = beginUpload(conn, options, file)

                // Perform the upload
                val totalBytesWritten: Long = if (multiPartFormData != null) {
                    handleMultipartUpload(conn, multiPartFormData, file, emit = { emit(it) })
                } else {
                    handleDirectUpload(conn, file, emit = { emit(it) })
                }

                // Process the response
                processUploadResponse(conn, totalBytesWritten, emit = { emit(it) })
            }
        }.getOrThrow()
    }.flowOn(Dispatchers.IO)

    /**
     * Prepares for download by validating inputs, creating directories and setting up connection.
     */
    private fun prepareForDownload(options: IONFLTRDownloadOptions): Pair<File, HttpURLConnection> {
        // Validate inputs
        val normalizedFilePath = fileHelper.normalizeFilePath(options.filePath)
        inputsValidator.validateTransferInputs(options.url, normalizedFilePath)

        // Create parent directories if needed
        val targetFile = File(URI(normalizedFilePath).path)
        fileHelper.createParentDirectories(targetFile)

        // Setup connection
        val connection = connectionHelper.setupConnection(options.url, options.httpOptions).let {
            connectionHelper.appendParamsToUrl(options.url, it, options.httpOptions, useChunkedMode = false)
        }

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
     * Begins the upload process by configuring the connection and connecting.
     *
     * @param connection The HTTP connection to configure
     * @param options The upload options
     * @param file Information about the file to upload
     * @return multi-part form data to append to beginning and end if needed, null otherwise
     */
    private fun beginUpload(
        connection: HttpURLConnection,
        options: IONFLTRUploadOptions,
        file: FileToUploadInfo
    ): Pair<String, String>? {
        val useChunkedMode = options.chunkedMode || file.size == -1L

        // Configure connection based on upload mode
        val multiPartFormData = configureConnectionForUpload(connection, options, file, useChunkedMode)

        connection.doOutput = true
        connection.connect()

        return multiPartFormData
    }

    /**
     * Downloads the file content with progress reporting.
     */
    private suspend fun downloadFileWithProgress(
        connection: HttpURLConnection,
        targetFile: File,
        contentLength: Long,
        handle: IONFLTRTransferHandle,
        emit: suspend (IONFLTRTransferResult) -> Unit
    ): Long = BufferedInputStream(connection.inputStream).use { inputStream ->
        FileOutputStream(targetFile).use { fileOut ->
            BufferedOutputStream(fileOut).use { outputStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                val lengthComputable = contentLength > 0
                var totalBytesRead: Long = 0

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    handle.throwIfAborted()
                    currentCoroutineContext().ensureActive()
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
    private suspend fun uploadFileWithProgress(
        file: FileToUploadInfo,
        outputStream: BufferedOutputStream,
        totalBytesWritten: Long,
        totalSize: Long,
        emit: suspend (IONFLTRTransferResult) -> Unit
    ): Long {
        var currentTotalBytes = totalBytesWritten
        file.inputStream.buffered().use { inputStream ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                currentTotalBytes += bytesRead
                emit(createUploadFileProgress(bytes = currentTotalBytes, total = totalSize))
            }
        }
        return currentTotalBytes
    }

    /**
     * Prepares for upload by validating inputs and setting up connection.
     */
    private fun prepareForUpload(options: IONFLTRUploadOptions): Pair<FileToUploadInfo, HttpURLConnection> {
        // Validate inputs
        inputsValidator.validateTransferInputs(options.url, options.filePath)

        // Check if file exists
        val file = fileHelper.getFileToUploadInfo(options.filePath)

        // Setup connection
        val connection = connectionHelper.setupConnection(options.url, options.httpOptions).let {
            connectionHelper.appendParamsToUrl(options.url, it, options.httpOptions, options.chunkedMode)
        }

        return Pair(file, connection)
    }

    /**
     * Configures the connection for upload based on the upload mode.
     *
     * @return multi-part form data to append to beginning and end
     */
    private fun configureConnectionForUpload(
        connection: HttpURLConnection,
        options: IONFLTRUploadOptions,
        file: FileToUploadInfo,
        useChunkedMode: Boolean
    ): Pair<String, String>? {
        var multiPartUpload = false
        // Set content type if not already set
        if (connectionHelper.useMultipartFormData(options.httpOptions) && !useChunkedMode) {
            multiPartUpload = true
            connection.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=$BOUNDARY"
            )
        } else if (!options.httpOptions.headers.containsKey("Content-Type")) {
            val mimeType = options.mimeType ?: fileHelper.getMimeType(options.filePath)
            ?: "application/octet-stream"
            connection.setRequestProperty("Content-Type", mimeType)
        }

        if (useChunkedMode) {
            connection.setChunkedStreamingMode(BUFFER_SIZE)
            connection.setRequestProperty("Transfer-Encoding", "chunked")
        } else if (!multiPartUpload) {
            connection.setFixedLengthStreamingMode(file.size)
        } else {
            val multipartData = createMultipartData(options, file.name)
            // Calculate total size including multipart overhead
            val multipartByteArray = (multipartData.first + multipartData.second).toByteArray()
            connection.setFixedLengthStreamingMode(file.size + multipartByteArray.size)
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
            // Write form parameters using the available attributes
            val allParams = (options.formParams ?: emptyMap()) + options.httpOptions.params
            allParams.forEach { (key, value) ->
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
        file: FileToUploadInfo,
        emit: suspend (IONFLTRTransferResult) -> Unit
    ): Long {
        var totalBytesWritten: Long = 0

        connection.outputStream.use { connOutputStream ->
            BufferedOutputStream(connOutputStream).use { outputStream ->
                val beforeDataByteArray = multipartExtraData.first.toByteArray()
                val afterDataByteArray = multipartExtraData.second.toByteArray()

                // Actual total size includes file size plus multipart overhead
                val totalSize = file.size + beforeDataByteArray.size + afterDataByteArray.size

                // write multipart form content before file
                totalBytesWritten += beforeDataByteArray.size
                outputStream.write(beforeDataByteArray)
                emit(createUploadFileProgress(bytes = totalBytesWritten, total = totalSize))

                // Write file content (skip reading the file if it's empty)
                if (file.size > 0) {
                    totalBytesWritten = uploadFileWithProgress(
                        file = file,
                        outputStream = outputStream,
                        totalBytesWritten = totalBytesWritten,
                        totalSize = totalSize,
                        emit = { emit(it) }
                    )
                }

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
        file: FileToUploadInfo,
        emit: suspend (IONFLTRTransferResult) -> Unit
    ): Long {
        if (file.size == 0L) {
            // For empty files, still emit a progress event showing 0 bytes
            emit(createUploadFileProgress(bytes = 0, total = 0))
            return 0L
        }

        var totalBytesWritten: Long

        connection.outputStream.use { connOutputStream ->
            BufferedOutputStream(connOutputStream).use { outputStream ->
                // Direct upload (not multipart)
                totalBytesWritten = uploadFileWithProgress(
                    file = file,
                    outputStream = outputStream,
                    totalBytesWritten = 0,
                    totalSize = file.size,
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
}