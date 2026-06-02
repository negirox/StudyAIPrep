package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.api.GeminiService
import com.example.api.NotesStudyPackJson
import com.example.api.QuizQuestionJson
import com.example.data.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExamPrepViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getDatabase(application).examPrepDao()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    // --- Core UI State Observables (Room Reactive Flows) ---
    val userProfile: StateFlow<UserProfile?> = dao.getUserProfileFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val studyNotes: StateFlow<List<StudyNote>> = dao.getAllNotesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val quizzes: StateFlow<List<Quiz>> = dao.getAllQuizzesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val explanations: StateFlow<List<DifficultTopicExplanation>> = dao.getAllExplanationsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val interviewSessions: StateFlow<List<MockInterviewSession>> = dao.getAllInterviewSessionsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Interactive Local/Session UI State ---
    private val _selectedNote = MutableStateFlow<StudyNote?>(null)
    val selectedNote = _selectedNote.asStateFlow()

    private val _activeQuiz = MutableStateFlow<Quiz?>(null)
    val activeQuiz = _activeQuiz.asStateFlow()

    private val _currentQuestionIndex = MutableStateFlow(0)
    val currentQuestionIndex = _currentQuestionIndex.asStateFlow()

    private val _selectedOptionIndex = MutableStateFlow<Int?>(null)
    val selectedOptionIndex = _selectedOptionIndex.asStateFlow()

    private val _isAnswerSubmitted = MutableStateFlow(false)
    val isAnswerSubmitted = _isAnswerSubmitted.asStateFlow()

    private val _quizCorrectAnswersCount = MutableStateFlow(0)
    val quizCorrectAnswersCount = _quizCorrectAnswersCount.asStateFlow()

    private val _quizCompleted = MutableStateFlow(false)
    val quizCompleted = _quizCompleted.asStateFlow()

    // --- Loading & Thinking Indicators ---
    private val _isGeneratingPack = MutableStateFlow(false)
    val isGeneratingPack = _isGeneratingPack.asStateFlow()

    private val _isExplainingTopic = MutableStateFlow(false)
    val isExplainingTopic = _isExplainingTopic.asStateFlow()

    private val _isInterviewThinking = MutableStateFlow(false)
    val isInterviewThinking = _isInterviewThinking.asStateFlow()

    // --- Flashcard Filters ---
    private val _flashcardFilterCategory = MutableStateFlow("ALL")
    val flashcardFilterCategory = _flashcardFilterCategory.asStateFlow()

    val filteredFlashcards: StateFlow<List<Flashcard>> = combine(
        dao.getAllFlashcardsFlow(),
        _flashcardFilterCategory
    ) { cards, category ->
        if (category == "ALL") cards else cards.filter { it.category == category }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Mock Interview Session Messages ---
    private val _activeSessionId = MutableStateFlow<Int?>(null)
    val activeSessionId = _activeSessionId.asStateFlow()

    val sessionMessages: StateFlow<List<MockInterviewMessage>> = _activeSessionId.flatMapLatest { id ->
        if (id != null) dao.getMessagesForSessionFlow(id) else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Prepare initial user database on first launch
        viewModelScope.launch(Dispatchers.IO) {
            val existing = dao.getUserProfile()
            if (existing == null) {
                // Insert initial default profile
                dao.insertUserProfile(UserProfile())
                // Populate high quality, rich initial pre-loaded materials
                preloadDefaultMaterials()
            }
        }
    }

    // --- User Profile Commands ---
    fun changeGoal(newGoal: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = dao.getUserProfile() ?: UserProfile()
            dao.insertUserProfile(current.copy(selectedGoal = newGoal))
            // Generate some dynamic mock notes or cards if database is empty for the target
            preloadGoalSpecificMaterials(newGoal)
        }
    }

    fun incrementStudyTime(minutes: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = dao.getUserProfile() ?: UserProfile()
            dao.insertUserProfile(current.copy(studyTimeMinutes = current.studyTimeMinutes + minutes))
        }
    }

    fun togglePremiumSimulation(unlocked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = dao.getUserProfile() ?: UserProfile()
            dao.insertUserProfile(current.copy(isPremiumUnlocked = unlocked))
        }
    }

    // --- Feature 1: Study Notes Generation ---
    fun selectNote(note: StudyNote?) {
        _selectedNote.value = note
    }

    fun deleteNote(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteNoteById(id)
            if (selectedNote.value?.id == id) {
                _selectedNote.value = null
            }
        }
    }

    fun analyzeAndSaveNotes(title: String, content: String, category: String) {
        _isGeneratingPack.value = true
        viewModelScope.launch {
            try {
                val pack = withContext(Dispatchers.IO) {
                    GeminiService.generateStudyPack(title, content, category)
                }
                
                withContext(Dispatchers.IO) {
                    // 1. Save Note with AI Summary
                    val noteId = dao.insertNote(
                        StudyNote(
                            title = title,
                            content = content,
                            summary = pack.summary,
                            keyTakeaways = pack.keyTakeaways.joinToString("\n"),
                            category = category
                        )
                    ).toInt()

                    // 2. Save Associated Quiz
                    val quizQuestions = pack.quizzes.map {
                        QuizQuestion(
                            question = it.question,
                            options = it.options,
                            correctAnswerIndex = it.correctAnswerIndex,
                            explanation = it.explanation
                        )
                    }
                    val quizType = Types.newParameterizedType(List::class.java, QuizQuestion::class.java)
                    val adapter = moshi.adapter<List<QuizQuestion>>(quizType)
                    val questionsJson = adapter.toJson(quizQuestions)

                    dao.insertQuiz(
                        Quiz(
                            title = "Quiz: $title",
                            associatedNoteId = noteId,
                            category = category,
                            questionsJson = questionsJson
                        )
                    )

                    // 3. Save Flashcards
                    pack.flashcards.forEach {
                        dao.insertFlashcard(
                            Flashcard(
                                term = it.term,
                                definition = it.definition,
                                category = category
                            )
                        )
                    }

                    // 4. Create an automatic Interview Session
                    val sessionId = dao.insertInterviewSession(
                        MockInterviewSession(
                            title = "Oral on: $title",
                            category = category
                        )
                    ).toInt()

                    // Post first question to interview message log
                    val firstQuestion = pack.interviewQuestions.firstOrNull() ?: "How would you summarize the core concept of $title?"
                    dao.insertInterviewMessage(
                        MockInterviewMessage(
                            sessionId = sessionId,
                            role = "interviewer",
                            message = firstQuestion,
                            score = null,
                            feedback = "Let's begin evaluating your note inputs!"
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isGeneratingPack.value = false
            }
        }
    }

    // --- Feature 2: Quizzes ---
    fun selectQuiz(quiz: Quiz?) {
        _activeQuiz.value = quiz
        _currentQuestionIndex.value = 0
        _selectedOptionIndex.value = null
        _isAnswerSubmitted.value = false
        _quizCorrectAnswersCount.value = 0
        _quizCompleted.value = false
    }

    fun submitQuizAnswer(choiceIndex: Int, correctIndex: Int) {
        if (_isAnswerSubmitted.value) return
        _selectedOptionIndex.value = choiceIndex
        _isAnswerSubmitted.value = true
        if (choiceIndex == correctIndex) {
            _quizCorrectAnswersCount.value += 1
            // Save analytical details to user stats
            viewModelScope.launch(Dispatchers.IO) {
                val current = dao.getUserProfile() ?: UserProfile()
                dao.insertUserProfile(current.copy(questionsSolved = current.questionsSolved + 1))
            }
        }
    }

    fun nextQuizQuestion() {
        val quiz = _activeQuiz.value ?: return
        val questions = getQuizQuestionsList(quiz)
        _selectedOptionIndex.value = null
        _isAnswerSubmitted.value = false

        if (_currentQuestionIndex.value < questions.size - 1) {
            _currentQuestionIndex.value += 1
        } else {
            _quizCompleted.value = true
            // Save final score state to database
            viewModelScope.launch(Dispatchers.IO) {
                val scorePercent = ((_quizCorrectAnswersCount.value.toFloat() / questions.size) * 100).toInt()
                val updatedQuiz = quiz.copy(
                    highScore = maxOf(quiz.highScore, scorePercent),
                    totalAttempts = quiz.totalAttempts + 1
                )
                dao.insertQuiz(updatedQuiz)
            }
        }
    }

    fun getQuizQuestionsList(quiz: Quiz?): List<QuizQuestion> {
        if (quiz == null) return emptyList()
        return try {
            val quizType = Types.newParameterizedType(List::class.java, QuizQuestion::class.java)
            val adapter = moshi.adapter<List<QuizQuestion>>(quizType)
            adapter.fromJson(quiz.questionsJson) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun generateNewTopicQuiz(topic: String, category: String) {
        _isGeneratingPack.value = true
        viewModelScope.launch {
            try {
                val rawQuestions = withContext(Dispatchers.IO) {
                    GeminiService.generateTopicQuiz(topic, category)
                }
                withContext(Dispatchers.IO) {
                    val questions = rawQuestions.map {
                        QuizQuestion(
                            question = it.question,
                            options = it.options,
                            correctAnswerIndex = it.correctAnswerIndex,
                            explanation = it.explanation
                        )
                    }
                    val quizType = Types.newParameterizedType(List::class.java, QuizQuestion::class.java)
                    val adapter = moshi.adapter<List<QuizQuestion>>(quizType)
                    val json = adapter.toJson(questions)

                    val newQuizId = dao.insertQuiz(
                        Quiz(
                            title = "AI Quiz: $topic",
                            category = category,
                            questionsJson = json
                        )
                    )
                    // Auto select the new generated quiz
                    val saved = dao.getQuizById(newQuizId.toInt())
                    if (saved != null) {
                        selectQuiz(saved)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isGeneratingPack.value = false
            }
        }
    }

    fun deleteQuiz(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteQuizById(id)
            if (activeQuiz.value?.id == id) {
                _activeQuiz.value = null
            }
        }
    }

    // --- Feature 3: Flashcards ---
    fun toggleFlashcardLearned(card: Flashcard) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateFlashcard(card.copy(isKnown = !card.isKnown))
        }
    }

    fun createFlashcard(term: String, definition: String, category: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertFlashcard(
                Flashcard(
                    term = term,
                    definition = definition,
                    category = category
                )
            )
        }
    }

    fun deleteFlashcard(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteFlashcardById(id)
        }
    }

    fun changeFlashcardFilter(category: String) {
        _flashcardFilterCategory.value = category
    }

    // --- Feature 4: Explain Diff Topics ---
    fun askAiToExplain(topic: String, examGoal: String, mode: String) {
        _isExplainingTopic.value = true
        viewModelScope.launch {
            try {
                val aiExp = withContext(Dispatchers.IO) {
                    GeminiService.explainTopic(topic, examGoal, mode)
                }
                withContext(Dispatchers.IO) {
                    val listType = Types.newParameterizedType(List::class.java, String::class.java)
                    val keypointsJson = moshi.adapter<List<String>>(listType).toJson(aiExp.keyPoints)

                    dao.insertExplanation(
                        DifficultTopicExplanation(
                            topic = topic,
                            explanation = aiExp.explanation,
                            analogy = aiExp.analogy,
                            keyPointsJson = keypointsJson,
                            category = examGoal
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isExplainingTopic.value = false
            }
        }
    }

    fun deleteExplanation(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteExplanationById(id)
        }
    }

    fun getKeyPointsList(item: DifficultTopicExplanation): List<String> {
        return try {
            val listType = Types.newParameterizedType(List::class.java, String::class.java)
            moshi.adapter<List<String>>(listType).fromJson(item.keyPointsJson) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // --- Feature 5: Mock Interview ---
    fun selectInterviewSession(sessionId: Int?) {
        _activeSessionId.value = sessionId
    }

    fun startNewInterviewSession(title: String, category: String) {
        _isInterviewThinking.value = true
        viewModelScope.launch {
            try {
                val sessionId = withContext(Dispatchers.IO) {
                    dao.insertInterviewSession(
                        MockInterviewSession(
                            title = "Interview on: $title",
                            category = category
                        )
                    ).toInt()
                }

                _activeSessionId.value = sessionId

                // Generate opening interview question
                val initialQuestion = "Hello, I am your examiner panel lead for the $category preparation. Let's begin searching your capabilities regarding: $title. First Question: Can you outline the primary principles or constitutional guidelines governing this topic?"
                
                withContext(Dispatchers.IO) {
                    dao.insertInterviewMessage(
                        MockInterviewMessage(
                            sessionId = sessionId,
                            role = "interviewer",
                            message = initialQuestion,
                            score = null,
                            feedback = "Session started."
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isInterviewThinking.value = false
            }
        }
    }

    fun deleteSession(sessionId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteInterviewSessionById(sessionId)
            dao.deleteMessagesForSession(sessionId)
            if (activeSessionId.value == sessionId) {
                _activeSessionId.value = null
            }
        }
    }

    fun submitStudentAnswer(answerText: String) {
        val sessionId = _activeSessionId.value ?: return
        val messages = sessionMessages.value
        val lastInterviewerMessage = messages.lastOrNull { it.role == "interviewer" }
        val currentQuestion = lastInterviewerMessage?.message ?: "Discuss core principles"

        _isInterviewThinking.value = true

        viewModelScope.launch {
            try {
                // 1. Save user answer
                withContext(Dispatchers.IO) {
                    dao.insertInterviewMessage(
                        MockInterviewMessage(
                            sessionId = sessionId,
                            role = "user",
                            message = answerText
                        )
                    )
                }

                // 2. Fetch evaluation & next turn from Gemini
                val historyText = messages.takeLast(6).joinToString("\n") { "${it.role}: ${it.message}" }
                val currentCategory = userProfile.value?.selectedGoal ?: "UPSC"
                
                val evaluation = withContext(Dispatchers.IO) {
                    GeminiService.evaluateAndGenerateInterviewTurn(
                        examGoal = currentCategory,
                        sessionHistory = historyText,
                        currentQuestion = currentQuestion,
                        userAnswer = answerText
                    )
                }

                // 3. Save evaluation & next interviewer question
                withContext(Dispatchers.IO) {
                    dao.insertInterviewMessage(
                        MockInterviewMessage(
                            sessionId = sessionId,
                            role = "interviewer",
                            message = evaluation.nextQuestion,
                            score = evaluation.score,
                            feedback = evaluation.feedback
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isInterviewThinking.value = false
            }
        }
    }

    // --- DATABASE PRELOADING AND POPULATION BOOTSTRAPPER ---

    private suspend fun preloadDefaultMaterials() {
        // Preload standard UPSC Polity package
        preloadGoalSpecificMaterials("UPSC")
    }

    private suspend fun preloadGoalSpecificMaterials(category: String) {
        when (category) {
            "UPSC" -> {
                // Preload high quality UPSC essentials
                val noteId = dao.insertNote(
                    StudyNote(
                        title = "UPSC Polity: Fundamental Rights",
                        content = "Fundamental Rights are enshrined in Part III of the Constitution of India (Articles 12-35). They are justiciable, allowing citizens to approach High Courts under Article 226 or the Supreme Court under Article 32 (Constitutional Remedies) directly in case of violation. Vital components include Equal Treatment (Art 14), Basic Freedoms (Art 19), Right to Life & Liberty (Art 21), and Right to Constitutional Remedies (Art 32). The State has authorities to enforce reasonable restrictions concerning integrity, public order, and morality.",
                        summary = "Part III of the Constitution of India enshrines justiciable Fundamental Rights protecting individual liberties. Article 32 grants direct access to the Supreme Court as a safeguard",
                        keyTakeaways = "Justiciable articles (Articles 12-35)\nArticle 32 allows direct Supreme Court writs\nSubject to reasonable restrictions for national sovereignty and public order",
                        category = "UPSC"
                    )
                ).toInt()

                val questionsList = listOf(
                    QuizQuestion(
                        question = "Which article of the Indian Constitution grants citizens the Right to Constitutional Remedies (Writs)?",
                        options = listOf("Article 14", "Article 21", "Article 32", "Article 226"),
                        correctAnswerIndex = 2,
                        explanation = "Article 32 is called the 'heart and soul' of the constitution, granting direct approach rights to the Supreme Court for writ remedies."
                    ),
                    QuizQuestion(
                        question = "Fundamental Rights can be suspended during which type of crisis?",
                        options = listOf("Financial Emergency", "National Emergency (excluding Art 20 & 21)", "State President Rule", "Never suspended"),
                        correctAnswerIndex = 1,
                        explanation = "During a National Emergency under Article 352, fundamental liberties except Articles 20 and 21 can be temporarily suspended."
                    )
                )
                val quizQuestionsJson = moshi.adapter<List<QuizQuestion>>(
                    Types.newParameterizedType(List::class.java, QuizQuestion::class.java)
                ).toJson(questionsList)

                dao.insertQuiz(
                    Quiz(
                        title = "UPSC Fundamental Rights Boost",
                        associatedNoteId = noteId,
                        category = "UPSC",
                        questionsJson = quizQuestionsJson
                    )
                )

                // Flashcard
                dao.insertFlashcard(
                    Flashcard(
                        term = "Habeas Corpus",
                        definition = "A constitutional writ meaning 'to have the body of' protecting citizens from illegal or unauthorized detention.",
                        category = "UPSC"
                    )
                )
                dao.insertFlashcard(
                    Flashcard(
                        term = "Article 21",
                        definition = "Protection of Life and Personal Liberty; cannot be suspended even during emergencies.",
                        category = "UPSC"
                    )
                )

                // Interview
                val sId = dao.insertInterviewSession(
                    MockInterviewSession(title = "UPSC Polity Session", category = "UPSC")
                ).toInt()
                dao.insertInterviewMessage(
                    MockInterviewMessage(
                        sessionId = sId,
                        role = "interviewer",
                        message = "Welcome UPSC aspirant. Can you explain why the Right to Privacy is read into Article 21, citing the famous K.S. Puttaswamy judgment?",
                        score = null,
                        feedback = "Begin"
                    )
                )
            }
            "JEE_NEET" -> {
                // Preload Physics Mechanics
                val noteId = dao.insertNote(
                    StudyNote(
                        title = "JEE Physics: Laws of Motion",
                        content = "Newton's three laws govern standard dynamics. 1. Law of Inertia: Body maintains state unless external unbalanced force is model. 2. Force equals rate of change of momentum (F = dp/dt = ma). 3. Every action has an equal and opposite reaction. In JEE, drawing free-body diagram representing all contact forces (normal, frictional, tension, weights) and setting directional force equations represents 100% of standard mechanical solvers.",
                        summary = "Newton's laws govern mechanics. The cornerstone to solving NEET/JEE mechanics lies in drawing complete Free Body Diagrams (FBDs) and applying F = ma along isolated directional coordinate axes.",
                        keyTakeaways = "First Law defines inertia\nSecond Law sets force as ma\nThird Law concerns equal/opposite force pairing\nFBD resolution simplifies multi-body string pulley systems",
                        category = "JEE_NEET"
                    )
                ).toInt()

                val questionsList = listOf(
                    QuizQuestion(
                        question = "A pulley system has a mass of 2kg on one end and 3kg on the other. What is the acceleration of the system (ignoring friction and rope weight)?",
                        options = listOf("g / 5", "g / 2", "g", "2g / 3"),
                        correctAnswerIndex = 0,
                        explanation = "Acceleration a = (m2 - m1)g / (m1 + m2) = (3 - 2)g / (3 + 2) = g / 5."
                    )
                )
                val quizQuestionsJson = moshi.adapter<List<QuizQuestion>>(
                    Types.newParameterizedType(List::class.java, QuizQuestion::class.java)
                ).toJson(questionsList)

                dao.insertQuiz(
                    Quiz(
                        title = "NEET/JEE Mechanics Practice",
                        associatedNoteId = noteId,
                        category = "JEE_NEET",
                        questionsJson = quizQuestionsJson
                    )
                )

                dao.insertFlashcard(
                    Flashcard(
                        term = "Static Friction",
                        definition = "Self-adjusting friction force acting to oppose initial motion relative acceleration. Max limit is μs * N.",
                        category = "JEE_NEET"
                    )
                )

                val sId = dao.insertInterviewSession(
                    MockInterviewSession(title = "JEE Physics Mechanics Panel", category = "JEE_NEET")
                ).toInt()
                dao.insertInterviewMessage(
                    MockInterviewMessage(
                        sessionId = sId,
                        role = "interviewer",
                        message = "Hello engineering/medical aspirant. Explain the work-energy theorem of forces and why non-conservative friction violates kinetic mechanical energy conservation.",
                        score = null,
                        feedback = "Begin Session"
                    )
                )
            }
            "ENGINEERING" -> {
                // Preload Computer Science Database
                val noteId = dao.insertNote(
                    StudyNote(
                        title = "Engineering: Database Normalization",
                        content = "Database normalization improves relational layouts by reducing data redundancy and anomalies (Insert, Update, Delete). Standard Normal Forms exist: 1NF requires atomic cell values. 2NF removes partial key dependencies (requires complete candidate keys). 3NF removes transitive functional dependencies (No non-prime attribute dictates another non-prime). BCNF (Boyce-Codd) requires that for every functional dependency A -> B, A must be a superkey.",
                        summary = "Normalization prevents update, delete, and insert anomalies. Normal levels progress sequentially from atomic states (1NF) up to key dependency compliance (3NF & BCNF).",
                        keyTakeaways = "1NF: Atomic values\n2NF: No partial key dependency\n3NF: No transitive dependency\nBCNF: Left side of any dependency must be a superkey",
                        category = "ENGINEERING"
                    )
                ).toInt()

                val questionsList = listOf(
                    QuizQuestion(
                        question = "If a relation is in 3NF and for every dependency X -> Y, X is a superkey, which normal form is it in?",
                        options = listOf("2NF", "3NF", "BCNF", "4NF"),
                        correctAnswerIndex = 2,
                        explanation = "BCNF is a stricter form of 3NF where every determinant (left side) must be a candidate superkey."
                    )
                )
                val quizQuestionsJson = moshi.adapter<List<QuizQuestion>>(
                    Types.newParameterizedType(List::class.java, QuizQuestion::class.java)
                ).toJson(questionsList)

                dao.insertQuiz(
                    Quiz(
                        title = "DBMS Gate Exam Booster",
                        associatedNoteId = noteId,
                        category = "ENGINEERING",
                        questionsJson = quizQuestionsJson
                    )
                )

                dao.insertFlashcard(
                    Flashcard(
                        term = "Transitive Dependency",
                        definition = "When attribute A dictates B, and B dictates C (where B is not a key) - violating 3NF compliance.",
                        category = "ENGINEERING"
                    )
                )

                val sId = dao.insertInterviewSession(
                    MockInterviewSession(title = "DBMS Engineering Board", category = "ENGINEERING")
                ).toInt()
                dao.insertInterviewMessage(
                    MockInterviewMessage(
                        sessionId = sId,
                        role = "interviewer",
                        message = "Welcome candidate. Can you explain the difference between 3NF and BCNF, illustrating with a dependency relation example?",
                        score = null,
                        feedback = "Begin"
                    )
                )
            }
        }
    }
}
