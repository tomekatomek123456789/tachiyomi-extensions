package eu.kanade.tachiyomi.extension.en.hentaimanga

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.annotations.Nsfw
import java.text.SimpleDateFormat
import java.util.Locale

@Nsfw
class HentaiManga : Madara(
    "Hentai Manga",
    "https://hentaimanga.me",
    "en",
    dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)
) {

}
