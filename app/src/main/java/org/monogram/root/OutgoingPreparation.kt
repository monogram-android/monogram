package org.monogram.root

import kotlinx.coroutines.CancellationException
import java.io.IOException

internal class OutgoingPreparationException(cause: Exception) :
    IOException("Outgoing message could not be prepared", cause)

internal suspend fun <T> prepareOutgoing(block: suspend () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: IOException) {
    throw OutgoingPreparationException(error)
} catch (error: IllegalArgumentException) {
    throw OutgoingPreparationException(error)
} catch (error: IllegalStateException) {
    throw OutgoingPreparationException(error)
}
