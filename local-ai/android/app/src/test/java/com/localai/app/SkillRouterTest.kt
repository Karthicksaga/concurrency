package com.localai.app

import com.localai.app.data.tools.SkillRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRouterTest {
    private fun tools(t: String, kb: Boolean = false) = SkillRouter.route(t, kb).map { it.tool }
    private fun one(t: String, tool: String, kb: Boolean = false) = SkillRouter.route(t, kb).first { it.tool == tool }

    @Test fun ordinaryChatCostsNothing() {
        assertEquals(emptyList<String>(), tools("Hi! Tell me a short story about a cat."))
        assertEquals(emptyList<String>(), tools("Explain how photosynthesis works"))          // no knowledge bases: plain answer
        assertEquals(emptyList<String>(), tools("My PNR is 4521367890 and the train is on 12-11-2026"))
    }

    @Test fun calculator() {
        assertEquals("2500 * 18 / 100", one("What is 18% of ₹2,500?", "calculator").input)
        val c = one("(1250 * 12) * 0.18", "calculator"); assertTrue(c.sure); assertEquals("(1250 * 12) * 0.18", c.input)
        assertEquals("38000 * 2", one("what is 38000 x 2", "calculator").input)
        assertTrue(one("If rent is 38000 * 2 for the deposit, should I negotiate with the owner first?", "calculator").let { !it.sure })
    }

    @Test fun dateDocumentsCallsNotes() {
        assertTrue(one("What's the date today?", "date_time").sure)
        assertEquals("pdf", one("Write a leave letter and save it as a PDF", "create_document").input)
        assertEquals("docx", one("make this a word document", "create_document").input)
        assertEquals("+91 98765 43210", one("call +91 98765 43210", "phone_call").input)
        assertTrue(!one("Remember that my gym days are Tuesday and Friday", "note_manager").sure)
    }

    @Test fun pastedScamIsCheckedAutomatically() {
        val msg = "Is this genuine? Dear Customer, your SBI account will be BLOCKED today. Update KYC at http://sbi-kyc-update.xyz"
        assertTrue(one(msg, "scam_check").sure)
        assertTrue("knowledge search is skipped for scam checks", "knowledge_search" !in tools(msg, kb = true))
    }

    @Test fun knowledgeOnlyWhenThereIsKnowledge() {
        assertEquals(emptyList<String>(), tools("When do I get my deposit back?"))
        assertTrue(!one("When do I get my deposit back?", "knowledge_search", kb = true).sure)
        assertTrue(one("Check my lease for the notice period", "knowledge_search", kb = true).sure)
    }
}
