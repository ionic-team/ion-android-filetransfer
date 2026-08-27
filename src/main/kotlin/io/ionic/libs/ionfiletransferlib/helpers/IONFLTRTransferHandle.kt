package io.ionic.libs.ionfiletransferlib.helpers

import io.ionic.libs.ionfiletransferlib.model.IONFLTRException
import java.net.HttpURLConnection

/**
 * Holds the state of a single in-flight transfer, so that it can be aborted from another thread.
 *
 * Aborting marks the transfer as aborted and disconnects the underlying connection, which unblocks
 * any read/write that is currently in progress.
 */
internal class IONFLTRTransferHandle(val id: String?) {

    @Volatile
    private var aborted: Boolean = false

    @Volatile
    private var connection: HttpURLConnection? = null

    val isAborted: Boolean get() = aborted

    /**
     * Associates the connection being used by the transfer with this handle.
     *
     * If the transfer was already aborted before the connection was created,
     * the connection is disconnected right away.
     */
    fun setConnection(connection: HttpURLConnection) {
        this.connection = connection
        if (aborted) {
            disconnectQuietly()
        }
    }

    /**
     * Marks the transfer as aborted and disconnects the underlying connection, if there is one.
     */
    fun abort() {
        aborted = true
        disconnectQuietly()
    }

    /**
     * Disconnects the underlying connection, which unblocks any read that is in progress.
     */
    private fun disconnectQuietly() {
        try {
            connection?.disconnect()
        } catch (ex: Exception) {
            // the connection is being discarded anyway; nothing to do here
        }
    }

    /**
     * @throws IONFLTRException.TransferAborted if the transfer was aborted
     */
    fun throwIfAborted() {
        if (aborted) {
            throw IONFLTRException.TransferAborted(id)
        }
    }

    /**
     * Replaces an error that was caused by aborting the transfer (e.g. the socket being closed
     * mid-read) with [IONFLTRException.TransferAborted].
     */
    fun mapErrorIfAborted(error: Throwable): Throwable =
        if (aborted) IONFLTRException.TransferAborted(id) else error
}
