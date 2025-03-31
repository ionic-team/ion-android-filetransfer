package io.ionic.libs.ionfiletransferlib.helpers

import java.util.regex.Pattern

class IONFLTRInputsValidator {
    /**
     * Boolean method to check if a given file path is valid
     * @param path The file path to check
     * @return true if path is valid, false otherwise
     */
    fun isPathValid(path: String?): Boolean {
        return !path.isNullOrBlank()
    }

    /**
     * Boolean method to check if a given URL is valid
     * @param url The URL to check
     * @return true if URL is valid, false otherwise
     */
    fun isURLValid(url: String): Boolean {
        val pattern =
            Pattern.compile("http[s]?://(([^/:.[:space:]]+(.[^/:.[:space:]]+)*)|([0-9](.[0-9]{3})))(:[0-9]+)?((/[^?#[:space:]]+)([^#[:space:]]+)?(#.+)?)?")
        return pattern.matcher(url).find()
    }
} 