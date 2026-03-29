package org.monogram.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

fun <T> Flow<T>.asResult(): Flow<AppResult<T>> {
    return this
        .map<T, AppResult<T>> {
            AppResult.Success(it)
        }
        .catch {
            if (it is CancellationException) {
                throw it
            }
            emit(AppResult.Error(AppError.Unknown(it)))
        }
}

suspend fun <T> runCatchingApp(
    block: suspend () -> T
): AppResult<T> {
    return try {
        AppResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        AppResult.Error(AppError.Unknown(t))
    }
}