package com.lagradost.cloudstream3.animeproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class GogoanimeProvider : MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie")) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

        val qualityRegex = Regex("(\\d+)P")

        // https://github.com/saikou-app/saikou/blob/3e756bd8e876ad7a9318b17110526880525a5cd3/app/src/main/java/ani/saikou/anime/source/extractors/GogoCDN.kt#L60
        // No Licence on the function
        private fun cryptoHandler(
            string: String,
            iv: ByteArray,
            secretKeyString: ByteArray,
            encrypt: Boolean = true
        ): String {
            val ivParameterSpec = IvParameterSpec(iv)
            val secretKey = SecretKeySpec(secretKeyString, "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            return if (!encrypt) {
                cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec)
                String(cipher.doFinal(base64DecodeArray(string)))
            } else {
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivParameterSpec)
                base64Encode(cipher.doFinal(string.toByteArray()))
            }
        }
    }

    override val mainUrl = "https://gogoanime.film"
    override val name = "GogoAnime"
    override val hasQuickSearch = false
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.Anime,
        TvType.OVA
    )

    override suspend fun getMainPage(): HomePageResponse {
        val headers = mapOf(
            "authority" to "ajax.gogo-load.com",
            "sec-ch-ua" to "\"Google Chrome\";v=\"89\", \"Chromium\";v=\"89\", \";Not A Brand\";v=\"99\"",
            "accept" to "text/html, */*; q=0.01",
            "dnt" to "1",
            "sec-ch-ua-mobile" to "?0",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0.4389.90 Safari/537.36",
            "origin" to mainUrl,
            "sec-fetch-site" to "cross-site",
            "sec-fetch-mode" to "cors",
            "sec-fetch-dest" to "empty",
            "referer" to "$mainUrl/"
        )
        val parseRegex =
            Regex("""<li>\s*\n.*\n.*<a\s*href=["'](.*?-episode-(\d+))["']\s*title=["'](.*?)["']>\n.*?img src="(.*?)"""")

        val urls = listOf(
            Pair("1", "Recent Release - Sub"),
            Pair("2", "Recent Release - Dub"),
            Pair("3", "Recent Release - Chinese"),
        )

        val items = ArrayList<HomePageList>()
        for (i in urls) {
            try {
                val params = mapOf("page" to "1", "type" to i.first)
                val html = app.get(
                    "https://ajax.gogo-load.com/ajax/page-recent-release.html",
                    headers = headers,
                    params = params
                )
                items.add(HomePageList(i.second, (parseRegex.findAll(html.text).map {
                    val (link, epNum, title, poster) = it.destructured
                    val isSub = listOf(1, 3).contains(i.first.toInt())
                    AnimeSearchResponse(
                        title,
                        link,
                        this.name,
                        TvType.Anime,
                        poster,
                        null,
                        if (isSub) EnumSet.of(DubStatus.Subbed) else EnumSet.of(
                            DubStatus.Dubbed
                        ),
                        null,
                        if (!isSub) epNum.toIntOrNull() else null,
                        if (isSub) epNum.toIntOrNull() else null,
                    )
                }).toList()))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): ArrayList<SearchResponse> {
        val link = "$mainUrl/search.html?keyword=$query"
        val html = app.get(link).text
        val doc = Jsoup.parse(html)

        val episodes = doc.select(""".last_episodes li""").map {
            AnimeSearchResponse(
                it.selectFirst(".name").text().replace(" (Dub)", ""),
                fixUrl(it.selectFirst(".name > a").attr("href")),
                this.name,
                TvType.Anime,
                it.selectFirst("img").attr("src"),
                it.selectFirst(".released")?.text()?.split(":")?.getOrNull(1)?.trim()
                    ?.toIntOrNull(),
                if (it.selectFirst(".name").text()
                        .contains("Dub")
                ) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(
                    DubStatus.Subbed
                ),
            )
        }

        return ArrayList(episodes)
    }

    private fun getProperAnimeLink(uri: String): String {
        if (uri.contains("-episode")) {
            val split = uri.split("/")
            val slug = split[split.size - 1].split("-episode")[0]
            return "$mainUrl/category/$slug"
        }
        return uri
    }

    override suspend fun load(url: String): LoadResponse {
        val link = getProperAnimeLink(url)
        val episodeloadApi = "https://ajax.gogo-load.com/ajax/load-list-episode"
        val html = app.get(link).text
        val doc = Jsoup.parse(html)

        val animeBody = doc.selectFirst(".anime_info_body_bg")
        val title = animeBody.selectFirst("h1").text()
        val poster = animeBody.selectFirst("img").attr("src")
        var description: String? = null
        val genre = ArrayList<String>()
        var year: Int? = null
        var status: String? = null
        var nativeName: String? = null
        var type: String? = null

        animeBody.select("p.type").forEach { pType ->
            when (pType.selectFirst("span").text().trim()) {
                "Plot Summary:" -> {
                    description = pType.text().replace("Plot Summary:", "").trim()
                }
                "Genre:" -> {
                    genre.addAll(pType.select("a").map {
                        it.attr("title")
                    })
                }
                "Released:" -> {
                    year = pType.text().replace("Released:", "").trim().toIntOrNull()
                }
                "Status:" -> {
                    status = pType.text().replace("Status:", "").trim()
                }
                "Other name:" -> {
                    nativeName = pType.text().replace("Other name:", "").trim()
                }
                "Type:" -> {
                    type = pType.text().replace("type:", "").trim()
                }
            }
        }

        val animeId = doc.selectFirst("#movie_id").attr("value")
        val params = mapOf("ep_start" to "0", "ep_end" to "2000", "id" to animeId)

        val episodes = app.get(episodeloadApi, params = params).document.select("a").map {
            AnimeEpisode(
                fixUrl(it.attr("href").trim()),
                "Episode " + it.selectFirst(".name").text().replace("EP", "").trim()
            )
        }.reversed()

        return newAnimeLoadResponse(title, link, getType(type.toString())) {
            japName = nativeName
            engName = title
            posterUrl = poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes) // TODO CHECK
            plot = description
            tags = genre

            showStatus = getStatus(status.toString())
        }
    }

    data class GogoSources(
        @JsonProperty("source") val source: List<GogoSource>?,
        @JsonProperty("sourceBk") val sourceBk: List<GogoSource>?,
        //val track: List<Any?>,
        //val advertising: List<Any?>,
        //val linkiframe: String
    )

    data class GogoSource(
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("default") val default: String? = null
    )

    private suspend fun extractVideos(uri: String, callback: (ExtractorLink) -> Unit) {
        val doc = app.get(uri).document

        val iframe = fixUrlNull(doc.selectFirst("div.play-video > iframe").attr("src")) ?: return

        argamap(
            {
                val link = iframe.replace("streaming.php", "download")
                val page = app.get(link, headers = mapOf("Referer" to iframe))

                page.document.select(".dowload > a").apmap {
                    if (it.hasAttr("download")) {
                        val qual = if (it.text()
                                .contains("HDP")
                        ) "1080" else qualityRegex.find(it.text())?.destructured?.component1()
                            .toString()
                        callback(
                            ExtractorLink(
                                "Gogoanime",
                                if (qual == "null") "Gogoanime" else "Gogoanime - " + qual + "p",
                                it.attr("href"),
                                page.url,
                                getQualityFromName(qual),
                                it.attr("href").contains(".m3u8")
                            )
                        )
                    } else {
                        val url = it.attr("href")
                        loadExtractor(url, null, callback)
                    }
                }
            }, {
                val streamingResponse = app.get(iframe, headers = mapOf("Referer" to iframe))
                val streamingDocument = streamingResponse.document
                argamap({
                    streamingDocument.select(".list-server-items > .linkserver")
                        ?.forEach { element ->
                            val status = element.attr("data-status") ?: return@forEach
                            if (status != "1") return@forEach
                            val data = element.attr("data-video") ?: return@forEach
                            loadExtractor(data, streamingResponse.url, callback)
                        }
                }, {
                    // https://github.com/saikou-app/saikou/blob/3e756bd8e876ad7a9318b17110526880525a5cd3/app/src/main/java/ani/saikou/anime/source/extractors/GogoCDN.kt
                    // No Licence on the following code
                    val encrypted =
                        streamingDocument.select("script[data-name='crypto']").attr("data-value")
                    val iv = streamingDocument.select("script[data-name='ts']").attr("data-value")
                        .toByteArray()

                    val id = Regex("id=([^&]+)").find(iframe)!!.value.removePrefix("id=")

                    val secretKey = cryptoHandler(encrypted, iv, iv + iv, false)
                    val encryptedId =
                        cryptoHandler(id, "0000000000000000".toByteArray(), secretKey.toByteArray())

                    val jsonResponse =
                        app.get(
                            "http://gogoplay.io/encrypt-ajax.php?id=$encryptedId&time=00000000000000000000",
                            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                        )
                    val sources = AppUtils.parseJson<GogoSources>(jsonResponse.text)

                    fun invokeGogoSource(
                        source: GogoSource,
                        sourceCallback: (ExtractorLink) -> Unit
                    ) {
                        if (source.file.contains("m3u8")) {
                            M3u8Helper().m3u8Generation(
                            M3u8Helper.M3u8Stream(
                                source.file,
                                headers = mapOf("Referer" to "https://gogoplay.io")
                            ), true
                        )
                            .map { stream ->
                                val qualityString = if ((stream.quality ?: 0) == 0) "" else "${stream.quality}p"
                                sourceCallback(  ExtractorLink(
                                    name,
                                    "$name $qualityString",
                                    stream.streamUrl,
                                    "https://gogoplay.io",
                                    getQualityFromName(stream.quality.toString()),
                                    true
                                ))
                            }
                        } else if (source.file.contains("vidstreaming")) {
                            sourceCallback.invoke(
                                ExtractorLink(
                                    this.name,
                                    "${this.name} ${source.label?.replace("0 P", "0p") ?: ""}",
                                    source.file,
                                    "https://gogoplay.io",
                                    getQualityFromName(source.label ?: ""),
                                    isM3u8 = source.type == "hls"
                                )
                            )
                        }
                    }

                    sources.source?.forEach {
                        invokeGogoSource(it, callback)
                    }
                    sources.sourceBk?.forEach {
                        invokeGogoSource(it, callback)
                    }
                })
            }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        extractVideos(data, callback)
        return true
    }
}
