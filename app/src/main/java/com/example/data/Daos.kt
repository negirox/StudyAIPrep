package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ExamPrepDao {

    // --- User Profile ---
    @Query("SELECT * FROM user_profile WHERE id = 1")
    fun getUserProfileFlow(): Flow<UserProfile?>

    @Query("SELECT * FROM user_profile WHERE id = 1")
    suspend fun getUserProfile(): UserProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserProfile(profile: UserProfile)

    // --- Study Notes ---
    @Query("SELECT * FROM study_notes ORDER BY timestamp DESC")
    fun getAllNotesFlow(): Flow<List<StudyNote>>

    @Query("SELECT * FROM study_notes WHERE id = :id")
    suspend fun getNoteById(id: Int): StudyNote?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: StudyNote): Long

    @Query("DELETE FROM study_notes WHERE id = :id")
    suspend fun deleteNoteById(id: Int)

    // --- Quizzes ---
    @Query("SELECT * FROM quizzes ORDER BY timestamp DESC")
    fun getAllQuizzesFlow(): Flow<List<Quiz>>

    @Query("SELECT * FROM quizzes WHERE id = :id")
    suspend fun getQuizById(id: Int): Quiz?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuiz(quiz: Quiz): Long

    @Query("DELETE FROM quizzes WHERE id = :id")
    suspend fun deleteQuizById(id: Int)

    // --- Flashcards ---
    @Query("SELECT * FROM flashcards ORDER BY timestamp DESC")
    fun getAllFlashcardsFlow(): Flow<List<Flashcard>>

    @Query("SELECT * FROM flashcards WHERE category = :category ORDER BY timestamp DESC")
    fun getFlashcardsByCategoryFlow(category: String): Flow<List<Flashcard>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFlashcard(flashcard: Flashcard): Long

    @Update
    suspend fun updateFlashcard(flashcard: Flashcard)

    @Query("DELETE FROM flashcards WHERE id = :id")
    suspend fun deleteFlashcardById(id: Int)

    // --- Mock Interview Sessions ---
    @Query("SELECT * FROM mock_interview_sessions ORDER BY timestamp DESC")
    fun getAllInterviewSessionsFlow(): Flow<List<MockInterviewSession>>

    @Query("SELECT * FROM mock_interview_sessions WHERE id = :id")
    suspend fun getInterviewSessionById(id: Int): MockInterviewSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInterviewSession(session: MockInterviewSession): Long

    @Query("DELETE FROM mock_interview_sessions WHERE id = :id")
    suspend fun deleteInterviewSessionById(id: Int)

    // --- Mock Interview Messages ---
    @Query("SELECT * FROM mock_interview_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSessionFlow(sessionId: Int): Flow<List<MockInterviewMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInterviewMessage(message: MockInterviewMessage): Long

    @Query("DELETE FROM mock_interview_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: Int)

    // --- Difficult Topic Explanations ---
    @Query("SELECT * FROM difficult_topic_explanations ORDER BY timestamp DESC")
    fun getAllExplanationsFlow(): Flow<List<DifficultTopicExplanation>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExplanation(explanation: DifficultTopicExplanation): Long

    @Query("DELETE FROM difficult_topic_explanations WHERE id = :id")
    suspend fun deleteExplanationById(id: Int)
}
