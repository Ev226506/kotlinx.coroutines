/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.flow

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.internal.*

/**
 * A command emitted by [SharingStarted] implementation to control the sharing coroutine in
 * [shareIn] and [stateIn] operators.
 */
@ExperimentalCoroutinesApi
public enum class SharingCommand {
    /**
     * Start the sharing coroutine.
     */
    START,

    /**
     * Stop the sharing coroutine.
     */
    STOP,

    /**
     * Stop the sharing coroutine and [reset buffer][MutableSharedFlow.resetBuffer] of the shared flow.
     */
    RESET_BUFFER
}

/**
 * A strategy for starting and stopping sharing coroutine in [shareIn] and [stateIn] operators.
 */
@ExperimentalCoroutinesApi
public interface SharingStarted {
    public companion object {
        /**
         * Sharing is started immediately and never stops.
         */
        @ExperimentalCoroutinesApi
        public val Eagerly: SharingStarted = startedEagerly() // always init because it is a default, likely needed

        /**
         * Sharing is started when the first subscriber appears and never stops.
         */
        @ExperimentalCoroutinesApi
        public val Lazily: SharingStarted by lazy { startedLazily() }

        /**
         * Sharing is started when the first subscriber appears, immediately stops when the last
         * subscriber disappears and [resets then buffer][MutableSharedFlow.resetBuffer].
         */
        @ExperimentalCoroutinesApi
        public val WhileSubscribed: SharingStarted by lazy { startedWhileSubscribed(0L, 0L) }

        /**
         * Sharing is started when the first subscriber appears, stops when the last
         * subscriber disappears and [stopTimeoutMillis] had passed,
         * after [cacheExpirationMillis] more passed [resets then buffer][MutableSharedFlow.resetBuffer].
         *
         * This function throws [IllegalArgumentException] when either [stopTimeoutMillis] or [cacheExpirationMillis]
         * are negative.
         */
        @Suppress("FunctionName")
        @ExperimentalCoroutinesApi
        public fun WhileSubscribed(stopTimeoutMillis: Long = 0, cacheExpirationMillis: Long = 0): SharingStarted =
            startedWhileSubscribed(stopTimeoutMillis, cacheExpirationMillis)
    }

    /**
     * Transforms the [subscriptionCount][MutableSharedFlow.subscriptionCount] state of the shared flow into the
     * flow of [commands][SharingCommand] that control sharing coroutine.
     */
    public fun commandFlow(subscriptionCount: StateFlow<Int>): Flow<SharingCommand>
}

// -------------------------------- implementation --------------------------------

private val ALWAYS_STARTED = unsafeDistinctFlow { emit(SharingCommand.START) }

private fun startedEagerly() = object : SharingStarted {
    override fun commandFlow(subscriptionCount: StateFlow<Int>): Flow<SharingCommand> = ALWAYS_STARTED
}

private fun startedLazily() = object : SharingStarted {
    override fun commandFlow(subscriptionCount: StateFlow<Int>): Flow<SharingCommand> = unsafeDistinctFlow {
        var started = false
        subscriptionCount.collect { count ->
            if (count > 0 && !started) {
                started = true
                emit(SharingCommand.START)
            }
        }
    }
}

private fun startedWhileSubscribed(stopTimeout: Long = 0, cacheExpiration: Long = 0): SharingStarted {
    require(stopTimeout >= 0) { "stopTimeout cannot be negative" }
    require(cacheExpiration >= 0) { "cacheExpiration cannot be negative" }
    return object : SharingStarted {
        override fun commandFlow(subscriptionCount: StateFlow<Int>): Flow<SharingCommand> = subscriptionCount
            .transformLatest { count ->
                if (count > 0) {
                    emit(SharingCommand.START)
                } else {
                    delay(stopTimeout)
                    if (cacheExpiration > 0) {
                        emit(SharingCommand.STOP)
                        delay(cacheExpiration)
                    }
                    emit(SharingCommand.RESET_BUFFER)
                }
            }
            .dropWhile { it != SharingCommand.START } // don't emit any STOP/RESET_BUFFER to start with, only START
            .distinctUntilChanged() // just in case somebody forgets it, don't leak our multiple sending of START
    }
}
