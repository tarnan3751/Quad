package com.campusmarket.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher

/**
 * A node read out as a summary that starts with [prefix], e.g. an inbox row ("Maya Chen, Desk lamp. …") or a chat
 * bubble: those clear their children's texts, so `hasText` no longer finds them.
 */
fun hasContentDescriptionStartingWith(prefix: String): SemanticsMatcher =
    SemanticsMatcher("ContentDescription starts with '$prefix'") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(prefix) } == true
    }
