package io.ionic.libs.ionfiletransferlib

import android.content.Context
import io.ionic.libs.ionfiletransferlib.helpers.IONFLTRInputsValidator
import io.ionic.libs.ionfiletransferlib.helpers.IONFLTRFileHelper
import io.ionic.libs.ionfiletransferlib.helpers.runCatchingIONFLTRExceptions
import io.ionic.libs.ionfiletransferlib.model.IONFLTRException
import io.ionic.libs.ionfiletransferlib.model.IONFLTRDownloadOptions
import io.ionic.libs.ionfiletransferlib.model.IONFLTRUploadOptions
import io.ionic.libs.ionfiletransferlib.model.IONFLTRTransferResult
import io.ionic.libs.ionfiletransferlib.model.IONFLTRProgressStatus
import io.ionic.libs.ionfiletransferlib.model.IONFLTRTransferComplete
import io.ionic.libs.ionfiletransferlib.model.IONFLTRTransferHttpOptions
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
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Entry point in IONFileTransferLib-Android
 *
 * Contains relevant methods for downloading and uploading files in Android.
 */
class IONFLTRController internal constructor(
    private val inputsValidator: IONFLTRInputsValidator,
    private val fileHelper: IONFLTRFileHelper
) {
    constructor() : this(
        inputsValidator = IONFLTRInputsValidator(),
        fileHelper = IONFLTRFileHelper()
    )

    companion object {
        private const val BUFFER_SIZE = 8192 // 8KB buffer size
        private const val BOUNDARY = "----IONFLTRBoundary"
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
            when {
                options.url.isBlank() -> throw IONFLTRException.EmptyURL(options.url)
                !inputsValidator.isURLValid(options.url) -> throw IONFLTRException.InvalidURL(options.url)
                !inputsValidator.isPathValid(options.filePath) -> throw IONFLTRException.InvalidPath(options.filePath)
            }

            // Create parent directories if needed
            val targetFile = File(options.filePath)
            fileHelper.createParentDirectories(targetFile)

            // Setup connection
            val connection = setupConnection(options.url, options.httpOptions)
            
            try {
                connection.connect()
                
                // Check response code
                val responseCode = connection.responseCode
                if (responseCode < 200 || responseCode > 299) {
                    val errorBody = connection.errorStream?.bufferedReader()?.readText()
                    throw IONFLTRException.HttpError(
                        responseCode.toString(),
                        errorBody,
                        connection.headerFields.mapValues { it.value.firstOrNull() ?: "" }
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
                            val headers = connection.headerFields.mapValues { it.value.firstOrNull() ?: "" }
                            emit(
                                IONFLTRTransferResult.Complete(
                                    IONFLTRTransferComplete(
                                        totalBytes = totalBytesRead,
                                        responseCode = responseCode.toString(),
                                        responseBody = null,
                                        headers = headers
                                    )
                                )
                            )
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }.onFailure { throwable ->
            throw throwable
        }
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
            when {
                options.url.isBlank() -> throw IONFLTRException.EmptyURL(options.url)
                !inputsValidator.isURLValid(options.url) -> throw IONFLTRException.InvalidURL(options.url)
                !inputsValidator.isPathValid(options.filePath) -> throw IONFLTRException.InvalidPath(options.filePath)
            }
            
            // Check if file exists
            val file = File(options.filePath)
            if (!file.exists()) {
                throw IONFLTRException.FileDoesNotExist()
            }
            
            // Setup connection
            val connection = setupConnection(options.url, options.httpOptions)
            
            try {
                val fileSize = file.length()
                
                // Handle multipart or direct upload
                if (options.chunkedMode) {
                    connection.setChunkedStreamingMode(BUFFER_SIZE)
                } else {
                    connection.setFixedLengthStreamingMode(fileSize)
                }
                
                // Set content type if not already set
                if (!options.httpOptions.headers.containsKey("Content-Type")) {
                    val mimeType = options.mimeType ?: fileHelper.getMimeType(options.filePath) ?: "application/octet-stream"
                    if (options.httpOptions.method.equals("POST", ignoreCase = true)) {
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
                        if (options.httpOptions.method.equals("POST", ignoreCase = true)) {
                            // Handle multipart form data
                            val boundary = "--$BOUNDARY\r\n"
                            outputStream.write(boundary.toByteArray(StandardCharsets.UTF_8))
                            
                            val fileHeader = "Content-Disposition: form-data; name=\"${options.fileKey}\"; filename=\"${file.name}\"\r\n"
                            outputStream.write(fileHeader.toByteArray(StandardCharsets.UTF_8))
                            
                            val mimeType = options.mimeType ?: fileHelper.getMimeType(options.filePath) ?: "application/octet-stream"
                            val contentType = "Content-Type: $mimeType\r\n\r\n"
                            outputStream.write(contentType.toByteArray(StandardCharsets.UTF_8))
                            
                            // Write file content
                            FileInputStream(file).use { fileInputStream ->
                                BufferedInputStream(fileInputStream).use { inputStream ->
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
                                                    contentLength = fileSize,
                                                    lengthComputable = true
                                                )
                                            )
                                        )
                                    }
                                }
                            }
                            
                            outputStream.write("\r\n--$BOUNDARY--\r\n".toByteArray(StandardCharsets.UTF_8))
                        } else {
                            // Direct upload (not multipart)
                            FileInputStream(file).use { fileInputStream ->
                                BufferedInputStream(fileInputStream).use { inputStream ->
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
                                                    contentLength = fileSize,
                                                    lengthComputable = true
                                                )
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Check response
                val responseCode = connection.responseCode
                val responseBody = if (responseCode >= 200 && responseCode < 300) {
                    connection.inputStream.bufferedReader().readText()
                } else {
                    connection.errorStream?.bufferedReader()?.readText()
                }
                
                if (responseCode < 200 || responseCode > 299) {
                    throw IONFLTRException.HttpError(
                        responseCode.toString(),
                        responseBody,
                        connection.headerFields.mapValues { it.value.firstOrNull() ?: "" }
                    )
                }
                
                // Return success
                val headers = connection.headerFields.mapValues { it.value.firstOrNull() ?: "" }
                emit(
                    IONFLTRTransferResult.Complete(
                        IONFLTRTransferComplete(
                            totalBytes = file.length(),
                            responseCode = responseCode.toString(),
                            responseBody = responseBody,
                            headers = headers
                        )
                    )
                )
            } finally {
                connection.disconnect()
            }
        }.onFailure { throwable ->
            throw throwable
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Sets up the HTTP connection with the provided options.
     */
    private fun setupConnection(urlString: String, httpOptions: IONFLTRTransferHttpOptions): HttpURLConnection {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        
        // Set method
        connection.requestMethod = httpOptions.method
        
        // Set timeouts
        connection.connectTimeout = httpOptions.connectTimeout
        connection.readTimeout = httpOptions.readTimeout
        
        // Set headers
        httpOptions.headers.forEach { (key, value) ->
            connection.setRequestProperty(key, value)
        }
        
        // Set parameters
        if (httpOptions.params.isNotEmpty() && httpOptions.shouldEncodeUrlParams) {
            val paramBuilder = StringBuilder()
            httpOptions.params.forEach { (key, values) ->
                values.forEach { value ->
                    if (paramBuilder.isNotEmpty()) paramBuilder.append("&")
                    paramBuilder.append("$key=$value")
                }
            }
            
            if (httpOptions.method.equals("GET", ignoreCase = true)) {
                val separator = if (urlString.contains("?")) "&" else "?"
                val newUrl = URL("$urlString$separator$paramBuilder")
                return newUrl.openConnection() as HttpURLConnection
            } else {
                connection.doOutput = true
                connection.outputStream.use { os ->
                    os.write(paramBuilder.toString().toByteArray())
                }
            }
        }
        
        // Set redirect handling
        connection.instanceFollowRedirects = !httpOptions.disableRedirects
        
        // Set SSL factory if provided
        if (httpOptions.sslSocketFactory != null && connection is javax.net.ssl.HttpsURLConnection) {
            connection.sslSocketFactory = httpOptions.sslSocketFactory
        }
        
        return connection
    }
} 