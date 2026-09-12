package com.displayio.model

import androidx.annotation.Keep

@Keep
data class SequenceData(
    val languages: List<String>? = null,
    val sequenceName: String,
    val steps: List<RobotStep>,
    val otherBroadcasts: List<OtherBroadcast>? = null
)

@Keep
data class RobotStep(
    val id: String,
    val title: String,
    val actions: List<RobotAction>
)

@Keep
data class RobotAction(
    val id: String,
    val name: String,
    val imageFileName: String? = null,
    val audioFileNames: Map<String, List<String>>,
    val en_des: String? = null,
    val subAudioName: Map<String, String>? = null,
    val subAudioFileName: Map<String, String>? = null,
    val subAudioEnDes: Map<String, String>? = null,
    val isMandatory: Boolean = true
)

@Keep
data class OtherBroadcast(
    val name: String,
    val audioFileName: Map<String, String>,
    val en_des: Map<String, String>? = null
)