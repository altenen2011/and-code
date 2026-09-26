package com.yugahashimoto.andcode.runtime.local

sealed interface LocalRuntimeOperationResult {
    data class UpdateSkipped(val version: String) : LocalRuntimeOperationResult

    data class Updated(val fromVersion: String, val toVersion: String) : LocalRuntimeOperationResult

    data class AutomaticRollback(
        val failedVersion: String,
        val restoredVersion: String,
        val reason: String,
    ) : LocalRuntimeOperationResult

    data class RolledBack(val fromVersion: String, val toVersion: String) : LocalRuntimeOperationResult

    data class RollbackFailedRestored(
        val attemptedVersion: String,
        val restoredVersion: String,
        val reason: String,
    ) : LocalRuntimeOperationResult

    data class Failed(val operation: String, val message: String) : LocalRuntimeOperationResult
}

interface LocalRuntimeUpdateEngine {
    suspend fun check(
        currentVersion: String,
        abi: String,
    ): LocalRuntimeUpdateCheck

    suspend fun prepare(
        release: LocalRuntimeRelease,
        onProgress: (Float?, String) -> Unit,
    ): PreparedRuntimeUpdate

    suspend fun activate(prepared: PreparedRuntimeUpdate): LocalRuntimeMetadata

    suspend fun commit()

    suspend fun recover(): LocalRuntimeMetadata?

    suspend fun rollback(): LocalRuntimeMetadata

    suspend fun rollbackVersion(): String?
}

class DefaultLocalRuntimeUpdateEngine(
    private val releaseClient: LocalRuntimeReleaseClient,
    private val updater: LocalRuntimeUpdater,
) : LocalRuntimeUpdateEngine {
    override suspend fun check(
        currentVersion: String,
        abi: String,
    ): LocalRuntimeUpdateCheck = releaseClient.check(currentVersion, abi)

    override suspend fun prepare(
        release: LocalRuntimeRelease,
        onProgress: (Float?, String) -> Unit,
    ): PreparedRuntimeUpdate = updater.prepare(release, onProgress)

    override suspend fun activate(prepared: PreparedRuntimeUpdate): LocalRuntimeMetadata = updater.activate(prepared)

    override suspend fun commit() = updater.commitActivation()

    override suspend fun recover(): LocalRuntimeMetadata? = updater.recoverInterruptedActivation()

    override suspend fun rollback(): LocalRuntimeMetadata = updater.rollback()

    override suspend fun rollbackVersion(): String? = updater.rollbackVersion()
}

interface LocalRuntimeOperations {
    fun currentMetadata(): LocalRuntimeMetadata?

    suspend fun stop()

    suspend fun start(): com.yugahashimoto.andcode.runtime.LocalRuntimeStatus.Ready
}

/**
 * Update engine for the npm channel (OpenCode v2 line). Version discovery goes through the npm
 * registry instead of GitHub Releases; everything downstream (prepare/activate/rollback) is the
 * shared [LocalRuntimeUpdater] machinery, driven by an asset that carries the registry
 * `sha512` integrity instead of a SHA-256 digest.
 */
class NpmLocalRuntimeUpdateEngine(
    private val releaseClient: NpmOpenCodeReleaseClient,
    private val updater: LocalRuntimeUpdater,
) : LocalRuntimeUpdateEngine {
    override suspend fun check(
        currentVersion: String,
        abi: String,
    ): LocalRuntimeUpdateCheck {
        return when (val check = releaseClient.check(currentVersion, abi)) {
            is NpmUpdateCheck.UpToDate -> LocalRuntimeUpdateCheck.UpToDate(check.currentVersion, check.latestVersion)
            is NpmUpdateCheck.Available -> {
                val release = check.release
                val sizeBytes = releaseClient.tarballSizeBytes(release.tarballUrl)
                LocalRuntimeUpdateCheck.Available(
                    check.currentVersion,
                    LocalRuntimeRelease(
                        version = release.version,
                        releaseNotes = "",
                        asset =
                            LocalRuntimeReleaseAsset(
                                name = release.tarballUrl.substringAfterLast('/'),
                                url = release.tarballUrl,
                                sha256 = "",
                                sizeBytes = sizeBytes,
                                npmIntegrity = release.integrity,
                            ),
                    ),
                )
            }
        }
    }

    override suspend fun prepare(
        release: LocalRuntimeRelease,
        onProgress: (Float?, String) -> Unit,
    ): PreparedRuntimeUpdate = updater.prepare(release, onProgress)

    override suspend fun activate(prepared: PreparedRuntimeUpdate): LocalRuntimeMetadata = updater.activate(prepared)

    override suspend fun commit() = updater.commitActivation()

    override suspend fun recover(): LocalRuntimeMetadata? = updater.recoverInterruptedActivation()

    override suspend fun rollback(): LocalRuntimeMetadata = updater.rollback()

    override suspend fun rollbackVersion(): String? = updater.rollbackVersion()
}
