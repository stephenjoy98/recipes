package com.stephenjoy.recipes.extract

import com.stephenjoy.recipes.ai.RecipePrompts
import com.stephenjoy.recipes.domain.SourcePlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramPageTest {

    @Test
    fun `finds the video url in embedded json and og tags`() {
        val embed = """<script>window.__x = {"shortcode_media":{"is_video":true,"video_url":"https:\/\/scontent.cdninstagram.com\/v\/t50\/reel.mp4?efg=abc&oh=1"}}</script>"""
        assertEquals("https://scontent.cdninstagram.com/v/t50/reel.mp4?efg=abc&oh=1", InstagramPage.videoUrl(embed))

        val og = """<meta property="og:video:secure_url" content="https://cdn.example.com/v.mp4?a=1&amp;b=2" />"""
        assertEquals("https://cdn.example.com/v.mp4?a=1&b=2", InstagramPage.videoUrl(og))

        assertNull(InstagramPage.videoUrl("<html>no video</html>"))
    }

    @Test
    fun `reads comments with the creator first`() {
        val html = """
            <script type="application/json">{"data":{"edge_media_to_parent_comment":{"edges":[
              {"node":{"text":"Can I use butter?","owner":{"username":"fan1"}}},
              {"node":{"text":"Full recipe: 2 cups flour, 3 eggs, 1 cup milk","owner":{"username":"chef"}}}
            ]},"edge_media_to_caption":{"edges":[{"node":{"text":"Pancakes!"}}]}}}</script>
            <script type="application/ld+json">{"@type":"SocialMediaPosting","comment":[
              {"@type":"Comment","text":"Looks great","author":{"@type":"Person","alternateName":"fan2"}}]}</script>
        """.trimIndent()
        val comments = InstagramPage.comments(html, owner = "chef")
        assertEquals("chef", comments.first().author)
        assertTrue(comments.first().text.startsWith("Full recipe"))
        assertEquals(setOf("chef", "fan1", "fan2"), comments.map { it.author }.toSet())
        // The caption node has no owner, so it isn't mistaken for a comment.
        assertTrue(comments.none { it.text == "Pancakes!" })
    }

    @Test
    fun `prompt includes video text and comments within budget`() {
        val source = SourceContent(
            url = "https://www.instagram.com/reel/x/",
            platform = SourcePlatform.INSTAGRAM,
            author = "chef",
            text = "x".repeat(20_000),
            onScreenText = "2 cups flour\n3 eggs",
            comments = listOf(PostComment("chef", "Recipe in full: bake 20 min")),
        )
        val prompt = RecipePrompts.recipePrompt(source, maxChars = 9_000)
        assertTrue(prompt.contains("Text shown on screen in the video:\n2 cups flour\n3 eggs"))
        assertTrue(prompt.contains("- chef (creator): Recipe in full: bake 20 min"))
        assertTrue(prompt.length < 11_000)
    }

    @Test
    fun `budgets give short sections everything and share the rest`() {
        assertEquals(listOf(100, 0, 450, 450), RecipePrompts.budgets(listOf(100, 0, 5000, 9000), 1000))
        assertEquals(listOf(10, 20), RecipePrompts.budgets(listOf(10, 20), 1000))
    }
}
