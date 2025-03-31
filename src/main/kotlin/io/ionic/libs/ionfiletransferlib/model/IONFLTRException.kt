package io.ionic.libs.ionfiletransferlib.model

/**
 * The available exceptions that the File Transfer library can return.
 * Some of the exceptions can return a cause in case it was triggered by another source (e.g. Android OS)
 */
sealed class IONFLTRException(
    override val message: String,
    override val cause: Throwable? = null
) : Throwable(message, cause) {

    class InvalidPath(val path: String?) :
        IONFLTRException("The provided path is either null or empty.")

    class EmptyURL(val url: String?) :
        IONFLTRException("The provided url is either null or empty.")

    class InvalidURL(val url: String) : 
        IONFLTRException("The provided url is not valid.")

    class FileDoesNotExist(override val cause: Throwable? = null) :
        IONFLTRException("The specified file does not exist", cause)

    class CannotCreateDirectory(val path: String, override val cause: Throwable? = null) :
        IONFLTRException("Cannot create directory at $path", cause)

    class HttpError(val responseCode: String, val responseBody: String?, val headers: Map<String, String>?) :
        IONFLTRException("HTTP error: $responseCode")

    class ConnectionError(override val cause: Throwable?) :
        IONFLTRException("Error establishing connection", cause)

    class TransferError(override val cause: Throwable?) :
        IONFLTRException("Error during file transfer", cause)
        
    class UnknownError(override val cause: Throwable?) :
        IONFLTRException("An unknown error occurred while trying to run the operation", cause)
} 