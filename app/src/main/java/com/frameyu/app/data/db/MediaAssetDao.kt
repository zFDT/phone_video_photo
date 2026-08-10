package com.frameyu.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 媒体资源的查询和批量写入接口。 */
@Dao
interface MediaAssetDao {
    @Query("SELECT * FROM media_assets WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): MediaAssetEntity?

    @Query("SELECT * FROM media_assets WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MediaAssetEntity?

    @Query("SELECT * FROM media_assets WHERE uri IN (:uris)")
    suspend fun getByUris(uris: List<String>): List<MediaAssetEntity>

    @Query("DELETE FROM media_assets WHERE uri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>): Int

    @Query("SELECT COUNT(*) FROM media_assets")
    suspend fun count(): Int

    @Query("SELECT * FROM media_assets ORDER BY taken_at_millis DESC")
    suspend fun getAll(): List<MediaAssetEntity>

    @Query("SELECT * FROM media_assets WHERE mime_type LIKE 'video/%' ORDER BY taken_at_millis DESC")
    fun observeVideos(): Flow<List<MediaAssetEntity>>

    @Query("SELECT * FROM media_assets ORDER BY taken_at_millis DESC")
    fun observeAll(): Flow<List<MediaAssetEntity>>

    @Upsert
    suspend fun upsertAll(assets: List<MediaAssetEntity>)
}
