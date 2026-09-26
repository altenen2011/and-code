package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Shared asset downloader for the in-app OpenCode updater. GitHub-channel assets verify a
 * SHA-256 hex digest; npm-channel assets (OpenCode v2 line) verify the registry `sha512`
 * integrity instead. Everything else — resume, size gating, progress — is identical.
 */
internal fun verifiedUpdateDownload(
    downloader: VerifiedRuntimeDownloader,
): suspend (asset: LocalRuntimeReleaseAsset, destination: File, progress: (Float?) -> Unit) -> Unit =
    { asset, destination, progress ->
        val npmIntegrity = asset.npmIntegrity
        if (npmIntegrity != null) {
            downloader.download(
                url = asset.url,
                destination = destination,
                expectedSizeBytes = asset.sizeBytes.takeIf { it > 0L },
                verify = { file -> RuntimeArchive.verifyNpmIntegrity(file, npmIntegrity) },
                onProgress = progress,
            )
        } else {
            downloader.download(
                url = asset.url,
                destination = destination,
                expectedSha256 = asset.sha256,
                expectedSizeBytes = asset.sizeBytes,
                onProgress = progress,
            )
        }
    }
