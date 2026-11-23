package com.margelo.nitro.syncprovider.callbacks

import android.util.Log
import com.margelo.nitro.syncprovider.CallbackAnswer
import com.margelo.nitro.syncprovider.CallbackAnswerStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * The kinds of JS callback, with the label their request ids and log lines carry, and the
 * [CallbackAnswer] field a RESOLVED answer of the kind must have.
 */
internal enum class CallbackKind(val label: String, val payload: String) {
  RETRY_DECIDER("retryDecider", "decision"),
  HEADER_RESOLVER("headerResolver", "headers"),
  BEFORE_DISPATCH("beforeDispatch", "beforeDispatch"),
  AFTER_DISPATCH("afterDispatch", "answer"),
}

/** What a [JsCallbackGateway.request] came back with. */
internal sealed interface CallbackResult<out T> {
  /** JS answered RESOLVED with what the kind expects. */
  data class Answered<T>(val value: T) : CallbackResult<T>

  /** No usable answer. The gateway logged why; the caller applies its fallback. */
  data class Skipped(val reason: SkipReason) : CallbackResult<Nothing>
}

/** Why a request has no usable answer. */
internal enum class SkipReason {
  /** Calling the JS function threw. */
  INVOKE_FAILED,

  /** JS answered FAILED: the function threw, rejected, or returned what the facade refused. */
  ANSWER_FAILED,

  /** JS answered RESOLVED without the field its kind expects, or with a value native refuses. */
  INVALID_ANSWER,

  /** No answer within the timeout: a hung JS thread or a runtime gone, which look the same. */
  TIMED_OUT,

  /**
   * The hybrid that installed the function was torn down, or a newer one attached: its JS runtime
   * is gone. Handled as [TIMED_OUT] is, without waiting for the timeout.
   */
  OWNER_GONE,

  /** The work the request serves was stopped, a flush cycle WorkManager stopped say. */
  STOPPED,
}

/** Where the gateway logs, by Android log priority. Never given a header, a body or a context. */
internal fun interface CallbackLog {
  fun log(priority: Int, message: String)

  companion object {
    /** The tag of the library's callback log lines. */
    const val TAG = "SyncCallbacks"

    val ANDROID = CallbackLog { priority, message -> Log.println(priority, TAG, message) }
  }
}

/**
 * The one way native asks JS something and waits for the answer, shared by every callback kind.
 *
 * The JS functions return nothing. Native calls one with a fresh request id, and the facade
 * answers later through `answerCallback(requestId, answer)`, which lands in [answer]. A request
 * sees an answer, an answer of FAILED, or none within its timeout; native cannot tell a hung JS
 * thread from a runtime a reload replaced, so both are a timeout. A late, unknown or repeated
 * answer does nothing.
 *
 * Each wait races a [CompletableDeferred] against its timeout. That await is cancellable, so the
 * timeout fires even inside `NonCancellable`; a Nitro `Promise` is never awaited, its `await()`
 * not being cancellable. The wait runs on [waitContext], so the timeout is real time whoever
 * calls. A request is registered before JS is called, and removed when it ends.
 *
 * No lock is held while JS is called or awaited. [answer], which runs on the JS thread, only
 * looks its request up and completes it.
 *
 * @param isOwnerLive whether the hybrid that installed a function is still the current one. A
 *   request of an owner that is gone ends at once, without calling JS, and
 *   [endWaitsOfGoneOwners] ends those already waiting.
 * @param scope where [notify] waits for its answers. Never the caller's: a caller must not wait.
 * @param notifyContext where [notify] calls JS: Main, the way item events reach their listeners,
 *   so a notification follows the item event before it.
 */
