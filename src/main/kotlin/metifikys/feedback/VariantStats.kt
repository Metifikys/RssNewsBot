package metifikys.feedback

import metifikys.db.DigestMessageRow
import metifikys.db.VariantPost
import java.util.Locale

/**
 * Reactions per experiment arm (`digest_messages.variant`: the persona A/B and the anti-bubble
 * `explore` posts) for `/status`. Pure — built from rows already fetched.
 *
 * Per arm: posts, reactions per post, the share of posts with any reaction, and the share of
 * reactions whose valence ([ReactionValence]) is positive. With ~one reader-operator and about one
 * reaction per post the numbers move slowly; the persona decision needs ≥40 posts per arm.
 */
object VariantStats {

    data class Arm(
        val variant: String,
        val posts: Int,
        val reactions: Int,
        val reactedPosts: Int,
        val positiveReactions: Int
    ) {
        val perPost: Double get() = if (posts == 0) 0.0 else reactions.toDouble() / posts
        val reactedShare: Double get() = if (posts == 0) 0.0 else reactedPosts.toDouble() / posts
        val positiveShare: Double? get() = if (reactions == 0) null else positiveReactions.toDouble() / reactions
    }

    /** Arms in display order: the persona pair first, then explore, then anything else. */
    private val ORDER = listOf(DigestMessageRow.VARIANT_PERSONA, DigestMessageRow.VARIANT_CONTROL, DigestMessageRow.VARIANT_EXPLORE)

    /** Category → its arms, in [ORDER]. */
    fun aggregate(posts: List<VariantPost>, valence: ReactionValence): Map<String, List<Arm>> =
        posts.groupBy { it.category }.mapValues { (_, catPosts) ->
            catPosts.groupBy { it.variant }
                .map { (variant, list) ->
                    Arm(
                        variant = variant,
                        posts = list.size,
                        reactions = list.sumOf { p -> p.reactions.sumOf { it.count } },
                        reactedPosts = list.count { p -> p.reactions.any { it.count > 0 } },
                        positiveReactions = list.sumOf { p -> p.reactions.filter { valence.of(it.emoji) > 0.0 }.sumOf { it.count } }
                    )
                }
                .sortedBy { ORDER.indexOf(it.variant).let { i -> if (i < 0) ORDER.size else i } }
        }

    /** `persona 12 · 1.3/post · 75% reacted · 88% 👍+` — one arm as a status cell. */
    fun cell(arm: Arm): String {
        val positive = arm.positiveShare?.let { " · ${pct(it)} 👍+" } ?: ""
        return "${arm.variant} ${arm.posts} · ${"%.1f".format(Locale.ROOT, arm.perPost)}/post · ${pct(arm.reactedShare)} reacted$positive"
    }

    private fun pct(x: Double): String = "${Math.round(x * 100)}%"
}
