package com.arflix.tv.data.repository

import com.arflix.tv.data.model.CollectionGroupKind
import com.arflix.tv.data.model.CollectionSourceConfig
import com.arflix.tv.data.model.CollectionSourceKind
import com.arflix.tv.data.model.CollectionTileShape
import java.util.Locale

/** ARVIO-authored queries. Artwork is TMDB metadata, not an imported creator's pack. */
internal object ArvioCollections {
    const val DISABLED_MARKER = "collection_defaults_disabled"
    private const val SCIENCE = "/8sNiAPPYU14PUepFNeSNGUTiHW.jpg"
    private const val COMEDY = "/jK65srQczOKTpW62wPxwwKztGgE.jpg"
    private const val FAMILY = "/eCynaAOgYYiw5yN5lBwz3IxqvaW.jpg"
    private const val CRIME = "/tSPT36ZKlP2WVHJLM4cQPLSzv3b.jpg"
    private const val HORROR = "/AmR3JG1VQVxU8TfAvljUhfSFUOx.jpg"
    private const val DOCUMENTARY = "/z2uuQasY4gQJ8VDAFki746JWeQJ.jpg"
    private const val ANIMATION = "/mMtUybQ6hL24FXo0F3Z4j2KG7kZ.jpg"
    private const val ACTION = "/gqrnQA6Xppdl8vIb2eJc58VC1tW.jpg"
    private const val THRILLER = "/8ZTVqvKDQ8emSGUEMjsS4yHAwrp.jpg"
    private const val FANTASY = "/zRKQW58MBEY078AxkHxEJzUskCl.jpg"
    private const val ADVENTURE = "/uRNgkJSkNBFbbn9fPsEjDIy8Sh3.jpg"

