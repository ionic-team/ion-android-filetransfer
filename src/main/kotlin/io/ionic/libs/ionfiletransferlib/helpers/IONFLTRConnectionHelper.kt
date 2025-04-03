package io.ionic.libs.ionfiletransferlib.helpers

import io.ionic.libs.ionfiletransferlib.model.IONFLTRTransferHttpOptions
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Helper class for setting up HTTP connections with proper configuration.
 */
class IONFLTRConnectionHelper {
    /**
     * Sets up the HTTP connection with the provided options.
     */
    fun setupConnection(urlString: String, httpOptions: IONFLTRTransferHttpOptions): HttpURLConnection {
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
        if (httpOptions.params.isNotEmpty()) {
            val paramString = buildString {
                httpOptions.params.forEach { (key, values) ->
                    values.forEach { value ->
                        if (isNotEmpty()) append("&")
                        val encodedKey = if (httpOptions.shouldEncodeUrlParams) {
                            java.net.URLEncoder.encode(key, StandardCharsets.UTF_8.name())
                        } else key
                        val encodedValue = if (httpOptions.shouldEncodeUrlParams) {
                            java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                        } else value
                        append("$encodedKey=$encodedValue")
                    }
                }
            }
            
            if (httpOptions.method.equals("GET", ignoreCase = true)) {
                val separator = if (urlString.contains("?")) "&" else "?"
                val newUrl = URL("$urlString$separator$paramString")
                return newUrl.openConnection() as HttpURLConnection
            } else {
                connection.doOutput = true
                connection.outputStream.use { os ->
                    os.write(paramString.toByteArray())
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