package io.ionic.libs.ionfiletransferlib.helpers

import io.ionic.libs.ionfiletransferlib.model.IONFLTRException
import java.util.regex.Pattern
import java.io.File
import java.net.URI
import java.net.URISyntaxException

internal class IONFLTRInputsValidator {

    /**
     * Validates the URL and file path for transfer operations.
     *
     * @param url The URL to validate
     * @param filePath The file path to validate
     * @throws IONFLTRException if validation fails
     */
    fun validateTransferInputs(url: String, filePath: String) {
        when {
            url.isBlank() -> throw IONFLTRException.EmptyURL(url)
            !isURLValid(url) -> throw IONFLTRException.InvalidURL(url)
            !isPathValid(filePath) -> throw IONFLTRException.InvalidPath(filePath)
        }
    }

    /**
     * Boolean method to check if a given file path is valid
     * @param path The file path to check
     * @return true if path is valid, false otherwise
     */
    private fun isPathValid(path: String?): Boolean {
        if (path.isNullOrBlank()) {
            return false
        }

        return try {
            val resolvedPath: String
            if (path.startsWith("file://")) {
                val uri = URI(path)
                if (uri.path == null) {
                    return false
                }
                resolvedPath = uri.path
            } else {
                resolvedPath = path
            }
            File(resolvedPath).isAbsolute
        } catch (e: URISyntaxException) {
            false
        }
    }

    /**
     * Boolean method to check if a given URL is valid
     * @param url The URL to check
     * @return true if URL is valid, false otherwise
     */
    private fun isURLValid(url: String): Boolean {
        val pattern =
            Pattern.compile("http[s]?://(([^/:.[:space:]]+(.[^/:.[:space:]]+)*)|([0-9](.[0-9]{3})))(:[0-9]+)?((/[^?#[:space:]]+)([^#[:space:]]+)?(#.+)?)?")
        return pattern.matcher(url).find()
    }
} 