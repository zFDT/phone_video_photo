package com.frameyu.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 按媒体资源管理分析候选帧。 */
@Dao
interface AnalysisCandidateDao {
    @Query("SELECT * FROM analysis_candidates ORDER BY score DESC, id")
    fun observeAll(): Flow<List<AnalysisCandidateEntity>>

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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRuleCandidate(candidate: AnalysisCandidateEntity): Long

    @Query(
        """
        UPDATE analysis_candidates
        SET media_asset_id = :mediaAssetId,
            timestamp_millis = :timestampMillis,
            score = :score,
            sharpness_score = :sharpnessScore,
            subject_score = :subjectScore,
            expression_score = :expressionScore,
            composition_score = :compositionScore,
            scene_score = :sceneScore,
            thumbnail_path = :thumbnailPath,
            model_status = :modelStatus,
            model_error_message = :modelErrorMessage
        WHERE id = :id
        """
    )
    suspend fun updateRuleCandidateFields(
        id: Long,
        mediaAssetId: Long,
        timestampMillis: Long,
        score: Int,
        sharpnessScore: Int,
        subjectScore: Int,
        expressionScore: Int,
        compositionScore: Int,
        sceneScore: Int,
        thumbnailPath: String?,
        modelStatus: String,
        modelErrorMessage: String?,
    ): Int

    /** 更新规则证据和模型状态，但保留用户选择与封面标记。 */
    @Transaction
    suspend fun upsertRuleCandidate(candidate: AnalysisCandidateEntity) {
        if (insertRuleCandidate(candidate) != -1L) return
        check(
            updateRuleCandidateFields(
                id = candidate.id,
                mediaAssetId = candidate.mediaAssetId,
                timestampMillis = candidate.timestampMillis,
                score = candidate.score,
                sharpnessScore = candidate.sharpnessScore,
                subjectScore = candidate.subjectScore,
                expressionScore = candidate.expressionScore,
                compositionScore = candidate.compositionScore,
                sceneScore = candidate.sceneScore,
                thumbnailPath = candidate.thumbnailPath,
                modelStatus = candidate.modelStatus,
                modelErrorMessage = candidate.modelErrorMessage,
            ) == 1,
        ) { "更新规则候选失败：${candidate.id}" }
    }

    @Query(
        """
        UPDATE analysis_candidates
        SET model_status = :status,
            model_error_message = :errorMessage
        WHERE id = :candidateId
        """
    )
    suspend fun updateModelStatus(
        candidateId: Long,
        status: String,
        errorMessage: String?,
    ): Int
}
