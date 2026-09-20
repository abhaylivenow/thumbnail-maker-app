package abhay.live.now.thumbnailmaker.picker.features

object ScoringWeights {

    fun normalise(values: List<Float>): List<Float> {
        if (values.isEmpty()) return emptyList()
        val min = values.min()
        val max = values.max()
        val range = max - min
        return if (range <= 0f) {
            List(values.size) { 0.5f }
        } else {
            values.map { (it - min) / range }
        }
    }
}
