package ru.dvedev.me.yaphotoframe.media.yandex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ответ листинга публичного ресурса. Описаны только те поля, которыми пользуется
 * рамка, — остальное в ответе есть, но нам не нужно.
 */
@Serializable
internal data class PublicResourceDto(
    val name: String? = null,
    val type: String? = null,
    @SerialName("_embedded") val embedded: EmbeddedDto? = null,
)

@Serializable
internal data class EmbeddedDto(
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
    val items: List<ResourceItemDto> = emptyList(),
)

@Serializable
internal data class ResourceItemDto(
    val name: String,
    val path: String,
    val type: String,
    val size: Long = 0,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    val preview: String? = null,
    val created: String? = null,
    val modified: String? = null,
    val exif: ExifDto? = null,
)

@Serializable
internal data class ExifDto(
    @SerialName("date_time") val dateTime: String? = null,
)

@Serializable
internal data class DownloadLinkDto(
    val href: String,
)
