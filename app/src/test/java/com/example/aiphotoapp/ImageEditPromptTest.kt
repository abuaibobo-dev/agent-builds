package com.example.aiphotoapp

import org.junit.Assert.assertTrue
import org.junit.Test

class ImageEditPromptTest {
    @Test
    fun buildsSafePromptForBackgroundRemoval() {
        val prompt = ImageEditPrompt.build(ImageEditPrompt.Mode.REMOVE_BACKGROUND, "人物保持不变")

        assertTrue(prompt.contains("remove the background"))
        assertTrue(prompt.contains("keep the subject unchanged"))
    }
}
