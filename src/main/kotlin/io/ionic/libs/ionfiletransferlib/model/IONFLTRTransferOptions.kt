package io.ionic.libs.ionfiletransferlib.model

import javax.net.ssl.SSLSocketFactory

/**
 * Options for downloading a file
 * 
 * @property url The URL to download the file from
 * @property filePath The local path where the downloaded file will be saved
 * @property httpOptions Additional HTTP options for the download request
 * @property body The request body as bytes
 */
data class IONFLTRDownloadOptions(
    val url: String,
    val filePath: String,
    val httpOptions: IONFLTRTransferHttpOptions = IONFLTRTransferHttpOptions("GET"),
    val body: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IONFLTRDownloadOptions

        if (url != other.url) return false
        if (filePath != other.filePath) return false
        if (httpOptions != other.httpOptions) return false
        if (!body.contentEquals(other.body)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + filePath.hashCode()
        result = 31 * result + httpOptions.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Options for uploading a file
 * 
 * @property url The URL to upload the file to
 * @property filePath The local path of the file to upload
 * @property chunkedMode Whether to use chunked transfer encoding
 * @property mimeType The MIME type of the file (null for auto-detection)
 * @property fileKey The form field name for the file when uploading as multipart/form-data
 * @property formParams Additional form parameters to include in multipart/form-data uploads
 * @property httpOptions Additional HTTP options for the upload request
 */
data class IONFLTRUploadOptions(
    val url: String,
    val filePath: String,
    val chunkedMode: Boolean = false,
    val mimeType: String? = null,
    val fileKey: String = "file",
    val formParams: Map<String, String>? = null,
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