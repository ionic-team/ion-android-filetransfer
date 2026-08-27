package io.ionic.libs.ionfiletransferlib.helpers

import io.ionic.libs.ionfiletransferlib.model.IONFLTRException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException

internal inline fun <T> T.runCatchingIONFLTRExceptions(block: T.() -> Unit): Result<Unit> =
    runCatching(block).mapErrorToIONFLTRException()

internal fun <R> Result<R>.mapErrorToIONFLTRException(): Result<R> =
    exceptionOrNull()?.let { throwable ->
        // coroutine cancellation must never be swallowed, so that structured concurrency is kept
        if (throwable is CancellationException) throw throwable
        val mappedException: IONFLTRException = when (throwable) {
            is IONFLTRException -> throwable
            is FileNotFoundException -> IONFLTRException.FileDoesNotExist(throwable)
            is ConnectException, is SocketTimeoutException -> IONFLTRException.ConnectionError(throwable)
            is IOException -> IONFLTRException.TransferError(throwable)
            else -> IONFLTRException.UnknownError(throwable)
        }
        Result.failure(mappedException)
    } ?: this 