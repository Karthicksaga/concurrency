package com.localai.app

import com.localai.app.data.ocr.Ocr
import com.localai.app.data.ocr.OcrPacks
import com.localai.app.data.safety.ScamCheck
import com.localai.app.data.safety.ScamCheck.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScamCheckTest {
    private fun level(s: String) = ScamCheck.analyze(s).level
    private fun ids(s: String) = ScamCheck.analyze(s).flags.map { it.id }.toSet()

    @Test fun commonScamsAreHigh() {
        // the exact text OCR read from a rendered screenshot on the host (see ADR-0016)
        val kyc = "VM-SBIUPD Dear Customer, your SBI account will be BLOCKED today. Update your KYC immediately at http://sbi-kyc-update.xyz/login or call 9876543210. Do not ignore."
        assertEquals(Level.HIGH, level(kyc))
        assertTrue(ids(kyc).containsAll(listOf("kyc", "lookalike", "urgent", "call_number")))

        assertEquals(Level.HIGH, level("You have received Rs 5,000 cashback! Enter your UPI PIN to receive the amount."))
        assertEquals(Level.HIGH, level("Dear consumer your electricity power connection will be disconnected tonight at 9.30 pm because previous month bill was not updated. Please call 8123456789 immediately."))
        assertEquals(Level.HIGH, level("This is CBI. A parcel in your name with drugs was seized at customs. You are under digital arrest. Stay on video call."))
        assertEquals(Level.HIGH, level("Part time job! Like YouTube videos and earn Rs 3000 per day. Join telegram for prepaid task."))
        assertEquals(Level.HIGH, level("Please install AnyDesk so our bank officer can help you update your card."))
        assertEquals(Level.HIGH, level("Congratulations! You won KBC lottery of 25 lakh. Pay processing fee of Rs 9,999 to receive the prize."))
        assertEquals(Level.HIGH, level("Share the OTP you just received to confirm your delivery."))
    }

    @Test fun indianLanguageScams() {
        assertEquals(Level.HIGH, level("உங்கள் மின்சார கட்டணம் செலுத்தப்படவில்லை. இன்று இரவு 9 மணிக்கு மின் இணைப்பு துண்டிக்கப்படும். அழைக்கவும் 9876543210"))
        assertTrue(level("आपका केवाईसी अपडेट नहीं है, खाता बंद हो जाएगा। तुरंत लिंक पर क्लिक करें bit.ly/abc123") != Level.LOW)
    }

    @Test fun genuineMessagesAreNotFlaggedHigh() {
        val bank = "Rs.2,500.00 debited from A/c XX1234 on 14-11-26 to VPA fresh.mart@okaxis. Not you? Call 18001234 or SMS BLOCK to 9223008333. Do not share OTP with anyone. -SBI"
        assertTrue(level(bank) != Level.HIGH)
        assertEquals(Level.LOW, level("Your OTP for login is 482913. Do not share it with anyone. Valid for 5 minutes. - HDFC Bank"))
        assertEquals(Level.LOW, level("Hi Karthik, dinner at 8 tonight? Mom made biryani."))
        assertEquals(Level.LOW, level("Your Amazon order #402-1 has shipped. Track at https://www.amazon.in/orders"))
    }

    @Test fun linksAreClassified() {
        val r = ScamCheck.analyze("Claim now: https://bit.ly/3xYz and http://192.168.4.20/pay and https://hdfc-reward.top/x and https://www.hdfcbank.com/personal")
        val ids = r.flags.map { it.id }
        assertTrue(ids.containsAll(listOf("short", "ip", "lookalike", "http")))
        assertTrue(r.flags.none { it.evidence.contains("hdfcbank.com") })
        assertEquals("hdfcbank.com", ScamCheck.host("https://www.hdfcbank.com/personal"))
    }

    @Test fun ocrCleanupAndPacks() {
        // what Tesseract printed for the screenshot: link split after a hyphen, ZWNJ after Tamil words
        assertEquals("at http://sbi-kyc-update.xyz/login", Ocr.cleanup("at http://sbi-kyc-\nupdate.xyz/login"))
        assertEquals("உங்கள் மின்சார", Ocr.cleanup("உங்கள்‌ மின்சார"))
        assertEquals("information", Ocr.cleanup("infor-\nmation"))
        assertEquals("eng+tam", OcrPacks.spec(listOf("tam", "hin"), installed = { it == "tam" }))
        assertEquals("tam", OcrPacks.forLanguageTag("ta-IN"))
        assertTrue(OcrPacks.all.all { it.sha256.length == 64 && it.sizeBytes > 500_000 })
    }
}
