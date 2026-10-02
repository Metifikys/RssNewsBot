package metifikys.format

/**
 * Ukrainian names of Step 1's `eventType` values, for admin-chat text and buttons (the inspector,
 * `/mutes`). `eventType` is a free snake_case string — the prompts list stable values, the model
 * adds its own — so [label] falls back to the raw value with spaces.
 */
object EventTypeLabels {

    private val LABELS: Map<String, String> = mapOf(
        // gaming
        "major_announcement" to "анонси",
        "release_date" to "дати релізу",
        "platform_policy" to "політика платформ",
        "industry_news" to "новини індустрії",
        "major_dlc_expansion" to "великі DLC",
        "delay" to "перенесення",
        "cancellation" to "скасування",
        "lawsuit" to "суди",
        "acquisition" to "поглинання",
        "studio_closure" to "закриття студій",
        "layoffs" to "звільнення",
        "layoff" to "звільнення",
        "patch" to "патчі",
        "season_launch" to "старти сезонів",
        "content_update" to "контент-оновлення",
        "dev_quote" to "цитати розробників",
        "community_story" to "історії спільноти",
        "leak" to "витоки",
        "rumor" to "чутки",
        "preview_impressions" to "превʼю",
        "hands_on_preview" to "превʼю",
        "review" to "огляди",
        "media_reveal" to "трейлери й показ",
        "chart_performance" to "чарти й продажі",
        "esports_result" to "кіберспорт",
        "esports_news" to "кіберспорт",
        "demo_release" to "демо",
        "early_access_release" to "ранній доступ",
        "casting_confirmed" to "кастинг",
        "obituary" to "некрологи",
        // tech
        "security_incident" to "інциденти безпеки",
        "vulnerability" to "вразливості",
        "research_breakthrough" to "дослідження",
        "devtool_release" to "інструменти розробника",
        "open_source_release" to "open source релізи",
        "product_launch" to "запуски продуктів",
        "ai_platform_update" to "оновлення AI-платформ",
        "ai_model_release" to "нові AI-моделі",
        "ai_product_launch" to "AI-продукти",
        "chip_business" to "чипи: бізнес",
        "chip_launch" to "нові чипи",
        "investment" to "інвестиції",
        "regulation" to "регулювання",
        "ai_policy_regulation" to "регулювання AI",
        "developer_platform_change" to "зміни платформ для розробників",
        "datacenter_infrastructure" to "датацентри",
        "market_report" to "ринкові звіти",
        "os_update" to "оновлення ОС",
        "court_ruling" to "рішення судів",
        "court_case" to "суди",
        "outage" to "збої",
        "product_shutdown" to "закриття продуктів",
        // science
        "medical_research" to "медицина",
        "biology_discovery" to "біологія",
        "astronomy_discovery" to "астрономія",
        "physics_discovery" to "фізика",
        "climate_research" to "клімат",
        "space_mission" to "космічні місії",
        "archaeology_paleontology" to "археологія",
        "engineering_breakthrough" to "інженерія",
        "oddity" to "курйози",
        "explainer" to "пояснення"
    )

    /** The Ukrainian label of [eventType], else the raw value with `_` as spaces; blank stays blank. */
    fun label(eventType: String): String {
        val key = eventType.trim().lowercase()
        return LABELS[key] ?: key.replace('_', ' ')
    }

    /** Canonical form a type mute is stored and compared in: trimmed, lowercased. */
    fun normalize(eventType: String): String = eventType.trim().lowercase()

    /** Null when [eventType] can be muted, else why not: a snake_case-ish value of 2..100 chars. */
    fun validate(eventType: String): String? {
        val t = normalize(eventType)
        return when {
            t.length < 2 -> "закороткий тип"
            t.length > 100 -> "задовгий тип"
            !t.all { it.isLetterOrDigit() || it == '_' || it == '-' } -> "тип — одне слово в snake_case (напр. rumor)"
            else -> null
        }
    }
}
