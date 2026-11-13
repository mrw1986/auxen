package io.github.auxen.db

import io.github.auxen.model.Source
import io.github.auxen.model.Track

fun Track.toEntity(): TrackEntity = TrackEntity(
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    genre = genre,
    year = year,
    durationSeconds = durationSeconds,
    trackNumber = trackNumber,
    discNumber = discNumber,
    source = source.name,
    sourceId = sourceId,
    bitrateKbps = bitrateKbps,
    format = format,
    sampleRateHz = sampleRateHz,
    bitDepth = bitDepth,
    albumArtUrl = albumArtUrl,
    matchGroupId = matchGroupId,
    explicit = explicit,
)

fun TrackEntity.toTrack(): Track = Track(
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    genre = genre,
    year = year,
    durationSeconds = durationSeconds,
    trackNumber = trackNumber,
    discNumber = discNumber,
    source = Source.valueOf(source),
    sourceId = sourceId,
    bitrateKbps = bitrateKbps,
    format = format,
    sampleRateHz = sampleRateHz,
    bitDepth = bitDepth,
    albumArtUrl = albumArtUrl,
    matchGroupId = matchGroupId,
    explicit = explicit,
)