internal class JsCallbackGateway(
  private val isOwnerLive: (owner: Long) -> Boolean = { true },
  private val scope: CoroutineScope = defaultScope(),
  private val waitContext: CoroutineContext = Dispatchers.Default,
  private val notifyContext: CoroutineContext = Dispatchers.Main,
  private val log: CallbackLog = CallbackLog.ANDROID,
) {

  private val pending = ConcurrentHashMap<String, Pending>()
  private val ids = AtomicLong(0L)

  /** Requests registered and not yet ended. For tests. */
  val pendingCount: Int get() = pending.size

  /**
   * Call JS through [invoke] with a new request id, and wait for its answer: at most
   * [timeoutMs], and no longer than [stop] stays active.
   *
   * It never throws because of anything JS does: every outcome but an answer [parse] accepts is
   * a [CallbackResult.Skipped], logged with [consequence], what the caller then does. An `Error`
   * that [invoke] raises is not caught, and a cancellation of the caller ends the wait with it.
   *
   * @param owner the generation of the hybrid that installed the function [invoke] calls.
   * @param stop cancelled when the work this request serves stops. The wait then ends at once,
   *   and JS is not called if it already had.
   * @param parse the value a RESOLVED answer carries for this kind, or null when it carries none
   *   native accepts.
   */
  @Suppress("LongParameterList")
  suspend fun <T : Any> request(
    kind: CallbackKind,
    owner: Long,
    itemId: String,
    timeoutMs: Long,
    stop: Job?,
    consequence: String,
    parse: (CallbackAnswer) -> T?,
    invoke: (requestId: String) -> Unit,
  ): CallbackResult<T> {
    val id = nextId(kind)
    val done = CompletableDeferred<Completion>()
    // Before JS can answer, and before an eviction can look for it.
    pending[id] = Pending(owner, done)
    // Runs at once when the stop has already happened.
    val onStop = stop?.invokeOnCompletion { cause -> if (cause != null) done.complete(Completion.Stopped) }
    try {
      if (!isOwnerLive(owner)) done.complete(Completion.OwnerGone)
      if (!done.isCompleted) {
        try {
          invoke(id)
        } catch (e: Exception) {
          return skip(kind, itemId, id, SkipReason.INVOKE_FAILED, "the call failed: ${describe(e)}", consequence)
        }
      }
      val completion = withContext(waitContext) { withTimeoutOrNull(timeoutMs) { done.await() } }
      return classify(kind, itemId, id, timeoutMs, consequence, completion, parse)
    } finally {
      pending.remove(id)
      onStop?.dispose()
    }
  }

  /**
   * Call JS through [invoke] and return at once: nothing waits for the answer. JS is called on
   * [notifyContext]. An answer of FAILED, or none within [timeoutMs], is logged; nothing else
   * follows from it.
   */
  fun notify(
    kind: CallbackKind,
    owner: Long,
    itemId: String,
    timeoutMs: Long,
    invoke: (requestId: String) -> Unit,
  ) {
    val id = nextId(kind)
    val done = CompletableDeferred<Completion>()
    pending[id] = Pending(owner, done)
    if (!isOwnerLive(owner)) done.complete(Completion.OwnerGone)
    val watch = scope.launch(notifyContext) {
      if (!done.isCompleted) {
        try {
          invoke(id)
        } catch (e: Exception) {
          skip(kind, itemId, id, SkipReason.INVOKE_FAILED, "the call failed: ${describe(e)}", NOTIFIED)
          return@launch
        }
      }
      val completion = withContext(waitContext) { withTimeoutOrNull(timeoutMs) { done.await() } }
      // A RESOLVED answer needs no payload here: only a failure is worth a line.
      classify(kind, itemId, id, timeoutMs, NOTIFIED, completion) { }
    }
    // Also when the watch never ran, its scope cancelled.
    watch.invokeOnCompletion { pending.remove(id) }
  }

  /**
   * The facade's answer to request [requestId], from `answerCallback` on the JS thread. It only
   * completes the request; a late, unknown or repeated id does nothing, and nothing throws.
   */
  fun answer(requestId: String, answer: CallbackAnswer) {
    pending.remove(requestId)?.done?.complete(Completion.Answer(answer))
  }

  /** End at once every wait whose owner is no longer live, as if no answer came. */
  fun endWaitsOfGoneOwners() {
    pending.values.forEach { if (!isOwnerLive(it.owner)) it.done.complete(Completion.OwnerGone) }
  }

  /** End every wait at once, as if no answer came. */
  fun endAllWaits() {
    pending.values.forEach { it.done.complete(Completion.OwnerGone) }
  }

  @Suppress("LongParameterList")
  private fun <T : Any> classify(
    kind: CallbackKind,
    itemId: String,
    id: String,
    timeoutMs: Long,
    consequence: String,
    completion: Completion?,
    parse: (CallbackAnswer) -> T?,
  ): CallbackResult<T> = when (completion) {
    null -> skip(kind, itemId, id, SkipReason.TIMED_OUT, "no answer within $timeoutMs ms", consequence)
    Completion.Stopped -> skip(
      kind, itemId, id, SkipReason.STOPPED, "the cycle stopped", consequence, Log.INFO,
    )
    Completion.OwnerGone -> skip(
      kind, itemId, id, SkipReason.OWNER_GONE, "the JS runtime that installed it is gone", consequence,
    )
    is Completion.Answer -> {
      val answer = completion.answer
      if (answer.status == CallbackAnswerStatus.FAILED) {
        skip(kind, itemId, id, SkipReason.ANSWER_FAILED, "answered FAILED: ${jsMessage(answer.message)}", consequence)
      } else {
        parse(answer)?.let { CallbackResult.Answered(it) }
          ?: skip(
            kind, itemId, id, SkipReason.INVALID_ANSWER, "the answer carried no usable ${kind.payload}",
            consequence,
          )
      }
    }
  }

  @Suppress("LongParameterList")
  private fun skip(
    kind: CallbackKind,
    itemId: String,
    id: String,
    reason: SkipReason,
    why: String,
    consequence: String,
    priority: Int = Log.WARN,
  ): CallbackResult.Skipped {
    log.log(priority, "${kind.label} for item $itemId ($id): $why; $consequence")
    return CallbackResult.Skipped(reason)
  }

  private fun nextId(kind: CallbackKind): String = "${kind.label}:${ids.incrementAndGet()}"

  /** One registered request: the generation of the hybrid it is for, and its outcome. */
  private class Pending(val owner: Long, val done: CompletableDeferred<Completion>)

  private sealed interface Completion {
    class Answer(val answer: CallbackAnswer) : Completion
    data object Stopped : Completion
    data object OwnerGone : Completion
  }

  companion object {
    /** The longest JS message a log line repeats. */
    const val MAX_LOGGED_MESSAGE = 256

    /** What follows a notification that failed: nothing. */
    private const val NOTIFIED = "nothing changes"

    private fun jsMessage(message: String?): String =
      message?.take(MAX_LOGGED_MESSAGE)?.takeIf { it.isNotEmpty() } ?: "(no message)"

    private fun describe(e: Exception): String =
      e.javaClass.simpleName + (e.message?.let { ": ${jsMessage(it)}" } ?: "")

    /** Where notifications wait for their answers, unless a test gives its own scope. */
    fun defaultScope(): CoroutineScope = CoroutineScope(
      SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
        Log.w(CallbackLog.TAG, "A callback notification failed: ${t.javaClass.simpleName}")
      },
    )
  }
}
