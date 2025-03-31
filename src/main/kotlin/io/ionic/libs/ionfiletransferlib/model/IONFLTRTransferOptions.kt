package io.ionic.libs.ionfiletransferlib.model

import javax.net.ssl.SSLSocketFactory

/**
 * Options for downloading a file
 * 
 * @property url The URL to download the file from
 * @property filePath The local path where the downloaded file will be saved
 * @property httpOptions Additional HTTP options for the download request
 */
data class IONFLTRDownloadOptions(
    val url: String,
    val filePath: String,
    val httpOptions: IONFLTRTransferHttpOptions = IONFLTRTransferHttpOptions("GET")
)

/**
 * Options for uploading a file
 * 
 * @property url The URL to upload the file to
 * @property filePath The local path of the file to upload
 * @property chunkedMode Whether to use chunked transfer encoding
 * @property mimeType The MIME type of the file (null for auto-detection)
 * @property fileKey The form field name for the file when uploading as multipart/form-data
 * @property httpOptions Additional HTTP options for the upload request
 */
data class IONFLTRUploadOptions(
    val url: String,
    val filePath: String,
    val chunkedMode: Boolean = false,
    val mimeType: String? = null,
    val fileKey: String = "file",
    val httpOptions: IONFLTRTransferHttpOptions = IONFLTRTransferHttpOptions("POST")
)

/**
 * HTTP options for file transfer operations
 * 
 * @property method The HTTP method (GET, POST, etc.)
 * @property headers HTTP headers to include in the request
 * @property params Additional parameters for the request
 * @property shouldEncodeUrlParams Whether to URL-encode the parameters
 * @property readTimeout Read timeout in milliseconds
 * @property connectTimeout Connection timeout in milliseconds
 * @property disableRedirects Whether to disable automatic redirects
 * @property sslSocketFactory Custom SSL socket factory (optional)
 */
data class IONFLTRTransferHttpOptions(
    val method: String,
    val headers: Map<String, String> = emptyMap(),
    val params: Map<String, Array<String>> = emptyMap(),
    val shouldEncodeUrlParams: Boolean = true,
    val readTimeout: Int = 60_000,
    val connectTimeout: Int = 60_000,
    val disableRedirects: Boolean = false,
    val sslSocketFactory: SSLSocketFactory? = null
) 