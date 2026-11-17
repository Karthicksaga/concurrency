package com.localai.app.data.safety

/**
 * On-device scam checker for a message, screenshot text or link the user chose to share with the app.
 * Deterministic rules give the verdict (works without any model, explainable, testable); the language model
 * only explains the result in the user's language. Patterns cover common Indian frauds: fake KYC/account
 * blocking, UPI "enter PIN to receive", electricity disconnection, parcel/"digital arrest", task/job scams,
 * fake investments, lottery/prize, remote-access apps and lookalike links.
 */
object ScamCheck {
    enum class Level(val label: String) { HIGH("Likely a scam"), MEDIUM("Suspicious"), LOW("No common scam signs") }

    data class Flag(val id: String, val title: String, val why: String, val weight: Int, val evidence: String)

    data class Report(val level: Level, val score: Int, val flags: List<Flag>, val links: List<String>) {
        val critical get() = flags.any { it.weight >= 5 }
    }

    private class Rule(val id: String, val title: String, val why: String, val weight: Int, val patterns: List<Regex>, val unless: Regex? = null)

    private fun r(vararg p: String) = p.map { Regex(it, setOf(RegexOption.IGNORE_CASE)) }

    private val rules = listOf(
        Rule("otp", "Asks for an OTP or PIN", "Banks, UPI apps and delivery companies never ask you to share an OTP, PIN or CVV.", 7,
            r("(share|send|tell|give|forward|provide)\\b.{0,25}\\b(otp|one[- ]time password|pin|cvv|mpin)", "(otp|pin|cvv)\\b.{0,25}\\b(share|send|tell|forward|provide)\\b",
                "ओटीपी.{0,20}(बताएं|भेजें|शेयर)", "ஓடிபி.{0,20}(பகிர|சொல்ல|அனுப்ப)"),
            unless = Regex("(do not|don't|never|dont)\\s+(share|disclose|tell)|किसी के साथ साझा न|யாருடனும் பகிர வேண்டாம்", RegexOption.IGNORE_CASE)),
        Rule("pin_receive", "PIN to \"receive\" money", "You never need to enter your UPI PIN to receive money. Entering it sends money out.", 7,
            r("(enter|use|type)\\b.{0,20}\\b(upi )?pin\\b.{0,40}\\b(receive|get|credit|refund|cashback)", "(receive|get|claim)\\b.{0,30}\\b(enter|scan)\\b.{0,20}\\b(pin|qr)", "collect request")),
        Rule("remote", "Asks you to install a screen-sharing app", "Apps like AnyDesk or TeamViewer let a stranger see and control your phone, including your bank apps.", 7,
            r("\\b(anydesk|teamviewer|quick ?support|rustdesk|airdroid|screen ?shar(e|ing))\\b", "\\.apk\\b", "download (this|the) app")),
        Rule("arrest", "Threat from \"police\", CBI, customs or TRAI", "Officials never arrest anyone over a video call or ask for money to \"settle\" a case. \"Digital arrest\" is always a scam.", 7,
            r("digital arrest", "\\b(cbi|narcotics|ncb|ed officer|enforcement directorate|customs|cyber ?cell|trai|police)\\b.{0,80}\\b(case|arrest|warrant|seized|illegal|drugs|suspend)",
                "(parcel|courier|fedex|dhl).{0,80}(drugs|illegal|seized|passports?)", "डिजिटल अरेस्ट")),
        Rule("kyc", "Account blocked / KYC update", "Banks don't block accounts by SMS or ask you to update KYC through a link. Use only the bank's own app or branch.", 4,
            r("\\b(kyc|pan( card)?|aadhaar)\\b.{0,40}\\b(update|expire|expired|pending|link|verify|suspend|block)", "account.{0,30}\\b(blocked|block|suspended|deactivated|frozen|closed)\\b",
                "(sim|number).{0,30}(blocked|deactivated|suspended)", "केवाईसी", "கேஒய்சி", "கணக்கு.{0,20}முடக்க")),
        Rule("power", "Electricity will be cut tonight", "Electricity boards do not message you from personal numbers asking you to call an \"officer\" or pay through a link.", 5,
            r("(electricity|power|light|bijli|eb) (bill|connection|supply).{0,80}(disconnect|cut|discontinue|tonight|today)", "(disconnect|cut).{0,40}(electricity|power) (connection|supply)",
                "बिजली.{0,40}(कट|काट)", "மின்.{0,40}(துண்டிக்க|நிறுத்த)")),
        Rule("prize", "Prize, lottery or free gift", "You can't win a lottery or prize you never entered. They ask for a \"fee\" or your bank details next.", 4,
            r("\\b(congratulations|you have won|you won|winner|lottery|lucky draw|kbc|jackpot|prize money|free gift|reward points? (expir|redeem))\\b", "लॉटरी", "பரிசு")),
        Rule("job", "Easy money / task job", "Pay-per-like, review or \"task\" jobs pay small amounts first, then ask you to deposit money you never get back.", 6,
            r("(work from home|part[- ]time job|daily (income|earn)|earn (rs\\.?|₹|inr)?\\s?\\d[\\d,]*\\s?(per|a|/) ?(day|hour|task))", "\\b(like|rate|review)\\b.{0,30}\\b(videos?|hotels?|products?)\\b.{0,40}\\b(earn|paid|salary|commission)",
                "(prepaid|vip) task", "telegram.{0,40}(task|job|earn)", "registration fee", "घर बैठे कमाएं")),
        Rule("invest", "Guaranteed or doubled returns", "No real investment guarantees high or doubled returns. This is how fake trading and crypto apps start.", 5,
            r("(double|triple) (your )?money", "guaranteed (returns?|profit)", "\\b\\d{2,3}\\s?% (daily|weekly|monthly) (return|profit)", "(crypto|trading|ipo|stock).{0,40}(tips|group|guaranteed|sure shot)")),
        Rule("pay_to_get", "Pay a fee to receive money", "Paying a \"processing\", \"release\" or \"tax\" fee to get a refund, loan or prize is a classic scam.", 5,
            r("(processing|release|clearance|registration|verification|gst|tax|insurance) (fee|charge|charges|amount)", "pay.{0,30}(to (receive|release|unlock|claim|activate))")),
        Rule("urgent", "Pressure to act now", "Scammers rush you so you don't stop to check. Real organisations give you time.", 2,
            r("\\b(immediately|urgent(ly)?|within \\d+ (hours?|hrs|minutes?|mins)|today itself|last (warning|chance|reminder)|act now|tonight)\\b", "तुरंत", "உடனடியாக|இன்று இரவு")),
        Rule("call_number", "Asks you to call or WhatsApp a mobile number", "Organisations use official helplines, not personal mobile numbers.", 2,
            r("(call|contact|whatsapp|message)\\b.{0,25}(\\+?91[- ]?)?[6-9]\\d{9}\\b")),
        Rule("refund", "Unexpected refund or wrong transfer", "\"I sent money by mistake, please return it\" and surprise refunds are used to trick you into paying.", 3,
            r("(sent|transferred|credited).{0,30}(by mistake|wrongly)", "refund.{0,40}(click|link|process|claim)", "income tax refund")),
    )

