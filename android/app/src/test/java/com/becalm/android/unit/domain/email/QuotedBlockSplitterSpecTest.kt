package com.becalm.android.unit.domain.email

import com.becalm.android.domain.email.QuotedBlockSplitter
import com.becalm.android.domain.email.SplitResult
import org.junit.Assert.assertEquals
import org.junit.Test

class QuotedBlockSplitterSpecTest {

    private val splitter = QuotedBlockSplitter()

    @Test
    fun `EMAIL-005 keeps full body in commitment when no quote marker exists`() {
        assertEquals(
            SplitResult(
                commitment = "새 약속 본문만 있습니다.",
                quoted = null,
            ),
            splitter.split("  새 약속 본문만 있습니다.  "),
        )
    }

    @Test
    fun `EMAIL-005 splits at gmail On wrote sentinel`() {
        assertEquals(
            SplitResult(
                commitment = "이번 주까지 전달드릴게요.",
                quoted = "On Mon, Dec 18, 2023 at 3:45 PM John Doe <john@example.com> wrote:\n지난번 약속 내용",
            ),
            splitter.split(
                "이번 주까지 전달드릴게요.\n\nOn Mon, Dec 18, 2023 at 3:45 PM John Doe <john@example.com> wrote:\n지난번 약속 내용",
            ),
        )
    }

    @Test
    fun `EMAIL-005 chooses earliest visible quote marker when angle quote precedes sentinel`() {
        assertEquals(
            SplitResult(
                commitment = "새 본문",
                quoted = "> 지난 답장\nOn Mon, Dec 18, 2023 at 3:45 PM John Doe <john@example.com> wrote:\nolder",
            ),
            splitter.split(
                "새 본문\n> 지난 답장\nOn Mon, Dec 18, 2023 at 3:45 PM John Doe <john@example.com> wrote:\nolder",
            ),
        )
    }

    @Test
    fun `EMAIL-005 does not treat korean reply header as quoted marker in current MVP`() {
        assertEquals(
            SplitResult(
                commitment = "새 본문\n2023년 12월 18일 오후 3:45, 홍길동 님이 작성:\n이전 메일",
                quoted = null,
            ),
            splitter.split("새 본문\n2023년 12월 18일 오후 3:45, 홍길동 님이 작성:\n이전 메일"),
        )
    }
}
