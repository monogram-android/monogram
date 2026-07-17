package org.monogram.data.repository

import org.monogram.data.datasource.remote.GitHubRemoteDataSource
import org.monogram.domain.models.GitHubCommitModel
import org.monogram.domain.repository.GitHubCommitRepository

class GitHubCommitRepositoryImpl(
    private val remoteDataSource: GitHubRemoteDataSource
) : GitHubCommitRepository {

    override suspend fun getRecentCommits(
        branchOrSha: String,
        limit: Int
    ): Result<List<GitHubCommitModel>> {
        return remoteDataSource.getRecentCommits(branchOrSha, limit).map { commits ->
            commits.map(::toGitHubCommitModel)
        }
    }
}

internal fun toGitHubCommitModel(
    response: GitHubRemoteDataSource.GitHubCommitResponse
): GitHubCommitModel {
    val firstMessageLine = response.commit.message.lineSequence()
        .firstOrNull()
        ?.trim()
        .orEmpty()

    return GitHubCommitModel(
        sha = response.sha,
        message = firstMessageLine.ifBlank { response.sha.take(7) },
        authorName = response.commit.author?.name?.takeIf { it.isNotBlank() } ?: "Unknown author",
        committedAt = response.commit.author?.date.orEmpty(),
        htmlUrl = response.htmlUrl
    )
}
