package com.becalm.android.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit

/**
 * nullable Preferences 값에 대한 set-or-remove 패턴을 한 곳으로 통합하는 확장 함수.
 *
 * ## 시그니처 근거
 * [SyncCursorStoreImpl]과 [UserPrefsStoreImpl]에 각각 존재하던 private `editNullable`
 * 메서드를 합친 것이다. 두 호출부의 동작이 완전히 동일했기 때문에 — 단일
 * [DataStore.edit] 트랜잭션 안에서 value가 null이면 `remove(key)`, 아니면
 * `prefs[key] = value` — byte-identical 거동을 그대로 보존한다.
 */
internal suspend fun <T> DataStore<Preferences>.editNullable(
    key: Preferences.Key<T>,
    value: T?,
) {
    edit { prefs ->
        if (value != null) prefs[key] = value else prefs.remove(key)
    }
}
