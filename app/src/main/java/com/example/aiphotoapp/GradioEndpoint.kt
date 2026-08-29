package com.example.aiphotoapp

object GradioEndpoint {
    fun normalize(name: String): String = name.trim().trimStart('/')
}
