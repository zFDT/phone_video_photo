package com.frameyu.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** 媒体资源的查询和批量写入接口。 */
@Dao
interface MediaAssetDao {
    @Query("SELECT * FROM media_assets WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): MediaAssetEntity?

    @Upsert
    suspend fun upsertAll(assets: List<MediaAssetEntity>)
}
