package org.monogram.domain.repository

import org.monogram.domain.models.GitHubCommitModel

interface GitHubCommitRepository {
    suspend fun getRecentCommits(
        branchOrSha: String,
        limit: Int = 20
    ): Result<List<GitHubCommitModel>>
}
