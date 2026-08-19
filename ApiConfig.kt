package com.vidhi.ai

object ApiConfig {
    // Set this to your deployed HTTPS backend URL before building a production APK.
    // For Android Emulator + local backend, use http://10.0.2.2:3000
    const val BASE_URL = "https://vidhi-ai-wtko.onrender.com"

    const val CONNECT_TIMEOUT_SECONDS = 20L
    const val READ_TIMEOUT_SECONDS = 90L
}
