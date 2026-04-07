package cn.aing.uptags.repository

sealed interface SaveResult {
    data class Success(val version: Long, val updatedAt: Long) : SaveResult

    data class Conflict(val latest: PlayerDataSnapshot?) : SaveResult

    data class Failure(val message: String, val cause: Throwable? = null) : SaveResult
}
