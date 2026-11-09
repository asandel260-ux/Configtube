package com.multikit.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class MultiKitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Required once before any PDFBox-Android operation.
        PDFBoxResourceLoader.init(applicationContext)
    }
}