    private val urlRe = Regex("(?i)\\b((?:https?://|www\\.)[^\\s<>\"']+|[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|in|net|org|xyz|top|click|info|icu|live|shop|online|site|link|ly|me|co|cc|io|vip|buzz|app)(?:/[^\\s<>\"']*)?)")
    private val shorteners = setOf("bit.ly", "tinyurl.com", "cutt.ly", "rb.gy", "t.ly", "is.gd", "shorturl.at", "goo.gl", "ow.ly", "tiny.cc", "s.id", "rebrand.ly", "t.co", "wa.me")
    private val riskyTlds = setOf("xyz", "top", "click", "icu", "live", "shop", "online", "site", "link", "vip", "buzz", "cc", "info")
    private val brands = listOf("sbi", "hdfc", "icici", "axis", "kotak", "paytm", "phonepe", "gpay", "googlepay", "amazon", "flipkart", "npci", "uidai", "incometax", "irctc", "bescom", "tneb", "tangedco", "indiapost", "bsnl", "airtel", "jio", "lic", "epfo")
    // Official domains (not exhaustive): a brand name on any other domain is treated as a lookalike.
    private val official = setOf("sbi.co.in", "onlinesbi.sbi", "hdfcbank.com", "icicibank.com", "axisbank.com", "kotak.com", "paytm.com", "phonepe.com", "pay.google.com",
        "amazon.in", "amazon.com", "flipkart.com", "npci.org.in", "uidai.gov.in", "incometax.gov.in", "irctc.co.in", "bescom.co.in", "tangedco.org", "tnebltd.gov.in",
        "indiapost.gov.in", "bsnl.co.in", "airtel.in", "jio.com", "licindia.in", "epfindia.gov.in")

