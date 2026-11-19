package com.openminis.app.ui.chat

/** Tracks which entries a running request has tried since its last successful response. */
internal class ModelFallbackChain(
    entryId: String?,
    candidates: List<FallbackCandidate>,
) {
    var currentEntryId: String? = entryId
        private set

    private val pending = ArrayDeque(candidates)
    private val tried = mutableSetOf<String>()

    init {
        responseSucceeded()
    }

    fun rebind(entryId: String?, candidates: List<FallbackCandidate>) {
        currentEntryId = entryId
        pending.clear()
        pending.addAll(candidates)
        responseSucceeded()
    }

    fun responseSucceeded() {
        tried.clear()
        currentEntryId?.let(tried::add)
    }

    fun next(rebuild: (Set<String>) -> Sequence<FallbackCandidate>): FallbackCandidate? {
        val candidate = pending.removeFirstMatching { it.entryId !in tried }
            ?: rebuild(tried).firstOrNull { it.entryId !in tried }
            ?: return null
        currentEntryId = candidate.entryId
        tried.add(candidate.entryId)
        return candidate
    }

    private inline fun ArrayDeque<FallbackCandidate>.removeFirstMatching(
        matches: (FallbackCandidate) -> Boolean,
    ): FallbackCandidate? {
        while (isNotEmpty()) {
            val candidate = removeFirst()
            if (matches(candidate)) return candidate
        }
        return null
    }
}

internal fun fallbackMemberOrder(members: List<String>, currentIndex: Int): Sequence<String> =
    if (currentIndex < 0) members.asSequence()
    else (1 until members.size).asSequence().map { members[(currentIndex + it) % members.size] }
