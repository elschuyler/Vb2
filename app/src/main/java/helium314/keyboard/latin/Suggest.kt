package helium314.keyboard.latin

import com.android.inputmethod.latin.BinaryDictionary
import com.example.foundation.common.Constants
import com.example.foundation.utils.CoordinateUtils
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion

/**
 * Suggestion engine coordinator interfacing with binary dictionaries and user history.
 */
class Suggest(val dictionaryFacilitator: DictionaryFacilitator) {

    fun getSuggestedWords(
        wordComposer: WordComposer,
        ngramContext: NgramContext,
        keyboard: Keyboard?,
        settingsValues: SettingsValuesForSuggestion,
        isPrediction: Boolean,
        inputStyle: Int,
        sequenceNumber: Int
    ): SuggestedWords {
        val typed = wordComposer.typedWord
        if (typed.isEmpty() && ngramContext.prevWordsCount == 0) return SuggestedWords.EMPTY

        val facilitator = dictionaryFacilitator as? DictionaryFacilitatorImpl
        val isValid = if (typed.isNotEmpty()) facilitator?.isValidWord(typed) ?: false else false
        val freq = if (typed.isNotEmpty()) facilitator?.getFrequency(typed) ?: -1 else -1

        val candidates = mutableListOf<String>()
        val inputCodePoints = wordComposer.getCodePoints()
        val inputSize = inputCodePoints.size
        val coords = wordComposer.coordinates
        val xCoordinates = IntArray(inputSize)
        val yCoordinates = IntArray(inputSize)
        for (i in 0 until inputSize) {
            xCoordinates[i] = CoordinateUtils.xFromCoordinates(coords, i)
            yCoordinates[i] = CoordinateUtils.yFromCoordinates(coords, i)
        }
        val times = IntArray(inputSize)
        val pointerIds = IntArray(inputSize)
        val suggestOptions = IntArray(1) // Normal typing

        // Extract N-gram context from committed words for bigram/trigram native suggestion
        val prevWordCodePointArrays = ngramContext.extractPrevWordsCodePointArrays()
        val isBeginningOfSentence = ngramContext.extractIsBeginningOfSentenceArray()
        val prevWordCount = ngramContext.prevWordsCount

        // Resolve proximity info pointer from keyboard model
        val proximityInfoPtr = keyboard?.proximityInfo?.nativeProximityInfo ?: 0L

        // Query native dictionaries if available with scoring and traversal session
        val dicts = facilitator?.getDictionaries() ?: emptyList()
        val allScoredCandidates = mutableListOf<BinaryDictionary.ScoredCandidate>()

        for (dict in dicts) {
            val sessionPtr = dict.traverseSession.nativeSession
            val scoredSuggestions = dict.getScoredSuggestions(
                proximityInfo = proximityInfoPtr,
                traverseSession = sessionPtr,
                xCoordinates = xCoordinates,
                yCoordinates = yCoordinates,
                times = times,
                pointerIds = pointerIds,
                inputCodePoints = inputCodePoints,
                inputSize = inputSize,
                suggestOptions = suggestOptions,
                prevWordCodePointArrays = prevWordCodePointArrays,
                isBeginningOfSentenceArray = isBeginningOfSentence,
                prevWordCount = prevWordCount
            )
            allScoredCandidates.addAll(scoredSuggestions)
        }

        // Sort candidates by native score descending (highest score first)
        // Apply scoring penalty to demoted words unless the typed word is an exact match
        val sortedScored = allScoredCandidates.map { cand ->
            if (facilitator?.isWordDemoted(cand.word) == true) {
                val penalizedScore = if (cand.word.equals(typed, ignoreCase = true)) {
                    cand.score
                } else {
                    (cand.score - 250).coerceAtLeast(1)
                }
                cand.copy(score = penalizedScore)
            } else {
                cand
            }
        }.sortedByDescending { it.score }

        val nativeSuggestions = mutableListOf<String>()
        for (cand in sortedScored) {
            if (!nativeSuggestions.contains(cand.word)) {
                nativeSuggestions.add(cand.word)
            }
        }

        // Slot 0: Literal typed word with baseline score (if currently composing)
        val scoredList = mutableListOf<Pair<String, Int>>()
        if (typed.isNotEmpty()) {
            val typedScore = if (isValid) freq.coerceAtLeast(1) else 0
            scoredList.add(Pair(typed, typedScore))
        }

        // Slots 1+: Best native correction, prediction, or dictionary match with actual scores
        for (cand in sortedScored) {
            if (scoredList.none { it.first == cand.word }) {
                scoredList.add(Pair(cand.word, cand.score))
                if (scoredList.size >= 5) break
            }
        }

        val willAutoCorrect = if (typed.isNotEmpty()) {
            isValid || (scoredList.size > 1 && (freq > 0 || (sortedScored.firstOrNull()?.score ?: 0) > 0))
        } else {
            false
        }
        return SuggestedWords.createWithScores(scoredList, willAutoCorrect)
    }

    fun close() {
        dictionaryFacilitator.closeDictionaries()
    }
}

