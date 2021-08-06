package eu.kanade.tachiyomi.extension.zh.copymanga

import android.app.Application
import android.content.SharedPreferences
import com.luhuiguo.chinese.ChineseUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class CopyManga : ConfigurableSource, HttpSource() {

    override val name = "拷贝漫画"
    override val baseUrl = "https://www.copymanga.com"
    override val lang = "zh"
    override val supportsLatest = true
    private val popularLatestPageSize = 50 // default
    private val searchPageSize = 12 // default

    val replaceToMirror2 = Regex("1767566263\\.rsc\\.cdn77\\.org")
    val replaceToMirror = Regex("1025857477\\.rsc\\.cdn77\\.org")

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }
    private val trustManager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return emptyArray()
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        }
    }
    private val sslContext = SSLContext.getInstance("SSL").apply {
        init(null, arrayOf(trustManager), SecureRandom())
    }

    override val client: OkHttpClient = super.client.newBuilder()
        .sslSocketFactory(sslContext.socketFactory, trustManager)
        .build()

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/comics?ordering=-popular&offset=${(page - 1) * popularLatestPageSize}&limit=$popularLatestPageSize", headers)
    override fun popularMangaParse(response: Response): MangasPage = parseSearchMangaWithFilterOrPopularOrLatestResponse(response)
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/comics?ordering=-datetime_updated&offset=${(page - 1) * popularLatestPageSize}&limit=$popularLatestPageSize", headers)
    override fun latestUpdatesParse(response: Response): MangasPage = parseSearchMangaWithFilterOrPopularOrLatestResponse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // when perform html search, sort by popular
        // val apiUrlString = "$baseUrl/api/kb/web/search/comics?limit=$searchPageSize&offset=${(page - 1) * searchPageSize}&platform=2&q=$query&q_type="
        val apiUrlString = "$baseUrl/api/v3/search/comic?limit=$searchPageSize&offset=${(page - 1) * searchPageSize}&platform=2&q=$query&q_type="
        val htmlUrlString = "$baseUrl/comics?offset=${(page - 1) * popularLatestPageSize}&limit=$popularLatestPageSize"
        val requestUrlString: String

        val params = filters.map {
            if (it is MangaFilter) {
                it.toUriPart()
            } else ""
        }.filter { it != "" }.joinToString("&")
        // perform html search only when do have filter and not search anything
        if (params != "" && query == "") {
            requestUrlString = "$htmlUrlString&$params"
        } else {
            requestUrlString = apiUrlString
        }
        val url = requestUrlString.toHttpUrlOrNull()?.newBuilder()
        return GET(url.toString(), headers)
    }
    override fun searchMangaParse(response: Response): MangasPage {
        if (response.headers("content-type").filter { it.contains("json", true) }.any()) {
            // result from api request
            return parseSearchMangaResponseAsJson(response)
        } else {
            // result from html request
            return parseSearchMangaWithFilterOrPopularOrLatestResponse(response)
        }
    }

    override fun mangaDetailsRequest(manga: SManga) = GET(baseUrl + manga.url, headers)
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        var _title: String = document.select("div.comicParticulars-title-right > ul > li:eq(0) ").first().text()
        if (preferences.getBoolean(SHOW_Simplified_Chinese_TITLE_PREF, false)) {
            _title = ChineseUtils.toSimplified(_title)
        }
        val manga = SManga.create().apply {
            title = _title
            var picture = document.select("div.comicParticulars-title-left img").first().attr("data-src")
            if (!preferences.getBoolean(change_cdn_tomainland, false)) {
                picture = replaceToMirror2.replace(picture, "mirror2.mangafunc.fun")
                picture = replaceToMirror.replace(picture, "mirror.mangafunc.fun")
            }
            thumbnail_url = picture
            description = document.select("div.comicParticulars-synopsis p.intro").first().text().trim()
        }

        val items = document.select("div.comicParticulars-title-right ul li")
        if (items.size >= 7) {
            manga.author = items[2].select("a").map { i -> i.text().trim() }.joinToString(", ")
            manga.status = when (items[5].select("span.comicParticulars-right-txt").first().text().trim()) {
                "已完結" -> SManga.COMPLETED
                "連載中" -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
            manga.genre = items[6].select("a").map { i -> i.text().trim().trim('#') }.joinToString(", ")
        }
        return manga
    }

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val disposableData = document.select("div.disposableData").first().attr("disposable")
        val disposablePass = document.select("div.disposablePass").first().attr("disposable")

        val chapterJsonString = decryptChapterData(disposableData, disposablePass)
        // default > groups > 全部 []
        val chapterJson = JSONObject(chapterJsonString)
        var chapterArray = chapterJson.optJSONObject("default")?.optJSONObject("groups")?.optJSONArray("全部")
        if (chapterArray == null) {
            return listOf()
        }

        val retDefault = ArrayList<SChapter>(chapterArray.length())
        for (i in 0 until chapterArray.length()) {
            val chapter = chapterArray.getJSONObject(i)
            retDefault.add(
                SChapter.create().apply {
                    name = chapter.getString("name")
                    date_upload = stringToUnixTimestamp(chapter.getString("datetime_created")) * 1000
                    url = "/comic/${chapter.getString("comic_path_word")}/chapter/${chapter.getString("uuid")}"
                }
            )
        }

        // {others} > groups > 全部 []
        val retOthers = ArrayList<SChapter>()
        for (categroy in chapterJson.keys()) {
            if (categroy != "default") {
                chapterArray = chapterJson.optJSONObject(categroy)?.optJSONObject("groups")?.optJSONArray("全部")
                if (chapterArray == null) {
                    continue
                }
                for (i in 0 until chapterArray.length()) {
                    val chapter = chapterArray.getJSONObject(i)
                    retOthers.add(
                        SChapter.create().apply {
                            name = chapter.getString("name")
                            date_upload = stringToUnixTimestamp(chapter.getString("datetime_created")) * 1000
                            url = "/comic/${chapter.getString("comic_path_word")}/chapter/${chapter.getString("uuid")}"
                        }
                    )
                }
            }
        }

        // place others to top, as other group updates not so often
        retDefault.addAll(0, retOthers)
        return retDefault.asReversed()
    }

    override fun pageListRequest(chapter: SChapter) = GET(baseUrl + chapter.url, headers)
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val disposableData = document.select("div.disposableData").first().attr("disposable")
        val disposablePass = document.select("div.disposablePass").first().attr("disposable")

        val pageJsonString = decryptChapterData(disposableData, disposablePass)
        val pageArray = JSONArray(pageJsonString)

        val ret = ArrayList<Page>(pageArray.length())
        for (i in 0 until pageArray.length()) {
            var page = pageArray.getJSONObject(i).getString("url")
            if (!preferences.getBoolean(change_cdn_tomainland, false)) {
                page = replaceToMirror2.replace(page, "mirror2.mangafunc.fun")
                page = replaceToMirror.replace(page, "mirror.mangafunc.fun")
            }
            ret.add(Page(i, "", page))
        }

        return ret
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/56.0.2924.87 Safari/537.36")

    // Unused, we can get image urls directly from the chapter page
    override fun imageUrlParse(response: Response) =
        throw UnsupportedOperationException("This method should not be called!")

    // Copymanga has different logic in polular and search page, mix two logic in search progress for now
    override fun getFilterList() = FilterList(
        MangaFilter(
            "题材",
            "theme",
            arrayOf(
                Pair("全部", ""),
                Pair("愛情", "aiqing"),
                Pair("歡樂向", "huanlexiang"),
                Pair("冒险", "maoxian"),
                Pair("百合", "baihe"),
                Pair("東方", "dongfang"),
                Pair("奇幻", "qihuan"),
                Pair("校园", "xiaoyuan"),
                Pair("科幻", "kehuan"),
                Pair("生活", "shenghuo"),
                Pair("轻小说", "qingxiaoshuo"),
                Pair("格鬥", "gedou"),
                Pair("神鬼", "shengui"),
                Pair("悬疑", "xuanyi"),
                Pair("耽美", "danmei"),
                Pair("其他", "qita"),
                Pair("舰娘", "jianniang"),
                Pair("职场", "zhichang"),
                Pair("治愈", "zhiyu"),
                Pair("萌系", "mengxi"),
                Pair("四格", "sige"),
                Pair("伪娘", "weiniang"),
                Pair("竞技", "jingji"),
                Pair("搞笑", "gaoxiao"),
                Pair("長條", "changtiao"),
                Pair("性转换", "xingzhuanhuan"),
                Pair("侦探", "zhentan"),
                Pair("节操", "jiecao"),
                Pair("热血", "rexue"),
                Pair("美食", "meishi"),
                Pair("後宮", "hougong"),
                Pair("励志", "lizhi"),
                Pair("音乐舞蹈", "yinyuewudao"),
                Pair("彩色", "COLOR"),
                Pair("AA", "aa"),
                Pair("异世界", "yishijie"),
                Pair("历史", "lishi"),
                Pair("战争", "zhanzheng"),
                Pair("机战", "jizhan"),
                Pair("C97", "comiket97"),
                Pair("C96", "comiket96"),
                Pair("宅系", "zhaixi"),
                Pair("C98", "C98"),
                Pair("C95", "comiket95"),
                Pair("恐怖", "%E6%81%90%E6%80 %96"),
                Pair("FATE", "fate"),
                Pair("無修正", "Uncensored"),
                Pair("穿越", "chuanyue"),
                Pair("武侠", "wuxia"),
                Pair("生存", "shengcun"),
                Pair("惊悚", "jingsong"),
                Pair("都市", "dushi"),
                Pair("LoveLive", "loveLive"),
                Pair("转生", "zhuansheng"),
                Pair("重生", "chongsheng"),
                Pair("仙侠", "xianxia")
            )
        ),
        MangaFilter(
            "排序",
            "ordering",
            arrayOf(
                Pair("最热门", "-popular"),
                Pair("最冷门", "popular"),
                Pair("最新", "-datetime_updated"),
                Pair("最早", "datetime_updated"),
            )
        ),
    )

    private class MangaFilter(
        displayName: String,
        searchName: String,
        val vals: Array<Pair<String, String>>,
        defaultValue: Int = 0
    ) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), defaultValue) {
        val searchName = searchName
        fun toUriPart(): String {
            val selectVal = vals[state].second
            return if (selectVal != "") "$searchName=$selectVal" else ""
        }
    }

    private fun parseSearchMangaWithFilterOrPopularOrLatestResponse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select("div.exemptComicList div.exemptComicItem").map { element ->
            mangaFromPage(element)
        }

        // There is always a next pager, so use itemCount to check. XD
        val hasNextPage = mangas.size == popularLatestPageSize

        return MangasPage(mangas, hasNextPage)
    }

    private fun parseSearchMangaResponseAsJson(response: Response): MangasPage {
        val body = response.body!!.string()
        // results > comic > list []
        val res = JSONObject(body)
        val comicArray = res.optJSONObject("results")?.optJSONArray("list")
        if (comicArray == null) {
            return MangasPage(listOf(), false)
        }

        val ret = ArrayList<SManga>(comicArray.length())
        for (i in 0 until comicArray.length()) {
            val obj = comicArray.getJSONObject(i)
            val authorArray = obj.getJSONArray("author")
            var _title: String = obj.getString("name")
            if (preferences.getBoolean(SHOW_Simplified_Chinese_TITLE_PREF, false)) {
                _title = ChineseUtils.toSimplified(_title)
            }
            ret.add(
                SManga.create().apply {
                    title = _title
                    var picture = obj.getString("cover")
                    if (!preferences.getBoolean(change_cdn_tomainland, false)) {
                        picture = replaceToMirror2.replace(picture, "mirror2.mangafunc.fun")
                        picture = replaceToMirror.replace(picture, "mirror.mangafunc.fun")
                    }
                    thumbnail_url = picture
                    author = Array<String?>(authorArray.length()) { i -> authorArray.getJSONObject(i).getString("name") }.joinToString(", ")
                    status = SManga.UNKNOWN
                    url = "/comic/${obj.getString("path_word")}"
                }
            )
        }

        return MangasPage(ret, comicArray.length() == searchPageSize)
    }

    private fun mangaFromPage(element: Element): SManga {
        val manga = SManga.create()
        element.select("div.exemptComicItem-img > a > img").first().let {
            var picture = it.attr("data-src")
            if (!preferences.getBoolean(change_cdn_tomainland, false)) {
                picture = replaceToMirror2.replace(picture, "mirror2.mangafunc.fun")
                picture = replaceToMirror.replace(picture, "mirror.mangafunc.fun")
            }
            manga.thumbnail_url = picture
        }
        element.select("div.exemptComicItem-txt > a").first().let {
            manga.setUrlWithoutDomain(it.attr("href"))
            var _title: String = it.select("p").first().text().trim()
            if (preferences.getBoolean(SHOW_Simplified_Chinese_TITLE_PREF, false)) {
                _title = ChineseUtils.toSimplified(_title)
            }
            manga.title = _title
        }
        return manga
    }

    private fun byteArrayToHexString(byteArray: ByteArray): String {
        var sb = ""
        for (b in byteArray) {
            sb += String.format("%02x", b)
        }
        return sb
    }

    private fun hexStringToByteArray(string: String): ByteArray {
        val bytes = ByteArray(string.length / 2)
        for (i in 0 until string.length / 2) {
            bytes[i] = string.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }

    private fun stringToUnixTimestamp(string: String, pattern: String = "yyyy-MM-dd", locale: Locale = Locale.CHINA): Long {
        return try {
            val time = SimpleDateFormat(pattern, locale).parse(string)?.time
            if (time != null) time / 1000 else Date().time / 1000
        } catch (ex: Exception) {
            Date().time / 1000
        }
    }

    // thanks to unpacker toolsite, http://matthewfl.com/unPacker.html
    private fun decryptChapterData(disposableData: String, disposablePass: String = "hotmanga.aes.key"): String {
        val prePart = disposableData.substring(0, 16)
        val postPart = disposableData.substring(16, disposableData.length)
        val disposablePassByteArray = disposablePass.toByteArray(Charsets.UTF_8)
        val prepartByteArray = prePart.toByteArray(Charsets.UTF_8)
        val dataByteArray = hexStringToByteArray(postPart)

        val secretKey = SecretKeySpec(disposablePassByteArray, "AES")
        val iv = IvParameterSpec(prepartByteArray)
        val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, iv)
        val result = String(cipher.doFinal(dataByteArray), Charsets.UTF_8)

        return result
    }

    // Change Title to Simplified Chinese For Library Gobal Search Optionally
    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val zhPreference = androidx.preference.CheckBoxPreference(screen.context).apply {
            key = SHOW_Simplified_Chinese_TITLE_PREF
            title = "将标题转换为简体中文"
            summary = "需要重启软件以生效。已添加漫画需要迁移改变标题。"

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val setting = preferences.edit().putBoolean(SHOW_Simplified_Chinese_TITLE_PREF, newValue as Boolean).commit()
                    setting
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }
        val cdnPreference = androidx.preference.CheckBoxPreference(screen.context).apply {
            key = change_cdn_tomainland
            title = "转换图片CDN为国内"
            summary = "加载图片使用国内CDN,可以使用代理的情况下请不要打开此选项"

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val setting = preferences.edit().putBoolean(change_cdn_tomainland, newValue as Boolean).commit()
                    setting
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }
        screen.addPreference(zhPreference)
        screen.addPreference(cdnPreference)
    }

    companion object {
        private const val SHOW_Simplified_Chinese_TITLE_PREF = "showSCTitle"
        private const val change_cdn_tomainland = "changeCDN"
    }
}
