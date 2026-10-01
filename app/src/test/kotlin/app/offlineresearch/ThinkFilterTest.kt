package app.offlineresearch

import app.offlineresearch.ui.stripThinking
import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkFilterTest {

    @Test
    fun plainTextIsUnchanged() {
        assertEquals("Paris is the capital.", stripThinking("Paris is the capital."))
    }

    @Test
    fun emptyThinkBlockFromNoThinkModeIsRemoved() {
        assertEquals("Paris.", stripThinking("<think>\n\n</think>\n\nParis."))
    }

    @Test
    fun closedThinkBlockIsRemoved() {
        assertEquals("Answer", stripThinking("<think>step 1\nstep 2</think>Answer"))
    }

    @Test
    fun unclosedThinkBlockHidesEverythingAfterIt() {
        assertEquals("", stripThinking("<think>still reasoning"))
    }

    @Test
    fun textBeforeAnUnclosedBlockIsKept() {
        assertEquals("Intro ", stripThinking("Intro <think>more"))
    }
}
