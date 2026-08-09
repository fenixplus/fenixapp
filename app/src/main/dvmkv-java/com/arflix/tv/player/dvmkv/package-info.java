/**
 * Vendored copy of media3 1.9.0's Matroska extractor with Dolby Vision profile-7 sample hooks.
 * The DV7 RPU rides in Matroska BlockAdditional, which the stock extractor
 * discards before any TrackOutput sees it — there is no app-level seam, hence the fork. Keep in
 * sync when bumping the media3 version: re-base on the new tag and re-apply the DV diff
 * (DolbyVisionSampleTransformer interface + handleBlockAdditionalData + sample-commit transform +
 * codec-string hook).
 */
@NonNullApi
package com.arflix.tv.player.dvmkv;

import androidx.media3.common.util.NonNullApi;
