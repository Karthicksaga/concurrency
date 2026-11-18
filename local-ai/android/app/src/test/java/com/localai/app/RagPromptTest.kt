package com.localai.app

import com.localai.app.data.agents.AgentRunner
import com.localai.app.runtime.inference.ChatTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagPromptTest {
    private val h = listOf(ChatTurn("user", "What is the notice period in my lease?"), ChatTurn("assistant", "Two months [1]."), ChatTurn("user", "explain more"))

    @Test fun followUpsSearchWithThePreviousQuestion() {
        assertEquals("What is the notice period in my lease? explain more", AgentRunner.searchQuery(h))
        assertEquals("What is the deposit amount for the flat?", AgentRunner.searchQuery(h + ChatTurn("user", "What is the deposit amount for the flat?")))
    }

    @Test fun sourcesGoRightBeforeTheQuestion() {
        val out = AgentRunner.withSources(h, "<source n=\"1\">Notice: 2 months</source>")
        assertEquals(3, out.size)
        assertTrue(out.last().content.startsWith("<source"))
        assertTrue(out.last().content.endsWith("Question: explain more"))
        assertEquals(h[0], out[0])
    }
}