    val entries: List<CollectionTemplateEntry> = buildList {
        add(entry("Favoritos do público", CollectionGroupKind.FEATURED, SCIENCE,
            "Filmes altamente avaliados com pelo menos 1.000 votos do público.", listOf(
                discover("movie", "vote_average.desc", "vote_count.gte" to "1000"))))
        add(entry("Séries aclamadas", CollectionGroupKind.FEATURED, THRILLER,
            "Grandes produções da televisão, classificadas com pelo menos 500 votos.", listOf(
                discover("series", "vote_average.desc", "vote_count.gte" to "500"))))
        add(entry("Menos de 2 horas", CollectionGroupKind.FEATURED, COMEDY,
            "Filmes bem avaliados para uma noite, todos entre 60 e 120 minutos.", listOf(
                discover("movie", "popularity.desc", "with_runtime.gte" to "60", "with_runtime.lte" to "120",
                    "vote_average.gte" to "7", "vote_count.gte" to "300"))))
        add(entry("Animação japonesa", CollectionGroupKind.FEATURED, ANIMATION,
            "Filmes e séries de anime do Japão, de histórias comoventes a grandes aventuras.",
            listOf("movie", "series").map {
                discover(it, "popularity.desc", "with_genres" to "16", "with_original_language" to "ja")
            }))

        add(service("Netflix", 8, "/rK1KljqmbvO9HQa1PBFLILWah72.png"))
        add(service("Disney+", 337, "/5eZ872CghnHFLB1j8grszbrx0dx.png"))
        add(service("Apple TV+", 350, "/9icYBfYFcwgCbky5VdGUIKJ4C5i.png"))
        add(service("Prime Video", 9, "/gMZdpavHmxFNnLpMHwVxfqeux2g.png"))
        add(service("HBO Max", 1899, "/skypuy7SXuugIQeYg0IglmzoKaS.png"))
        add(service("Hulu", 15, "/44uAnmSqvA4yBOdbPWN8YgQHjWm.png"))
        add(service("Paramount+", 2303, "/4N4BMd0Mm0kHAmF7RZgL5lW3cwc.png", 2616))
        add(service("Peacock", 386, "/a1UIdq5BrkcAxnxcUhFsNbXnxeu.png"))
        add(service("Starz", 43, "/h25xjouKmiSmFiiqw0aDXbxGZo7.png"))
        add(service("Shudder", 99, "/58O6yqUFM6qoOiBNAddJs7xNKc.png"))
        add(service("MGM+", 34, "/q63Uzpu7JAs566vA2G23Lk7LcID.png"))
        add(service("Discovery+", 520, "/tHseJEgZaUdVlLtBpOGmKPfzbQ8.png"))
        add(service("Crunchyroll", 283, "/uFL3c4Cq8M6WoLymlC5Y8bmGytV.png"))

        add(genre("Ação", 28, 10759, ACTION, "Adrenalina, perseguições eletrizantes e grandes aventuras."))
        add(genre("Aventura", 12, 10759, ADVENTURE, "Jornadas a lugares desconhecidos e histórias de descobertas."))
        add(genre("Comédia", 35, 35, COMEDY, "Comédias inteligentes, favoritos de sempre e histórias leves."))
        add(genre("Crime", 80, 80, CRIME, "Investigações policiais, impérios criminais e segredos obscuros."))
        add(genre("Documentário", 99, 99, DOCUMENTARY, "Histórias reais, fatos marcantes e pessoas extraordinárias."))
        add(genre("Família", 10751, 10751, FAMILY, "Filmes e séries para todas as idades assistirem juntos."))
        add(genre("Fantasia", 14, 10765, FANTASY, "Mundos mágicos, criaturas míticas e poderes extraordinários."))
        add(genre("Terror", 27, null, HORROR, "Mistérios assustadores, forças sobrenaturais e suspense arrepiante."))
        add(genre("Ficção Científica", 878, 10765, SCIENCE, "Novos mundos, futuros distópicos e tecnologia além da imaginação."))
        add(genre("Suspense", 53, null, THRILLER, "Tramas intensas cheias de reviravoltas, perigo e mistério."))

        add(franchise("Star Wars", 10, "/iY2ujEY2m68OTTlPFTiHub9joHS.jpg", "A saga completa dos Skywalker, em ordem de lançamento."))
        add(franchise("Senhor dos Anéis", 119, "/bccR2CGTWVVSZAG0yqmy3DIvhTX.jpg", "A jornada inesquecível pela Terra Média."))
        add(franchise("Harry Potter", 1241, "/4gV0rKUjB1nLUdZB4zIltLvNZZr.jpg", "A história do jovem bruxo em Hogwarts, do primeiro ano à batalha final."))
        add(franchise("Matrix", 2344, "/bRm2DEgUiYciDw3myHuYFInD7la.jpg", "A trilogia revolucionária que redefiniu o cinema de ficção."))
        add(franchise("Piratas do Caribe", 295, "/wxgD3fB5lQ2sGJLog0rvXW049Pf.jpg", "As aventuras e desventuras do Capitão Jack Sparrow pelos mares."))
        add(franchise("Jogos Vorazes", 131635, "/Ipp7cegtub4t0mu7xaKLQkYoGc.jpg", "Os tributos de Panem e a rebelião de Katniss Everdeen."))
        add(franchise("Velozes e Furiosos", 9485, "/z5A5W3WYJc3UVEWljSGwdjDgQ0j.jpg", "Corridas de rua, missões globais e a importância da família."))
        add(franchise("Jurassic Park", 328, "/njFixYzIxX8jsn6KMSEtAzi4avi.jpg", "Parque dos Dinossauros e Jurassic World em ordem cronológica."))
        add(franchise("Exterminador do Futuro", 528, "/sCnBEw2Yu6foEjs4Xb4eMddYHRo.jpg", "A batalha no tempo contra a Skynet pelo destino da humanidade."))
        add(franchise("Batman: Cavaleiro das Trevas", 263, "/xyhrCEdB4XRkelfVsqXeUZ6rLHi.jpg", "A aclamada trilogia do Batman dirigida por Christopher Nolan."))

        for ((year, art) in listOf(1970 to CRIME, 1980 to HORROR, 1990 to FAMILY,
            2000 to ADVENTURE, 2010 to SCIENCE, 2020 to FANTASY)) {
            add(entry("Anos $year", CollectionGroupKind.DECADE, art,
                "Explore os filmes de $year a ${year + 9}, classificados por avaliação do público.", listOf(
                    discover("movie", "vote_average.desc", "primary_release_date.gte" to "$year-01-01",
                        "primary_release_date.lte" to "${year + 9}-12-31", "vote_count.gte" to "300"))))
        }
    }

