package com.becalm.android.domain.meeting

import android.net.Uri
import android.provider.DocumentsContract

public enum class MeetingImportFolderKind {
    Audio,
}

public object MeetingImportFolders {
    public const val MEETINGS_DIR: String = "BeCalm Meetings"
    public const val AUDIO_DIR: String = "Audio"

    public fun targetDirectoryName(kind: MeetingImportFolderKind): String =
        when (kind) {
            MeetingImportFolderKind.Audio -> AUDIO_DIR
        }

    public fun targetDirectoryDocumentUri(treeUri: Uri, kind: MeetingImportFolderKind): Uri {
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri).trimEnd('/')
        val targetDocumentId = listOf(
            rootDocumentId,
            MEETINGS_DIR,
            targetDirectoryName(kind),
        ).joinToString("/")
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, targetDocumentId)
    }

    public fun targetRelativePath(kind: MeetingImportFolderKind): String =
        "Recordings/$MEETINGS_DIR/${targetDirectoryName(kind)}/%"

    public fun targetDataPath(kind: MeetingImportFolderKind): String =
        "%/Recordings/$MEETINGS_DIR/${targetDirectoryName(kind)}/%"

    public const val MEETINGS_RELATIVE_PATH_PATTERN: String = "Recordings/$MEETINGS_DIR/%"
    public const val MEETINGS_DATA_PATH_PATTERN: String = "%/Recordings/$MEETINGS_DIR/%"
}
