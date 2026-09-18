package com.irondigital.spindle.data.tagging

/**
 * The minimum a track has to expose for its filename to be read against it.
 *
 * Plain values rather than a Track, for the same reason the duplicate finder
 * takes its own candidate type: the judgment is worth testing on its own, and
 * nothing here needs a MediaStore row behind it.
 */
data class TagCandidate(
    val mediaId: String,
    val title: String,
    val artist: String,
    val fileName: String,
)

/**
 * A correction the filename appears to offer. A null field means the filename
 * had nothing better to say than what the library already shows, and that field
 * is left exactly as it is.
 */
data class TagProposal(
    val mediaId: String,
    val fileName: String,
    val currentTitle: String,
    val currentArtist: String,
    val title: String? = null,
    val artist: String? = null,
    val trackNumber: Int? = null,
) {
    val proposedTitle: String get() = title ?: currentTitle
    val proposedArtist: String get() = artist ?: currentArtist
}

/**
 * Works out which tracks would be improved by reading their filenames.
 *
 * Two rules keep the list short enough to be worth reading. A track whose tags
 * look genuinely present is never touched, however tempting its filename looks
 * — a correctly tagged file is not a problem to be solved. And a filename that
 * only repeats what the library already shows produces nothing, because a list
 * padded with no-op rows is a list nobody checks before hitting apply.
 */
object TagProposals {

    fun propose(candidates: List<TagCandidate>): List<TagProposal> =
        candidates.mapNotNull(::proposeFor)

    fun proposeFor(candidate: TagCandidate): TagProposal? {
        if (!FilenameTagger.looksUntagged(candidate.title, candidate.artist, candidate.fileName)) {
            return null
        }

        val tags = FilenameTagger.parse(candidate.fileName)
        if (tags.isEmpty) return null

        val title = tags.title?.takeIf {
            it.isNotBlank() && !it.equals(candidate.title, ignoreCase = true)
        }
        val artist = tags.artist?.takeIf {
            it.isNotBlank() && !it.equals(candidate.artist, ignoreCase = true)
        }

        // A track number on its own is too thin a reason to put a row in front
        // of someone; it only rides along with a real name correction.
        if (title == null && artist == null) return null

        return TagProposal(
            mediaId = candidate.mediaId,
            fileName = candidate.fileName,
            currentTitle = candidate.title,
            currentArtist = candidate.artist,
            title = title,
            artist = artist,
            trackNumber = tags.trackNumber,
        )
    }
}
