package io.ionic.libs.ionfiletransferlib.model

/**
 * Represents the result of a file transfer operation (upload or download)
 */
sealed class IONFLTRTransferResult {
    /**
     * Represents an ongoing transfer operation with progress information
     * 
     * @property status Current progress status information
     */
    data class Ongoing(val status: IONFLTRProgressStatus) : IONFLTRTransferResult()
    
    /**
     * Represents a completed transfer operation
     * 
     * @property data Complete transfer information
     */
    data class Complete(val data: IONFLTRTransferComplete) : IONFLTRTransferResult()
}

/**
 * Progress status information for an ongoing transfer
 * 
 * @property bytes Number of bytes transferred so far
 * @property contentLength Total size of the content in bytes, if known
 * @property lengthComputable Whether the total content length is known
 */
data class IONFLTRProgressStatus(
    val bytes: Long,
    val contentLength: Long,
    val lengthComputable: Boolean
)

/**
 * Information about a completed transfer
 * 
 * @property totalBytes Total number of bytes transferred
 * @property responseCode HTTP response code
 * @property responseBody HTTP response body (if available)
 * @property headers HTTP response headers (if available)
 */
data class IONFLTRTransferComplete(
    val totalBytes: Long,
    val responseCode: String,
    val responseBody: String?,
    val headers: Map<String, List<String>>?
) 