    fun preserveDisabledDefaults(hidden: Set<String>): Set<String> {
        val legacyRails = setOf("collection_rail_service", "collection_rail_genre", "collection_rail_franchise")
        val serviceIds = entries.filter { it.group == CollectionGroupKind.SERVICE }.map { it.id }
        // The old global switch hid every tile as well as its rail. Hiding just rails is not enough.
        val disabled = DISABLED_MARKER in hidden ||
            (hidden.containsAll(legacyRails) && hidden.containsAll(serviceIds))
        return if (disabled) hidden + DISABLED_MARKER + entries.map { it.id } +
            entries.map { "collection_rail_${it.group.name.lowercase(Locale.US)}" } else hidden
    }

    private fun entry(title: String, group: CollectionGroupKind, art: String, description: String,
        sources: List<CollectionSourceConfig>) = CollectionTemplateEntry(
        id = "collection_${group.name.lowercase(Locale.US)}_${slug(title)}",
        title = title, group = group, coverImageUrl = "https://image.tmdb.org/t/p/w780$art",
        tileShape = CollectionTileShape.LANDSCAPE, hideTitle = false, heroVideoUrl = null,
        sources = sources, listMetadata = emptyList(), description = description,
        heroImageUrl = if (group == CollectionGroupKind.SERVICE) null else "https://image.tmdb.org/t/p/w1280$art"
    )

    private fun service(title: String, provider: Int, logo: String, secondProvider: Int? = null): CollectionTemplateEntry =
        entry(title, CollectionGroupKind.SERVICE, logo,
            "Movies and series available through $title in the US catalog. Availability varies by region.",
            listOfNotNull(provider, secondProvider).flatMap { id -> listOf("movie", "series").map { type ->
                CollectionSourceConfig(kind = CollectionSourceKind.TMDB_WATCH_PROVIDER, mediaType = type,
                    tmdbWatchProviderId = id, watchRegion = "US", sortBy = "popularity.desc")
            } })

    private fun genre(title: String, movie: Int, series: Int?, art: String, description: String) =
        entry(title, CollectionGroupKind.GENRE, art, description, listOfNotNull(
            CollectionSourceConfig(kind = CollectionSourceKind.TMDB_GENRE, mediaType = "movie", tmdbGenreId = movie,
                sortBy = "popularity.desc"),
            series?.let { CollectionSourceConfig(kind = CollectionSourceKind.TMDB_GENRE, mediaType = "series",
                tmdbGenreId = it, sortBy = "popularity.desc") }
        ))

    private fun franchise(title: String, id: Int, art: String, description: String) =
        entry(title, CollectionGroupKind.FRANCHISE, art, description, listOf(
            CollectionSourceConfig(kind = CollectionSourceKind.TMDB_COLLECTION, mediaType = "movie", tmdbCollectionId = id)))

    private fun discover(type: String, sort: String, vararg filters: Pair<String, String>) =
        CollectionSourceConfig(kind = CollectionSourceKind.TMDB_DISCOVER, mediaType = type, sortBy = sort,
            discoverParams = mapOf("include_adult" to "false") + filters.toMap())

    private fun slug(title: String) = title.lowercase(Locale.US).replace("+", "plus")
        .replace("&", "and").replace(Regex("[^a-z0-9]+"), "_").trim('_')
}
