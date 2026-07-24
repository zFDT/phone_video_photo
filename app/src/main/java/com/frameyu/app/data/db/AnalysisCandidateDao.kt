package com.frameyu.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 按媒体资源管理分析候选帧。 */
@Dao
interface AnalysisCandidateDao {
    @Query("SELECT * FROM analysis_candidates WHERE media_asset_id = :mediaAssetId ORDER BY score DESC")
    suspend fun getByMediaAssetId(mediaAssetId: Long): List<AnalysisCandidateEntity>

    @Query("SELECT * FROM analysis_candidates WHERE media_asset_id = :mediaAssetId ORDER BY score DESC")
    fun observeByMediaAssetId(mediaAssetId: Long): Flow<List<AnalysisCandidateEntity>>

    @Query("DELETE FROM analysis_candidates WHERE media_asset_id = :mediaAssetId")
    suspend fun deleteByMediaAssetId(mediaAssetId: Long)

    @Upsert
    suspend fun upsertAll(candidates: List<AnalysisCandidateEntity>)
}
