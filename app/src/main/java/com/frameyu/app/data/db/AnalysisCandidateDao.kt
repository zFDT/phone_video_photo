package com.frameyu.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 按媒体资源管理分析候选帧。 */
@Dao
interface AnalysisCandidateDao {
    @Query("SELECT * FROM analysis_candidates WHERE media_asset_id = :mediaAssetId ORDER BY score DESC")
    suspend fun getByMediaAssetId(mediaAssetId: Long): List<AnalysisCandidateEntity>

    @Query("SELECT * FROM analysis_candidates WHERE media_asset_id = :mediaAssetId ORDER BY score DESC")
    fun observeByMediaAssetId(mediaAssetId: Long): Flow<List<AnalysisCandidateEntity>>

    @Query("UPDATE analysis_candidates SET is_cover = 0 WHERE media_asset_id = :mediaAssetId")
    suspend fun clearOtherCovers(mediaAssetId: Long)

    @Query(
        """
        UPDATE analysis_candidates
        SET is_cover = 1
        WHERE id = :candidateId AND media_asset_id = :mediaAssetId
        """
    )
    suspend fun setCoverCandidate(mediaAssetId: Long, candidateId: Long)

    @Transaction
    suspend fun setCover(mediaAssetId: Long, candidateId: Long) {
        clearOtherCovers(mediaAssetId)
        setCoverCandidate(mediaAssetId, candidateId)
    }

    @Query("SELECT * FROM analysis_candidates WHERE media_asset_id = :mediaAssetId AND is_cover = 1 LIMIT 1")
    suspend fun getCover(mediaAssetId: Long): AnalysisCandidateEntity?

    @Query("SELECT * FROM analysis_candidates WHERE media_asset_id = :mediaAssetId AND is_cover = 1 LIMIT 1")
    fun observeCover(mediaAssetId: Long): Flow<AnalysisCandidateEntity?>

    @Query(
        """
        UPDATE analysis_candidates
        SET is_selected = :selected
        WHERE id = :candidateId AND media_asset_id = :mediaAssetId
        """
    )
    suspend fun setSelected(mediaAssetId: Long, candidateId: Long, selected: Boolean)

    @Query("UPDATE analysis_candidates SET is_selected = :selected WHERE media_asset_id = :mediaAssetId")
    suspend fun setAllSelected(mediaAssetId: Long, selected: Boolean)

    @Query("DELETE FROM analysis_candidates WHERE media_asset_id = :mediaAssetId")
    suspend fun deleteByMediaAssetId(mediaAssetId: Long)

    @Upsert
    suspend fun upsertAll(candidates: List<AnalysisCandidateEntity>)
}
