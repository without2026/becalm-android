package com.becalm.android.domain.meeting

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

    public fun targetRelativePath(kind: MeetingImportFolderKind): String =
        "Recordings/$MEETINGS_DIR/${targetDirectoryName(kind)}/%"

    public fun targetDataPath(kind: MeetingImportFolderKind): String =
        "%/Recordings/$MEETINGS_DIR/${targetDirectoryName(kind)}/%"

    public const val MEETINGS_RELATIVE_PATH_PATTERN: String = "Recordings/$MEETINGS_DIR/%"
    public const val MEETINGS_DATA_PATH_PATTERN: String = "%/Recordings/$MEETINGS_DIR/%"
}
