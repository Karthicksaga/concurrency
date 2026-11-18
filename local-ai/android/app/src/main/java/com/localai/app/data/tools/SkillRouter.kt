package com.localai.app.data.tools

import com.localai.app.data.safety.ScamCheck

/**
 * A skill that may help with a message. [input] is set when it can be extracted reliably, and [sure] means the skill
 * should run without asking the model (e.g. a plain sum). Unsure candidates go to one grammar-constrained decision.
 */
data class SkillCandidate(val tool: String, val input: String?, val sure: Boolean)

/**
 * Instant, on-device pre-routing for automatic tools in normal chat. Ordinary messages produce no candidates, so they
 * cost nothing extra; the model is consulted only when a skill is plausible but not certain. Pure and unit-tested.
 */
object SkillRouter {
    private val ci = setOf(RegexOption.IGNORE_CASE)
    private val arithmetic = Regex("(?<![\\w.])\\(?\\s*-?\\d[\\d,]*(\\.\\d+)?\\s*\\)?(\\s*[-+*/×x÷^%]\\s*\\(?\\s*-?\\d[\\d,]*(\\.\\d+)?\\s*\\)?)+")
    private val percentOf = Regex("(\\d[\\d,]*(?:\\.\\d+)?)\\s*%\\s*of\\s*(?:rs\\.?|₹|inr)?\\s*(\\d[\\d,]*(?:\\.\\d+)?)", ci)
    private val mathWords = Regex("\\b(what is|what's|calculate|compute|how much is|total of|sum of|=\\s*\\?|evaluate)\\b", ci)
    private val dateTime = Regex("\\b(what('?s| is) (the )?(date|time|day)|today'?s date|what day is (it|today)|current (date|time)|date today|time now|which day)\\b|आज (की )?तारीख|இன்று (என்ன )?தேதி", ci)
    private val document = Regex("\\b(make|create|save|export|write|generate|turn|convert|give)\\b.{0,50}\\b(pdf|word|docx|document file)\\b|\\bas (a )?(pdf|word|docx)\\b", ci)
    private val scamWords = Regex("\\b(scam|fraud|fake|genuine|legit(imate)?|is this (real|true|safe)|phishing|spam)\\b", ci)
    private val myDocs = Regex("\\b(in|from|according to|search|check) (my|the) (documents?|files?|pdfs?|notes|knowledge( base)?|lease|policy|statements?)\\b", ci)
    private val question = Regex("^(what|when|where|who|which|how|why|does|do|is|are|can|should|explain|summari[sz]e|find|list|tell me)\\b|\\?\\s*$", ci)
    private val noteWords = Regex("\\b(remember (that|this)|note (this|that|down)|save (a |this )?note|add (a )?note|make a note|my notes|show (my )?notes|list (my )?notes)\\b", ci)
    // "call Amma", "please call my appa", "amma ku call pannu", "அம்மாவுக்கு கால் பண்ணு", "माँ को कॉल करो"
    private val callEn = Regex("^(?:(?:please|pls|can you|could you|hey|ok)\\s+)*(?:call|dial|ring|phone)\\s+(?:to\\s+)?(.{1,40}?)(?:\\s+(?:now|please|pls|right now))?[.!?]*$", ci)
    private val callTanglish = Regex("^(?:my\\s+)?(.{1,30}?)\\s*(?:ku|kku|kitta)?\\s+(?:call|phone)\\s+(?:pannu|panu|pannunga|podu|pottu|panni)\\b.*$", ci)
    private val callTamil = Regex("^(.{1,30}?)(?:வுக்கு|க்கு|கு)?\\s*(?:கால்|போன்)\\s*(?:பண்ணு|பண்ணுங்க|செய்|போடு)")
    private val callHindi = Regex("^(.{1,30}?)\\s+को\\s+(?:कॉल|फ़ोन|फोन)\\s+(?:करो|कीजिए|करें|लगाओ)")
    private val notPeople = setOf("me", "it", "this", "that", "back", "him", "her", "them", "us", "you", "me back", "it a day")

    /** The person or number in a "call …" command, or null. */
    fun callTarget(text: String): String? {
        val t = text.trim()
        val m = callEn.find(t) ?: callTanglish.find(t) ?: callTamil.find(t) ?: callHindi.find(t) ?: return null
        val who = m.groupValues[1].trim().removePrefix("my ").trim()
        if (who.isEmpty() || who.lowercase() in notPeople || who.split(Regex("\\s+")).size > 4) return null
        return who
    }

    fun route(text: String, hasKnowledge: Boolean): List<SkillCandidate> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        val out = ArrayList<SkillCandidate>()

        // Calculator: "18% of 2,500", "(1250*12)*0.18", "what is 38000 x 2"
        percentOf.find(t)?.let { m ->
            out += SkillCandidate("calculator", "${num(m.groupValues[2])} * ${num(m.groupValues[1])} / 100", sure = true)
        } ?: arithmetic.findAll(t).map { it.value.trim() }.filter { e -> e.count { it.isDigit() } >= 2 && Regex("\\d\\s*[-+*/×x÷^%]\\s*[\\d(]").containsMatchIn(e) }
            .maxByOrNull { it.length }?.let { e ->
                val expr = e.replace('×', '*').replace('÷', '/').replace(Regex("(?<=\\d)\\s*x\\s*(?=[\\d(])"), " * ").replace(",", "")
                val mostlyMath = e.length >= t.length * 0.5 || mathWords.containsMatchIn(t)
                // dates and phone numbers ("12-11-2026", "98765-43210") are not sums
                if (!Regex("^\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}$").matches(e.trim()) && !Regex("^\\d{5}-\\d{5}$").matches(e.trim()))
                    out += SkillCandidate("calculator", expr, sure = mostlyMath)
            }

        if (dateTime.containsMatchIn(t)) out += SkillCandidate("date_time", "now", sure = true)

        // A pasted message: the rules already see scam signs, or the user asks whether it's genuine.
        val report = ScamCheck.analyze(t)
        val pasted = t.length >= 40
        if (pasted && report.level != ScamCheck.Level.LOW) out += SkillCandidate("scam_check", t, sure = true)
        else if (scamWords.containsMatchIn(t) && pasted) out += SkillCandidate("scam_check", t, sure = true)

        document.find(t)?.let { m ->
            val fmt = if (Regex("word|docx", ci).containsMatchIn(m.value)) "docx" else "pdf"
            out += SkillCandidate("create_document", fmt, sure = true)
        }

        callTarget(t)?.let { who -> out += SkillCandidate("phone_call", who, sure = true) }

        if (noteWords.containsMatchIn(t)) out += SkillCandidate("note_manager", null, sure = false)

        if (hasKnowledge && out.none { it.tool == "scam_check" || it.tool == "calculator" && it.sure }) {
            when {
                myDocs.containsMatchIn(t) -> out += SkillCandidate("knowledge_search", t.take(300), sure = true)
                question.containsMatchIn(t) && t.length >= 12 -> out += SkillCandidate("knowledge_search", t.take(300), sure = false)
            }
        }
        return out.distinctBy { it.tool }
    }

    private fun num(s: String) = s.replace(",", "")
}
