package metifikys.story

import metifikys.db.EventArticleRow
import metifikys.digest.VectorMath

/**
 * The hype detector (plan item 3): how many outlets carried a story and how many of them had it
 * first-hand rather than retelling someone else. Pure and deterministic — no LLM.
 *
 * A member is a reprint when (a) its title or teaser credits another outlet ([Attribution]), or
 * (b) its stored vector is at least `reprintSimilarity` close to a member of another outlet
 * published before it. An outlet is an original source when at least one of its members is not a
 * reprint. When every member credits the same outlet, none of our feeds carries the original and
 * [Result.commonSource] names it.
 */
object Hype {

    data class Result(
        val outlets: Int,
        /** Outlets with at least one member that is no reprint. */
        val originals: Int,
        /** Every member credits this one outlet (as written in the first such member); null otherwise. */
        val commonSource: String?,
        /** Article ids classified as reprints. */
        val reprints: Set<Int>
    )

    /**
     * @param texts title + teaser per member link; a member without text is judged by its vector only
     * @param vectors L2-normalized vectors per article id (one model); empty disables rule (b)
     */
    fun analyze(
        cluster: EventCluster,
        texts: Map<String, String>,
        vectors: Map<Int, FloatArray>,
        reprintSimilarity: Double
    ): Result {
        val ordered = cluster.members.sortedBy { EventCluster.timeOf(it) }
        val credited = LinkedHashMap<Int, Attribution.Mention>()
        val reprints = mutableSetOf<Int>()
        for ((i, m) in ordered.withIndex()) {
            val domain = cluster.domainOf(m)
            val credit = texts[m.link]?.let { Attribution.creditedSource(it, domain) }
            if (credit != null) {
                credited[m.articleId] = credit
                reprints += m.articleId
                continue
            }
            if (copiesEarlier(m, ordered.subList(0, i), cluster, vectors, reprintSimilarity)) reprints += m.articleId
        }
        val originals = cluster.byDomain.values.count { rows -> rows.any { it.articleId !in reprints } }
        val allCredit = cluster.members.isNotEmpty() && cluster.members.all { it.articleId in credited }
        val common = if (allCredit && credited.values.map { it.key }.distinct().size == 1) credited.values.first().source else null
        return Result(cluster.outlets, originals, common, reprints)
    }

    private fun copiesEarlier(
        m: EventArticleRow,
        earlier: List<EventArticleRow>,
        cluster: EventCluster,
        vectors: Map<Int, FloatArray>,
        threshold: Double
    ): Boolean {
        val v = vectors[m.articleId] ?: return false
        val t = EventCluster.timeOf(m)
        val domain = cluster.domainOf(m)
        return earlier.any { e ->
            EventCluster.timeOf(e) < t && cluster.domainOf(e) != domain &&
                vectors[e.articleId]?.let { VectorMath.cosine(v, it) >= threshold } == true
        }
    }
}
