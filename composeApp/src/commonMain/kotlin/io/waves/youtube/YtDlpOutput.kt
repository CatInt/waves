package io.waves.youtube

/** The URL yt-dlp printed, or a [YoutubeException] carrying its error line. */
internal fun parseYtDlpOutput(exitCode: Int, output: String): String {
    val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    lines.firstOrNull { it.startsWith("https://") }?.takeIf { exitCode == 0 }?.let { return it }
    val error = lines.lastOrNull { it.startsWith("ERROR:") }
        ?.removePrefix("ERROR:")?.trim()
        ?.replace(Regex("""^\[[^\]]+]\s*[\w-]+:\s*"""), "") // "[youtube] <id>: "
        ?.substringBefore(". Use --cookies")
        ?.trimEnd('.')
    throw YoutubeException(
        if (error.isNullOrEmpty()) "yt-dlp couldn't get this video's audio." else "yt-dlp: $error.",
    )
}
