package com.example.aiphotoapp

object ImageEditPrompt {
    enum class Mode { REMOVE_BACKGROUND, REPLACE_BACKGROUND, CHANGE_CLOTHES, CHANGE_CLOTHES_COLOR }

    fun build(mode: Mode, instruction: String): String {
        val operation = when (mode) {
            Mode.REMOVE_BACKGROUND -> "Remove the background and make it transparent."
            Mode.REPLACE_BACKGROUND -> "Replace the background with the requested scene."
            Mode.CHANGE_CLOTHES -> "Change only the subject's clothes as requested. Keep face, body, pose and identity unchanged."
            Mode.CHANGE_CLOTHES_COLOR -> "Change only the subject's clothing color or style as requested. Keep face, body, pose and identity unchanged."
        }
        return "$operation Keep the subject unchanged. $instruction".trim().take(500)
    }
}
