package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profile")
data class UserProfile(
    @PrimaryKey val id: Int = 1,
    val selectedGoal: String = "UPSC", // "UPSC", "JEE_NEET", "ENGINEERING"
    val studyTimeMinutes: Int = 45,
    val questionsSolved: Int = 12,
    val isPremiumUnlocked: Boolean = false
)

@Entity(tableName = "study_notes")
data class StudyNote(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val content: String,
    val summary: String,
    val keyTakeaways: String, // Comma or newline separated
    val timestamp: Long = System.currentTimeMillis(),
    val category: String
)

@Entity(tableName = "quizzes")
data class Quiz(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val associatedNoteId: Int? = null,
    val category: String,
    val questionsJson: String, // JSON serialized list of QuizQuestion
    val highScore: Int = 0,
    val totalAttempts: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

data class QuizQuestion(
    val question: String,
    val options: List<String>,
    val correctAnswerIndex: Int,
    val explanation: String
)

@Entity(tableName = "flashcards")
data class Flashcard(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val term: String,
    val definition: String,
    val isKnown: Boolean = false,
    val category: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "mock_interview_sessions")
data class MockInterviewSession(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val category: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "mock_interview_messages")
data class MockInterviewMessage(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sessionId: Int,
    val role: String, // "interviewer" or "user"
    val message: String,
    val score: Int? = null, // scored 1 to 10 by AI
    val feedback: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "difficult_topic_explanations")
data class DifficultTopicExplanation(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val topic: String,
    val explanation: String,
    val analogy: String,
    val keyPointsJson: String, // JSON array of key takeaways
    val category: String,
    val timestamp: Long = System.currentTimeMillis()
)
