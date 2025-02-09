package com.github.roscrl.inlineaichat

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SimpleTest : BasePlatformTestCase() {
    
    fun testBasicPluginLoad() {
        assertTrue(true)
    }

    override fun getTestDataPath(): String {
        return "src/test/testData"
    }
} 