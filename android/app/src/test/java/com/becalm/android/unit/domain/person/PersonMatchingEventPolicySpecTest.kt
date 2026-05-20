package com.becalm.android.unit.domain.person

import com.becalm.android.domain.person.PersonMatchingEventPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonMatchingEventPolicySpecTest {
    @Test
    fun `service account verification notice is hidden`() {
        assertTrue(
            PersonMatchingEventPolicy.isLikelyServiceAccountNotification(
                title = "Slack 이메일 주소를 확인하세요",
                snippet = "워크스페이스에 가입하려면 이메일 주소를 확인해 주세요.",
                suggestedLabel = "Slack",
            ),
        )
    }

    @Test
    fun `person to person work mail mentioning account verification is not hidden`() {
        assertFalse(
            PersonMatchingEventPolicy.isLikelyServiceAccountNotification(
                title = "리마인더 FW: 서버비 결제완료 안내 RE: Google Android 개발자 인증 비용 검토",
                snippet = "창업동아리 지원금 지출 관련 서류 회신을 받지 못하여 리마인더 드립니다. 확인 부탁드립니다.",
                suggestedLabel = "송지은",
            ),
        )
    }
}