    fun links(text: String): List<String> = urlRe.findAll(text).map { it.value.trimEnd('.', ',', ')', ';') }
        .filter { it.contains('.') && !Regex("^\\d+(\\.\\d+)+$").matches(it) }.distinct().toList()

    fun host(link: String): String = link.lowercase().removePrefix("http://").removePrefix("https://").removePrefix("www.").substringBefore('/').substringBefore('?').substringBefore(':')

    fun analyze(text: String): Report {
        val t = text.replace('\n', ' ')
        val flags = ArrayList<Flag>()
        for (rule in rules) {
            val m = rule.patterns.firstNotNullOfOrNull { it.find(t) } ?: continue
            if (rule.unless?.containsMatchIn(t) == true) continue
            flags += Flag(rule.id, rule.title, rule.why, rule.weight, m.value.trim().take(80))
        }
        val links = links(t)
        for (l in links) {
            val h = host(l)
            when {
                h in shorteners -> flags += Flag("short", "Shortened link", "Short links hide where they really go. Don't open them from unknown senders.", 3, l)
                Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(h) -> flags += Flag("ip", "Link to a bare IP address", "Real companies never send links to raw number addresses.", 5, l)
                brands.any { h.contains(it) } && official.none { h == it || h.endsWith(".$it") } ->
                    flags += Flag("lookalike", "Fake-looking company link", "The link uses a well-known name but is not that company's real website.", 6, l)
                h.substringAfterLast('.') in riskyTlds -> flags += Flag("tld", "Unusual website address", "Addresses ending in .${h.substringAfterLast('.')} are cheap and often used for scams.", 3, l)
            }
            if (l.lowercase().startsWith("http://")) flags += Flag("http", "Unsecured link (http)", "The link is not encrypted; never enter details on such a page.", 1, l)
        }
        val unique = flags.distinctBy { it.id }
        val score = unique.sumOf { it.weight }
        val critical = unique.count { it.weight >= 5 }
        val level = when {
            unique.any { it.weight >= 6 } -> Level.HIGH          // one strong signal is enough (OTP, PIN, remote app, arrest, fake link, task job)
            critical >= 1 && score >= 7 -> Level.HIGH
            score >= 8 -> Level.HIGH
            score >= 3 || critical >= 1 -> Level.MEDIUM
            else -> Level.LOW
        }
        return Report(level, score, unique.sortedByDescending { it.weight }, links)
    }

    /** What to do, shown with every result (Indian helplines; correct as of 2026). */
    val advice = listOf(
        "Don't open links, call numbers or install apps from this message.",
        "Never share an OTP, UPI PIN, CVV or password with anyone.",
        "Check by calling the official number on the bank's website, card or app, not the one in the message.",
        "Lost money? Call 1930 (National Cyber Crime Helpline) immediately, or report at cybercrime.gov.in.",
        "Report fraud calls and messages on Sanchar Saathi (Chakshu) at sancharsaathi.gov.in.",
    )
}
