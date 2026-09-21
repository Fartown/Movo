package io.github.mangi.eta.agent.model

internal data class CitationAnnotation(
    val start: Int?,
    val end: Int?,
    val url: String,
    val title: String,
)

internal object ResponsesCitationFormatter {
    fun apply(text: String, annotations: List<CitationAnnotation>): String {
        val unique = annotations
            .filter { it.url.startsWith("https://") || it.url.startsWith("http://") }
            .distinctBy { it.url }
        if (unique.isEmpty()) return text

        val valid = unique.withIndex().filter { (_, citation) ->
            citation.end != null && citation.end in 0..text.length &&
                citation.start != null && citation.start in 0..citation.end
        }
        val result = StringBuilder(text)
        valid.sortedByDescending { it.value.end }.forEach { (index, citation) ->
            result.insert(citation.end!!, " [\\[${index + 1}\\]](${citation.url.escapeMarkdownUrl()})")
        }
        val invalid = unique.filterNot(valid.map { it.value }.toSet()::contains)
        if (invalid.isNotEmpty()) {
            result.append("\n\n来源：")
            invalid.forEachIndexed { index, citation ->
                val number = unique.indexOf(citation) + 1
                val label = citation.title.ifBlank { "来源 ${index + 1}" }.escapeMarkdownLabel()
                result.append("\n- [$number] [$label](${citation.url.escapeMarkdownUrl()})")
            }
        }
        return result.toString()
    }

    private fun String.escapeMarkdownUrl(): String = replace("(", "%28")
        .replace(")", "%29").replace("<", "%3C").replace(">", "%3E")
        .replace(" ", "%20").replace("\n", "%0A").replace("\r", "%0D")

    private fun String.escapeMarkdownLabel(): String = replace("\\", "\\\\")
        .replace("[", "\\[").replace("]", "\\]")
        .replace("\n", " ").replace("\r", " ")
}
