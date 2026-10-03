package com.polymath.os.data.net

import com.polymath.os.domain.model.AiError
import com.polymath.os.domain.model.AiResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Rule 2: the single network boundary. Never swallows CancellationException. */
suspend fun <T> safeAiCall(block: suspend () -> T): AiResult<T> = try {
    AiResult.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: UnknownHostException) {
    AiResult.Failure(AiError.OFFLINE)
} catch (e: SocketTimeoutException) {
    AiResult.Failure(AiError.TIMEOUT)
} catch (e: IOException) {
    AiResult.Failure(AiError.OFFLINE)
} catch (e: HttpException) {
    AiResult.Failure(
        when (e.code()) {
            401, 403 -> AiError.BAD_KEY
            429 -> AiError.RATE_LIMITED
            in 500..599 -> AiError.SERVER
            else -> AiError.UNKNOWN
        },
    )
} catch (e: SerializationException) {
    AiResult.Failure(AiError.BAD_JSON)
} catch (e: IllegalArgumentException) {
    AiResult.Failure(AiError.BAD_JSON)
}
