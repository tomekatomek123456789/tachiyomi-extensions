package eu.kanade.tachiyomi.multisrc.wpmangareader

import generator.ThemeSourceData.SingleLang
import generator.ThemeSourceGenerator

class WPMangaReaderGenerator : ThemeSourceGenerator {

    override val themePkg = "wpmangareader"

    override val themeClass = "WPMangaReader"

    override val baseVersionCode: Int = 8

    override val sources = listOf(
        SingleLang("Kiryuu", "https://kiryuu.id", "id", overrideVersionCode = 1),
        SingleLang("KomikMama", "https://komikmama.net", "id"),
        SingleLang("MangaKita", "https://mangakita.net", "id", overrideVersionCode = 1),
        SingleLang("Gabut Scans", "https://gabutscans.com", "id"),
        SingleLang("Martial Manga", "https://martialmanga.com/", "es"),
        SingleLang("Ngomik", "https://ngomik.net", "id", overrideVersionCode = 1),
        SingleLang("Sekaikomik", "https://www.sekaikomik.xyz", "id", isNsfw = true, overrideVersionCode = 6),
        SingleLang("Davey Scans", "https://daveyscans.com/", "id"),
        SingleLang("Mangasusu", "https://mangasusu.co.in", "id", isNsfw = true),
        SingleLang("TurkToon", "https://turktoon.com", "tr"),
        SingleLang("Gecenin Lordu", "https://geceninlordu.com/", "tr", overrideVersionCode = 1),
        SingleLang("A Pair of 2+", "https://pairof2.com", "en", className = "APairOf2"),
        SingleLang("PMScans", "https://reader.pmscans.com", "en"),
        SingleLang("Skull Scans", "https://www.skullscans.com", "en"),
        SingleLang("Luminous Scans", "https://www.luminousscans.com", "en"),
        SingleLang("Azure Scans", "https://azuremanga.com", "en"),
        SingleLang("ReaperScans.fr (GS)", "https://reaperscans.fr", "fr", className  = "ReaperScansFR", pkgName = "gsnation", overrideVersionCode = 2),
        SingleLang("YugenMangas", "https://yugenmangas.com", "es"),
        SingleLang("DragonTranslation", "https://dragontranslation.com", "es", isNsfw = true, overrideVersionCode = 1),
        SingleLang("Patatescans", "https://patatescans.com", "fr", isNsfw = true, overrideVersionCode = 1),
        SingleLang("Fusion Scanlation", "https://fusionscanlation.com", "es", className = "FusionScanlation", overrideVersionCode = 1),
        SingleLang("Ace Scans", "https://acescans.xyz", "en", isNsfw = true, overrideVersionCode = 1),
        SingleLang("Silence Scan", "https://silencescan.com.br", "pt-BR", isNsfw = true, overrideVersionCode = 5),
    )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            WPMangaReaderGenerator().createAll()
        }
    }
}
